// Chessmaster — GPL-3.0-or-later
import ChessDomain
import EngineKit
import Foundation
import PersistenceKit
import RatingKit

/// Picks the Stockfish level for "Auto" mode. The level is **sticky and
/// per-time-control**: it's seeded once from the player's rating, then only
/// moves after a decisive run — +1 after `streakLength` wins in a row, −1
/// after that many losses — and holds otherwise. That gives steady, legible
/// progression (you can sit at Level 5 for 10+0 and Level 1 for 1+0) instead
/// of bouncing every game as the rating wobbles across a level boundary.
///
/// Movement is event-driven: `recordResult` is called once when a rated Auto
/// game finishes. `level(for:)` is a pure read (it seeds on first use), so the
/// displayed level never changes just from being re-read.
enum AdaptiveLevel {
    static let streakLength = 3

    struct Recommendation: Equatable {
        var level: Int
        /// Probability the engine plays a random legal move. Level 1 is the
        /// ladder floor but Stockfish's floor is still too strong for a true
        /// beginner — a player still losing there gets an increasingly
        /// blunder-prone opponent until they start winning again.
        var blunderProbability: Double
    }

    // MARK: - Read (pure)

    /// The sticky Auto level for a category, plus level-1 blunder easing.
    static func level(
        for category: TimeControl.Category, rating: Glicko2Rating, recentGames: [GameRecord]
    ) -> Recommendation {
        let level = storedLevel(for: category, seedRating: rating)

        // Below the floor, keep easing off: every further loss makes the
        // engine blunder more (on top of level 1's built-in 45% handicap),
        // capped at about two of every three moves being random.
        let losses = consecutiveLosses(in: recentGames, category: category)
        let blunder = level == 1 && losses >= streakLength
            ? min(0.65, 0.45 + 0.07 * Double(losses - streakLength + 1))
            : 0
        return Recommendation(level: level, blunderProbability: blunder)
    }

    // MARK: - Write (once per finished game)

    /// Record a finished rated Auto game and adjust that category's level:
    /// +1 after `streakLength` wins in a row, −1 after that many losses, then
    /// the streak resets and must rebuild (so a single loss after a hot streak
    /// never snaps you back down). A draw resets the streak.
    static func recordResult(
        for category: TimeControl.Category, didWin: Bool?, seedRating: Glicko2Rating
    ) {
        var level = storedLevel(for: category, seedRating: seedRating)
        var streak = UserDefaults.standard.integer(forKey: streakKey(category))
        switch didWin {
        case .some(true):
            streak = max(streak, 0) + 1
            if streak >= streakLength { level = min(10, level + 1); streak = 0 }
        case .some(false):
            streak = min(streak, 0) - 1
            if streak <= -streakLength { level = max(1, level - 1); streak = 0 }
        case .none:
            streak = 0
        }
        UserDefaults.standard.set(level, forKey: levelKey(category))
        UserDefaults.standard.set(streak, forKey: streakKey(category))
    }

    // MARK: - Storage

    private static func levelKey(_ c: TimeControl.Category) -> String { "autoLevel.\(c.rawValue)" }
    private static func streakKey(_ c: TimeControl.Category) -> String { "autoStreak.\(c.rawValue)" }

    /// The persisted level for a category, seeding (and storing) from rating
    /// on first use so the read is stable thereafter.
    private static func storedLevel(for category: TimeControl.Category, seedRating: Glicko2Rating) -> Int {
        let stored = UserDefaults.standard.integer(forKey: levelKey(category))
        if stored >= 1 { return stored }
        let seed = baseLevel(rating: seedRating)
        UserDefaults.standard.set(seed, forKey: levelKey(category))
        return seed
    }

    // MARK: - Seeding

    /// Closest level by rating. Players without an established rating
    /// (fresh install, deviation still near the 350 default) start from
    /// their onboarding self-assessment, or gently at level 3 — never at
    /// the level the 1500 default rating would imply.
    private static func baseLevel(rating: Glicko2Rating) -> Int {
        guard rating.deviation < 250 else {
            let selfRating = UserDefaults.standard.integer(forKey: "onboarding.selfRating")
            return selfRating > 0 ? closestLevel(to: Double(selfRating)) : 3
        }
        return closestLevel(to: rating.rating)
    }

    private static func closestLevel(to rating: Double) -> Int {
        StrengthLevel.all.min {
            abs(Double($0.ratingEstimate) - rating) < abs(Double($1.ratingEstimate) - rating)
        }?.level ?? 3
    }

    /// Losses in a row (most recent first) in this category against the
    /// engine; a win or draw ends the run. Drives the level-1 easing-off.
    private static func consecutiveLosses(in recentGames: [GameRecord], category: TimeControl.Category) -> Int {
        recentGames
            .filter {
                $0.opponentType == "engine" && $0.termination != "aborted"
                    && $0.ratingCategory == category.rawValue
            }
            .prefix { won(game: $0) == false }
            .count
    }

    /// true = win, false = loss, nil = draw. Note `GameRecord.result` is
    /// stored camelCase locally ("whiteWin"/"blackWin") — the server's
    /// snake_case is a separate on-the-wire format.
    private static func won(game: GameRecord) -> Bool? {
        switch game.result {
        case "whiteWin": game.playerColor == "white"
        case "blackWin": game.playerColor == "black"
        default: nil
        }
    }
}
