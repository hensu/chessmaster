// Chessmaster — GPL-3.0-or-later

package com.hensu.chessmaster

import java.time.Instant
import java.time.ZoneId

/** Home-screen streaks: consecutive days played, consecutive wins. */
data class Streaks(val playDays: Int, val wins: Int) {
    companion object {
        fun compute(
            games: List<GameRecord>,
            calendar: ZoneId = ZoneId.systemDefault(),
            now: Instant = Instant.now(),
        ): Streaks {
            val finished = games.filter { it.opponentType == "engine" && it.termination != "aborted" }

            // Day streak: walk back from today (yesterday counts as alive —
            // a streak shouldn't die while today is still playable).
            val playedDays = finished.map { it.endedAt.atZone(calendar).toLocalDate() }.toSet()
            var day = now.atZone(calendar).toLocalDate()
            if (!playedDays.contains(day)) {
                day = day.minusDays(1)
            }
            var playDays = 0
            while (playedDays.contains(day)) {
                playDays += 1
                day = day.minusDays(1)
            }

            // Win streak: most recent games first, count until the first non-win.
            var wins = 0
            for (game in finished.sortedByDescending { it.endedAt }) {
                val won = (game.result == "whiteWin" && game.playerColor == "white") ||
                    (game.result == "blackWin" && game.playerColor == "black")
                if (won) wins += 1 else break
            }

            return Streaks(playDays = playDays, wins = wins)
        }
    }
}
