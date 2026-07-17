// Chessmaster — GPL-3.0-or-later
import EngineKit
import Foundation
import PersistenceKit
import RatingKit

/// Picks the Stockfish level for "Auto" mode: the level whose calibrated
/// rating sits closest to the player's, corrected by their recent streak so
/// a rough patch eases off *immediately* instead of waiting for the rating
/// to catch up. The goal is games the player can win about half the time —
/// that's where improvement happens.
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

    static func recommend(rating: Glicko2Rating, recentGames: [GameRecord]) -> Recommendation {
        var level = baseLevel(rating: rating)

        let losses = consecutiveLosses(in: recentGames)
        switch streak(in: recentGames) {
        case .losing: level -= 1   // confidence first: win one, then climb
        case .winning: level += 1  // keep it challenging
        case .none: break
        }
        level = min(10, max(1, level))

        // Below the floor, keep easing off: every further loss makes the
        // engine blunder more (on top of level 1's built-in 45% handicap),
        // capped at about two of every three moves being random.
        let blunder = level == 1 && losses >= streakLength
            ? min(0.65, 0.45 + 0.07 * Double(losses - streakLength + 1))
            : 0
        return Recommendation(level: level, blunderProbability: blunder)
    }

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

    /// Losses in a row (most recent first) against the engine; a win or
    /// draw ends the run. Drives how blunder-prone the easing-off gets.
    private static func consecutiveLosses(in recentGames: [GameRecord]) -> Int {
        recentGames
            .filter { $0.opponentType == "engine" && $0.termination != "aborted" }
            .prefix { won(game: $0) == false }
            .count
    }

    private enum Streak { case winning, losing, none }

    /// The last `streakLength` finished engine games, most recent first.
    private static func streak(in recentGames: [GameRecord]) -> Streak {
        let outcomes = recentGames
            .filter { $0.opponentType == "engine" && $0.termination != "aborted" }
            .prefix(streakLength)
            .map { won(game: $0) }
        guard outcomes.count == streakLength else { return .none }
        if outcomes.allSatisfy({ $0 == true }) { return .winning }
        if outcomes.allSatisfy({ $0 == false }) { return .losing }
        return .none
    }

    /// true = win, false = loss, nil = draw.
    private static func won(game: GameRecord) -> Bool? {
        switch game.result {
        case "white_win": game.playerColor == "white" ? true : false
        case "black_win": game.playerColor == "black" ? true : false
        default: nil
        }
    }
}
