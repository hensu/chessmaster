// Chessmaster — GPL-3.0-or-later
import Foundation
import PersistenceKit

/// Home-screen streaks: consecutive days played, consecutive wins.
struct Streaks {
    let playDays: Int
    let wins: Int

    static func compute(games: [GameRecord], calendar: Calendar = .current,
                        now: Date = Date()) -> Streaks {
        let finished = games.filter { $0.opponentType == "engine" && $0.termination != "aborted" }

        // Day streak: walk back from today (yesterday counts as alive —
        // a streak shouldn't die while today is still playable).
        let playedDays = Set(finished.map { calendar.startOfDay(for: $0.endedAt) })
        var day = calendar.startOfDay(for: now)
        if !playedDays.contains(day) {
            day = calendar.date(byAdding: .day, value: -1, to: day)!
        }
        var playDays = 0
        while playedDays.contains(day) {
            playDays += 1
            day = calendar.date(byAdding: .day, value: -1, to: day)!
        }

        // Win streak: most recent games first, count until the first non-win.
        var wins = 0
        for game in finished.sorted(by: { $0.endedAt > $1.endedAt }) {
            let won = (game.result == "white_win" && game.playerColor == "white")
                || (game.result == "black_win" && game.playerColor == "black")
            if won { wins += 1 } else { break }
        }

        return Streaks(playDays: playDays, wins: wins)
    }
}
