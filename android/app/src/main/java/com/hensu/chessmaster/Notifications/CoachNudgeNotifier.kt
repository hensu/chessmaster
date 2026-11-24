// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Local nudges for trial/paid players who have never used the AI coach —
 * the feature they're paying for. Tapping opens Play, where AutoCoach
 * generates their first report and hands it over, so the notification
 * only has to bring them back.
 *
 * - "coach-ready": next day at the player's usual play hour.
 * - "coach-trial-end": the day before the free trial ends (trial only).
 *
 * Rescheduled on the same hooks as ReengagementNotifier (launch,
 * background, after games); cancelled once the player has a report.
 */
object CoachNudgeNotifier {
    private const val readyID = "coach-ready"
    private const val trialEndID = "coach-trial-end"

    fun refresh(container: DependencyContainer) {
        if (App.arguments.contains("--uitest")) return
        MainScope().launch {
            val games = try {
                container.games.recentGames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            // Same gate as the in-app report: premium, never coached, a game worth coaching.
            if (AutoCoach.candidate(games, container) == null) {
                cancelAll(); return@launch
            }
            val trialEndsAt = container.entitlements.trialEndsAt
            val hour = ReengagementNotifier.usualPlayHour(games)
            schedule(trialEndsAt, hour)
        }
    }

    fun cancelAll() {
        LocalNotifications.cancel(listOf(readyID, trialEndID))
    }

    private fun schedule(trialEndsAt: Instant?, hour: Int) {
        if (!LocalNotifications.authorized) return
        cancelAll()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()

        val ready = now.atZone(zone).plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant()
        add(readyID, ready, "coach_ready",
            "Your AI coach is ready 🧠",
            if (trialEndsAt == null)
                "See where your last game turned — and how to play it better next time."
            else
                "See where your last game turned — and how to play it better. It's included in your free trial.")

        // The day before the trial ends — their last chance to see the value
        // before being charged (or cancelling).
        if (trialEndsAt != null) {
            val fire = trialEndsAt.atZone(zone).minusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant()
            if (fire > now.plusSeconds(2 * 3600)) {
                add(trialEndID, fire, "coach_trial_end",
                    "Your trial ends tomorrow",
                    "You haven't tried your AI coach yet — get a personal review of your last game before it ends.")
            }
        }
    }

    private fun add(id: String, fire: Instant, reason: String, title: String, body: String) {
        if (fire <= Instant.now()) return
        LocalNotifications.schedule(id, title, body, fire,
                                    mapOf("reason" to reason, "tier" to "0", "action" to "play"))
    }
}
