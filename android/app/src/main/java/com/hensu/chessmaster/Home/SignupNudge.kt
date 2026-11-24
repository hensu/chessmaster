// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The "save your progress" nudge for signed-out players. Principle:
 * prompt when they have something to lose, never when they're trying to
 * play. Triggers: the first finished game ever, or a cold start once the
 * local pile is worth saving. Skips back off exponentially (2 → 4 → 7
 * days, once per day at most), but a fresh milestone — longer streak,
 * more games banked — resets the clock, because the pitch just got
 * stronger than the one they rejected.
 */
object SignupNudge {
    data class Prompt(
        val trigger: String,          // first_game | milestone | cold_start
        val streakDays: Int,
        val games: Int,
        val rating: Int?,
    ) {
        val id: String get() = trigger
    }

    private const val shownKey = "nudge.lastShownAt"
    private const val skipsKey = "nudge.skips"
    private const val milestoneKey = "nudge.milestone"

    fun evaluate(games: List<GameRecord>, isSignedIn: Boolean, isConfigured: Boolean, rating: Int?): Prompt? {
        val allGames = games
        if (!(isConfigured && !isSignedIn && !App.arguments.contains("--uitest"))) return null
        val finished = allGames.count { it.opponentType == "engine" && it.termination != "aborted" }
        if (finished < 1) return null

        val defaults = Defaults.standard
        // A Date is stored as epoch seconds.
        val lastShown: Double? = if (defaults.has(shownKey)) defaults.double(shownKey) else null
        val streaks = Streaks.compute(allGames)

        // First finished game: the moment they created something to keep.
        if (finished == 1 && lastShown == null) {
            return record(Prompt(trigger = "first_game", streakDays = streaks.playDays,
                                 games = finished, rating = rating))
        }

        // Milestone buckets — crossing one re-arms the nudge immediately.
        val streakBucket = listOf(14, 7, 3, 2).firstOrNull { streaks.playDays >= it } ?: 0
        val gamesBucket = listOf(50, 25, 10, 5).firstOrNull { finished >= it } ?: 0
        val milestone = "s$streakBucket-g$gamesBucket"
        val newMilestone = milestone != defaults.string(milestoneKey) &&
            (streakBucket > 0 || gamesBucket > 0)

        // Nothing at stake yet: stay quiet.
        if (!(streakBucket > 0 || gamesBucket > 0)) return null

        // Frequency: at most daily; skips back off 2 → 4 → 7 days unless a
        // new milestone resets the schedule.
        if (lastShown != null) {
            val daysSince = (Instant.now().toEpochMilli() / 1000.0 - lastShown) / 86_400
            if (daysSince < 1) return null
            if (!newMilestone) {
                val skips = defaults.integer(skipsKey)
                val required = min(7.0, 2.0.pow(max(1, skips).toDouble()))
                if (daysSince < required) return null
            }
        }

        defaults.set(milestoneKey, milestone)
        return record(Prompt(trigger = if (newMilestone) "milestone" else "cold_start",
                             streakDays = streaks.playDays, games = finished, rating = rating))
    }

    fun skipped() {
        Defaults.standard.set(skipsKey, Defaults.standard.integer(skipsKey) + 1)
    }

    private fun record(prompt: Prompt): Prompt {
        Defaults.standard.set(shownKey, Instant.now().toEpochMilli() / 1000.0)
        return prompt
    }
}

/**
 * The personalized "keep your progress" card.
 * Shown in a sheet; `onDismiss` is the Swift `dismiss()` ("Not now").
 */
@Composable
fun SignupNudgeCard(prompt: SignupNudge.Prompt, onSignUp: () -> Unit, onDismiss: () -> Unit) {
    // .presentationDetents([.height(420)]): the sheet's drag handle takes the rest of the height.
    Column(
        Modifier.fillMaxWidth().heightIn(min = 380.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.launch_logo), contentDescription = null, contentScale = ContentScale.Fit,
              modifier = Modifier.padding(top = 26.dp).size(52.dp).clip(RoundedCornerShape(12.dp)))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (prompt.streakDays >= 2) {
                SignupNudgeStat("🔥", "${prompt.streakDays}-day streak")
            }
            SignupNudgeStat("♟️", "${prompt.games} game${if (prompt.games == 1) "" else "s"}")
            prompt.rating?.let { rating ->
                SignupNudgeStat("📈", "rating $rating")
            }
        }

        Text(if (prompt.trigger == "first_game") "Your first game lives only on this phone."
             else "All of it lives only on this phone.",
             style = Sys.headline, color = Sys.primary, textAlign = TextAlign.Center)
        Text("Create a free account and everything follows you — new phone, reinstall, anywhere.",
             style = Sys.subheadline, color = Sys.secondary, textAlign = TextAlign.Center,
             modifier = Modifier.padding(horizontal = 24.dp))

        Box(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(52.dp).clip(CircleShape)
                .background(Sys.green.opacity(0.85)).clickable { onSignUp() },
            contentAlignment = Alignment.Center,
        ) {
            Text("Create free account", style = Sys.headline, color = Color.White)
        }

        Text("Not now", style = Sys.subheadline, color = Sys.secondary,
             modifier = Modifier.padding(bottom = 20.dp).clip(CircleShape)
                 .clickable {
                     SignupNudge.skipped()
                     onDismiss()
                 }
                 .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun SignupNudgeStat(emoji: String, text: String) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Sys.secondary.opacity(0.1))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(emoji, style = Sys.title3)
        Text(text, style = Sys.caption.weight(FontWeight.SemiBold), color = Sys.primary)
    }
}
