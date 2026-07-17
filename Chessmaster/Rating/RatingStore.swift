// Chessmaster — GPL-3.0-or-later
import Foundation
import ChessDomain
import RatingKit
import PersistenceKit

/// The user's current Glicko-2 rating per time-control category, backed by
/// the rating_history table (latest row per category wins).
@Observable @MainActor
final class RatingStore {
    private(set) var ratings: [TimeControl.Category: Glicko2Rating] = [:]

    private let history: RatingHistoryRepository

    init(history: RatingHistoryRepository) {
        self.history = history
        if let latest = try? history.latestPerCategory() {
            ratings = Dictionary(uniqueKeysWithValues: latest.compactMap { key, record in
                TimeControl.Category(rawValue: key).map {
                    ($0, Glicko2Rating(rating: record.rating, deviation: record.deviation, volatility: record.volatility))
                }
            })
        }
    }

    func rating(for category: TimeControl.Category) -> Glicko2Rating {
        ratings[category] ?? Glicko2Rating()
    }

    /// Applies one rated game to the in-memory rating and returns the
    /// before/after pair. Call `recordHistory` AFTER the game row is saved —
    /// the history row has a foreign key to it.
    @discardableResult
    func applyGame(
        category: TimeControl.Category,
        opponentRating: Double,
        score: Double
    ) -> (before: Glicko2Rating, after: Glicko2Rating) {
        let before = rating(for: category)
        let after = Glicko2Calculator.update(
            player: before,
            results: [Glicko2Result(
                opponent: Glicko2Rating(rating: opponentRating, deviation: 60, volatility: 0.06),
                score: score
            )]
        )
        ratings[category] = after
        return (before, after)
    }

    /// Persists the rating-history row for an already-saved game.
    func recordHistory(gameID: String?, category: TimeControl.Category) {
        let current = rating(for: category)
        try? history.append(RatingHistoryRecord(
            gameID: gameID,
            at: .now,
            category: category.rawValue,
            rating: current.rating,
            deviation: current.deviation,
            volatility: current.volatility
        ))
    }
}
