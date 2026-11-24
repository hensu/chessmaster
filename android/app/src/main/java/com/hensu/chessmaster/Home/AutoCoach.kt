// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.icu.text.RelativeDateTimeFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant

/**
 * The proactive first AI report. A trial/paid player who has never seen
 * the coach is the subscriber most likely to churn — 3 of 4 trialists in
 * Oct 2026 never tapped "Get AI Coaching". So on their next app open we
 * generate the report for their latest game in the background and hand
 * them the finished result, instead of waiting for a tap.
 *
 * Once per player: the first completed report (auto or manual, on any
 * device) retires it for good. Generation failures retry on later opens,
 * capped at 3 attempts so a broken game can't burn model spend forever.
 */
object AutoCoach {
    data class Ready(val game: GameRecord, val report: CoachingReport) {
        val id: String get() = game.id
    }

    private const val doneKey = "autoCoach.done"
    private const val attemptsKey = "autoCoach.attempts"
    private const val maxAttempts = 3
    /** Too-short games give the coach nothing to say. */
    private const val minPlies = 16

    /** Cheap local gate — no network. Returns the game worth coaching. */
    fun candidate(games: List<GameRecord>, container: DependencyContainer): GameRecord? {
        val defaults = Defaults.standard
        if (!(!App.arguments.contains("--uitest") &&
                container.entitlements.isPremium &&
                container.sync.isConfigured && container.sync.isSignedIn &&
                !defaults.bool(doneKey) &&
                defaults.integer(attemptsKey) < maxAttempts)) return null
        if (games.any { it.coachingJSON != null }) {
            defaults.set(doneKey, true)
            return null
        }
        return games
            .filter { it.opponentType == "engine" && it.termination != "aborted" }
            .filter { (GameReplay.of(it.pgn)?.plies?.size ?: 0) >= minPlies }
            .maxByOrNull { it.endedAt }
    }

    /**
     * Confirms with the server that this player has no report anywhere,
     * then generates one. null = nothing to show (already coached, offline,
     * or the model failed — a later open retries).
     */
    suspend fun run(game: GameRecord, container: DependencyContainer): Ready? {
        val defaults = Defaults.standard
        when (container.sync.hasCompletedCoachingReport()) {
            true -> {
                defaults.set(doneKey, true)
                return null
            }
            null -> return null   // unknown — never spend a report on a guess
            false -> {}
        }
        defaults.set(attemptsKey, defaults.integer(attemptsKey) + 1)
        val plan = container.entitlements.plan.rawValue
        container.sync.track("auto_coach_started", mapOf("plan" to plan))
        val report = autoCoachGenerate(game.id, container)
        val json = report?.let { runCatching { AppJson.encodeToString(CoachingReport.serializer(), it) }.getOrNull() }
        if (report == null || json == null) {
            container.sync.track("auto_coach_failed", mapOf("plan" to plan))
            return null
        }
        runCatching { container.games.updateCoaching(game.id, json) }
        defaults.set(doneKey, true)
        container.sync.track("auto_coach_ready", mapOf("plan" to plan))
        val updated = runCatching { container.games.game(game.id) }.getOrNull() ?: game
        return Ready(game = updated, report = report)
    }
}

