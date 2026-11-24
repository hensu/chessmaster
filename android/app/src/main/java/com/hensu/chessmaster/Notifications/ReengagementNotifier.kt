// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Local "come back and play" notifications for lapsed users. Each time the
 * app is used we (re)schedule a cascade of future reminders measured from
 * *now*; returning to the app reschedules them, so an active player never
 * sees one. Content is personalized from on-device history.
 *
 * Cadence: day 3 / 7 / 14 / 30 for everyone; after day 30, a ~quarterly
 * win-back for paid users only. Delivery is provisional (quiet) unless the
 * player has already granted full notification permission — no hard prompt.
 */
object ReengagementNotifier {
    private const val idPrefix = "reengage-"
    private val everyoneTiers = listOf(3, 7, 14, 30)
    /** After day 30, ~every 90 days for a year — paid users only. */
    private val paidQuarterlyTiers = listOf(120, 210, 300, 390)

    // MARK: - Entry point

    fun refresh(container: DependencyContainer) {
        if (App.arguments.contains("--uitest")) return
        // Rides the same hooks; independent of server push (which doesn't
        // send coach nudges).
        CoachNudgeNotifier.refresh(container)
        // Once the backend can push this signed-in user (token registered),
        // the server owns re-engagement — don't also fire local ones.
        if (container.sync.isSignedIn && container.sync.hasRemoteToken) {
            cancelAll()
            return
        }
        // The signals are gathered inside the task (the Swift gathers them just before it):
        // the puzzle file may still be parsing in the background right after launch, and
        // waiting for it here must not block the main thread.
        MainScope().launch {
            val games = try {
                container.games.recentGames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val rating = container.ratings.ratings.values
                .filter { it.deviation < 250 }
                .map { it.rating.roundToInt() }
                .filter { it >= 400 }
                .maxOrNull()
            val isPaid = container.entitlements.plan != Plan.free
            LearnContent.awaitLoaded()
            val unsolvedCategory = PuzzleCategory.entries.firstOrNull {
                container.learnProgress.solvedCount(it) < LearnContent.puzzles(it).size
            }?.title
            val signals = Signals(
                games = games,
                rating = rating,
                isPaid = isPaid,
                lostLast = lastWasLoss(games),
                favoriteTimeControl = favoriteTimeControl(games),
                nearNextLevel = nearNextLevel(rating),
                unsolvedPuzzleCategory = unsolvedCategory,
            )
            schedule(signals)
        }
    }

    fun cancelAll() {
        val ids = (everyoneTiers + paidQuarterlyTiers).map { "$idPrefix$it" }
        LocalNotifications.cancel(ids)
    }

    // MARK: - Scheduling

    /** `(points: Int, name: String)` */
    private data class NearLevel(val points: Int, val name: String)

    private data class Signals(
        val games: List<GameRecord>,
        val rating: Int?,
        val isPaid: Boolean,
        val lostLast: Boolean,
        val favoriteTimeControl: String,
        val nearNextLevel: NearLevel?,
        val unsolvedPuzzleCategory: String?,
    )

    /** `(title: String, body: String, reason: String)` */
    private data class Copy(val title: String, val body: String, val reason: String)

    private fun schedule(s: Signals) {
        // Need real history before nudging — nothing to personalize otherwise.
        if (s.games.none { it.termination != "aborted" }) {
            cancelAll(); return
        }

        // Permission is requested loudly after a win (NotificationPermission);
        // only schedule once the user has granted it.
        if (!LocalNotifications.authorized) return

        cancelAll()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val hour = usualPlayHour(s.games)
        val tiers = if (s.isPaid) everyoneTiers + paidQuarterlyTiers else everyoneTiers

        for (day in tiers) {
            val fire = now.atZone(zone).plusDays(day.toLong())
                .withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant()
            if (fire <= now) continue
            val copy = message(day, s)
            LocalNotifications.schedule(
                "$idPrefix$day", copy.title, copy.body, fire,
                mapOf("reason" to copy.reason, "tier" to day.toString(), "action" to "play"))
        }
    }

    // MARK: - Copy (personalized per tier)

    private fun message(forDay: Int, s: Signals): Copy {
        when (forDay) {
            3 -> {
                val near = s.nearNextLevel
                if (near != null) {
                    return Copy("Almost there ♟️",
                                "You're ${near.points} points from ${near.name}. One good game could get you there.",
                                "near_level")
                }
                if (s.lostLast) {
                    return Copy("Rematch?", "That last game didn't go your way. Ready to even the score?", "avenge_loss")
                }
                return Copy("Your move ♟️", "Your board's set for ${s.favoriteTimeControl}. Fancy a quick game?", "favorite_tc")
            }
            7 -> {
                val cat = s.unsolvedPuzzleCategory
                if (cat != null) {
                    return Copy("Keep your edge", "A fresh set of $cat puzzles is waiting to sharpen your tactics.", "puzzles")
                }
                return Copy("Keep your edge", "A week off dulls the tactics. One game to stay sharp?", "stay_sharp")
            }
            14 -> {
                val r = s.rating
                if (r != null) {
                    return Copy("Your $r rating misses you", "Two weeks off — one quick game to knock off the rust?", "rating_miss")
                }
                return Copy("It's been a while ♟️", "Two weeks since your last game. Ready for a quick one?", "two_weeks")
            }
            30 ->
                return Copy("Your board's still set ♟️", "Right where you left it. Come back for a single game?", "winback_30")
            else ->
                // Quarterly win-back — paid users only.
                return Copy("Your coach is still here", "Whenever you're ready, a game and a fresh AI review are waiting.", "winback_paid_quarterly")
        }
    }

    // MARK: - Signals

    /**
     * The hour the player most often finishes games (median of last 30),
     * defaulting to 7pm — so reminders land when they actually play.
     */
    fun usualPlayHour(games: List<GameRecord>): Int {
        val zone = ZoneId.systemDefault()
        val hours = games
            .filter { it.opponentType == "engine" && it.termination != "aborted" }
            .take(30)
            .map { it.endedAt.atZone(zone).hour }
            .sorted()
        if (hours.isEmpty()) return 19
        return hours[hours.size / 2]
    }

    /** The player's most-played time control, as a short label ("5+3"). */
    private fun favoriteTimeControl(games: List<GameRecord>): String {
        val recent = games.filter { it.opponentType == "engine" && it.termination != "aborted" }.take(30)
        if (recent.isEmpty()) return "a quick game"
        val counts = mutableMapOf<String, Int>()
        for (g in recent) {
            val initial = g.tcInitialSeconds
            val label = if (initial != null) {
                "${initial / 60}+${g.tcIncrementSeconds ?: 0}"
            } else {
                "a game"
            }
            counts[label] = (counts[label] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }?.key ?: "a quick game"
        return if (best == "a game") "a quick game" else "a $best game"
    }

    /** Whether the most recent rated game was a loss (rating dropped). */
    private fun lastWasLoss(games: List<GameRecord>): Boolean {
        val last = games.firstOrNull {
            it.ratingBefore != null && it.ratingAfter != null && it.termination != "aborted"
        } ?: return false
        return (last.ratingAfter ?: 0.0) < (last.ratingBefore ?: 0.0) - 0.5
    }

    /** If within 150 points of the next level up, the gap and that level's name. */
    private fun nearNextLevel(rating: Int?): NearLevel? {
        if (rating == null) return null
        val next = (1..10)
            .map { StrengthLevel.level(it) }
            .filter { it.ratingEstimate > rating }
            .minByOrNull { it.ratingEstimate }
        if (next == null || next.ratingEstimate - rating > 150) return null
        return NearLevel(next.ratingEstimate - rating, "Level ${next.level} · ${next.displayName}")
    }
}
