// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Local streak notifications: an evening "streak on the line" reminder and
 * a next-morning milestone nudge. Permission is requested only once the
 * player has a streak worth protecting.
 */
object StreakNotifier {
    private const val reminderID = "streak-reminder"
    private const val milestoneID = "streak-milestone"
    private val winMilestones: Set<Int> = setOf(3, 5, 10, 20)
    private val dayMilestones: Set<Int> = setOf(3, 7, 14, 30)

    /**
     * Recomputes streaks and (re)schedules notifications. Call after every
     * finished game and once at launch.
     */
    fun refresh(games: List<GameRecord>) {
        if (App.arguments.contains("--uitest")) return
        val zone = ZoneId.systemDefault()
        val streaks = Streaks.compute(games)
        val today = LocalDate.now(zone)
        val playedToday = games.any {
            it.endedAt.atZone(zone).toLocalDate() == today && it.termination != "aborted"
        }
        val hour = usualPlayHour(games)
        MainScope().launch { schedule(streaks, playedToday, hour) }
    }

    /**
     * The hour of day the player most often finishes games (median of the
     * last 30), so nudges land when they actually play. Defaults to 7pm.
     */
    private fun usualPlayHour(games: List<GameRecord>): Int {
        val zone = ZoneId.systemDefault()
        val hours = games
            .filter { it.opponentType == "engine" && it.termination != "aborted" }
            .take(30)
            .map { it.endedAt.atZone(zone).hour }
            .sorted()
        if (hours.isEmpty()) return 19
        return hours[hours.size / 2]
    }

    private suspend fun schedule(streaks: Streaks, playedToday: Boolean, usualHour: Int) {
        // Nothing worth protecting yet: never prompt, never schedule.
        if (!(streaks.playDays >= 2 || streaks.wins >= 2)) return

        var status = LocalNotifications.status()
        if (status == LocalNotifications.Status.notDetermined) {
            suspendCancellableCoroutine { continuation ->
                LocalNotifications.requestAuthorization { if (continuation.isActive) continuation.resume(Unit) }
            }
            status = LocalNotifications.status()
        }
        if (status != LocalNotifications.Status.authorized) return

        LocalNotifications.cancel(listOf(reminderID, milestoneID))
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        fun at(day: LocalDate, hour: Int): Instant = day.atTime(hour, 0, 0).atZone(zone).toInstant()

        if (streaks.playDays >= 2) {
            val fireDate: Instant
            val title: String
            val body: String
            if (playedToday) {
                // Streak extended today: celebrate tomorrow, at the hour
                // they usually play.
                fireDate = at(today.plusDays(1), usualHour)
                title = "🔥 ${streaks.playDays}-day streak and counting"
                body = "You usually play around now — one game keeps it rolling."
            } else {
                // At risk today: remind at their usual hour; last call 9pm.
                var candidate = at(today, usualHour)
                if (candidate <= now) {
                    candidate = at(today, 21)
                }
                fireDate = candidate
                title = "🔥 ${streaks.playDays}-day streak on the line"
                body = "One quick game keeps it alive."
            }
            if (fireDate > now) {
                LocalNotifications.schedule(reminderID, title, body, fireDate)
            }
        }

        // Milestone nudge: next morning after hitting a notable number.
        val winHit = winMilestones.contains(streaks.wins)
        val dayHit = dayMilestones.contains(streaks.playDays)
        if (winHit || dayHit) {
            val fireDate = at(today.plusDays(1), 10)
            val title = if (winHit) "🏆 ${streaks.wins} wins in a row!"
                        else "🔥 ${streaks.playDays} days straight!"
            val body = "You're on a roll — a quick game keeps it going."
            LocalNotifications.schedule(milestoneID, title, body, fireDate)
        }
    }
}