/** `try? await CoachingGenerator.generate(gameID:sync:)`: null on any failure, cancellation passes through. */
private suspend fun autoCoachGenerate(gameID: String, container: DependencyContainer): CoachingReport? =
    try {
        CoachingGenerator.generate(gameID, container.sync)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/**
 * `Date.formatted(.relative(presentation: .named))`: "now", "5 minutes ago", "yesterday",
 * "3 days ago", "last week", "2 months ago"…
 */
private fun autoCoachRelative(date: Instant, now: Instant = Instant.now()): String {
    val formatter = RelativeDateTimeFormatter.getInstance()
    val seconds = Duration.between(date, now).seconds
    val future = seconds < 0
    val direction = if (future) RelativeDateTimeFormatter.Direction.NEXT else RelativeDateTimeFormatter.Direction.LAST
    val s = kotlin.math.abs(seconds)
    fun numeric(n: Long, unit: RelativeDateTimeFormatter.RelativeUnit) = formatter.format(n.toDouble(), direction, unit)
    fun named(n: Long, absolute: RelativeDateTimeFormatter.AbsoluteUnit, unit: RelativeDateTimeFormatter.RelativeUnit) =
        if (n == 1L) formatter.format(direction, absolute) else numeric(n, unit)
    return when {
        s < 1 -> formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        s < 60 -> numeric(s, RelativeDateTimeFormatter.RelativeUnit.SECONDS)
        s < 3_600 -> numeric(s / 60, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        s < 86_400 -> numeric(s / 3_600, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        s < 7 * 86_400 -> named(s / 86_400, RelativeDateTimeFormatter.AbsoluteUnit.DAY, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        s < 30 * 86_400 -> named(s / (7 * 86_400), RelativeDateTimeFormatter.AbsoluteUnit.WEEK, RelativeDateTimeFormatter.RelativeUnit.WEEKS)
        s < 365 * 86_400 -> named(s / (30 * 86_400), RelativeDateTimeFormatter.AbsoluteUnit.MONTH, RelativeDateTimeFormatter.RelativeUnit.MONTHS)
        else -> named(s / (365 * 86_400), RelativeDateTimeFormatter.AbsoluteUnit.YEAR, RelativeDateTimeFormatter.RelativeUnit.YEARS)
    }
}

/**
 * "Your coach reviewed your last game" — the finished report, one tap away.
 * Shown in a sheet; `onDismiss` is the Swift `dismiss()` ("Later").
 */
@Composable
fun AutoCoachCard(ready: AutoCoach.Ready, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val game = ready.game
    val outcome = if (game.result == "draw") "Draw" else {
        val won = (game.result == "whiteWin" && game.playerColor == "white") ||
            (game.result == "blackWin" && game.playerColor == "black")
        if (won) "Win" else "Loss"
    }
    val opponent = if (game.opponentType == "friend") "a friend" else "Level ${game.engineLevel ?: 1}"

    // .presentationDetents([.height(440)]): the sheet's drag handle takes the rest of the height.
    Column(
        Modifier.fillMaxWidth().heightIn(min = 400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Sys.green,
             modifier = Modifier.padding(top = 28.dp).size(34.dp))

        Text("Your AI coach reviewed your last game",
             style = Sys.title3.weight(FontWeight.Bold), color = Sys.primary, textAlign = TextAlign.Center,
             modifier = Modifier.padding(horizontal = 24.dp))

        Text("$outcome vs $opponent · ${autoCoachRelative(game.endedAt)}",
             style = Sys.footnote.weight(FontWeight.SemiBold), color = Sys.secondary)

        Text(ready.report.summary,
             style = Sys.subheadline, color = Sys.secondary, textAlign = TextAlign.Center,
             maxLines = 4, overflow = TextOverflow.Ellipsis,
             modifier = Modifier.padding(horizontal = 24.dp))

        Box(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(52.dp).clip(CircleShape)
                .background(Sys.green.opacity(0.85)).clickable { onOpen() }.testTag("autoCoachOpen"),
            contentAlignment = Alignment.Center,
        ) {
            Text("See my report", style = Sys.headline, color = Color.White)
        }

        Text("Later", style = Sys.subheadline, color = Sys.secondary,
             modifier = Modifier.padding(bottom = 20.dp).clip(CircleShape).clickable { onDismiss() }
                 .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/**
 * Re-engagement for subscribers who stopped using the coach: after 3
 * finished games in a row without tapping "Get AI Coaching", the next
 * game's report is generated automatically and shown (post-game analysis
 * if they open it, else the Home card). Then it's back to the button and
 * the count starts over. A manual tap resets the count.
 */
object CoachHabit {
    private const val skippedKey = "coachHabit.skipped"
    private const val dueKey = "coachHabit.dueGame"
    const val threshold = 3
    private const val minPlies = 16

    /** Call once per finished game (after it's saved). */
    fun gameFinished(record: GameRecord, container: DependencyContainer) {
        if (!(!App.arguments.contains("--uitest") &&
                container.entitlements.isPremium && container.sync.isConfigured &&
                record.termination != "aborted" &&
                (GameReplay.of(record.pgn)?.plies?.size ?: 0) >= minPlies)) return
        val defaults = Defaults.standard
        val skipped = defaults.integer(skippedKey)
        if (skipped >= threshold) {
            defaults.set(dueKey, record.id)
            defaults.set(skippedKey, 0)
            container.sync.track("coach_habit_due", mapOf("plan" to container.entitlements.plan.rawValue))
        } else {
            defaults.set(skippedKey, skipped + 1)
        }
    }

    /** The player asked for a report themselves. */
    fun coachingRequested() {
        Defaults.standard.set(skippedKey, 0)
    }

    fun isDue(gameID: String): Boolean = Defaults.standard.string(dueKey) == gameID

    fun clearDue() {
        Defaults.standard.remove(dueKey)
    }

    /**
     * The due game if it still has no report (the player closed the game
     * without opening its analysis).
     */
    fun dueGame(container: DependencyContainer): GameRecord? {
        if (!container.entitlements.isPremium) return null
        val id = Defaults.standard.string(dueKey) ?: return null
        val game = runCatching { container.games.game(id) }.getOrNull()
        if (game == null || game.coachingJSON != null) {
            clearDue()
            return null
        }
        return game
    }

    /** Generates the due game's report for the Home card. */
    suspend fun run(game: GameRecord, container: DependencyContainer): AutoCoach.Ready? {
        clearDue()
        val plan = container.entitlements.plan.rawValue
        container.sync.track("auto_coach_started", mapOf("plan" to plan, "trigger" to "habit"))
        val report = autoCoachGenerate(game.id, container)
        val json = report?.let { runCatching { AppJson.encodeToString(CoachingReport.serializer(), it) }.getOrNull() }
        if (report == null || json == null) {
            container.sync.track("auto_coach_failed", mapOf("plan" to plan, "trigger" to "habit"))
            return null
        }
        runCatching { container.games.updateCoaching(game.id, json) }
        container.sync.track("auto_coach_ready", mapOf("plan" to plan, "trigger" to "habit"))
        val updated = runCatching { container.games.game(game.id) }.getOrNull() ?: game
        return AutoCoach.Ready(game = updated, report = report)
    }
}
