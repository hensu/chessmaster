// Chessmaster — GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant

/**
 * The user's current Glicko-2 rating per time-control category, backed by
 * the rating_history table (latest row per category wins).
 */
class RatingStore(private val history: RatingHistoryRepository) {
    var ratings: Map<TimeControl.Category, Glicko2Rating> by mutableStateOf(emptyMap())
        private set

    init {
        val latest = runCatching { history.latestPerCategory() }.getOrNull()
        if (latest != null) {
            ratings = latest.mapNotNull { (key, record) ->
                TimeControl.Category.fromRaw(key)?.let {
                    it to Glicko2Rating(record.rating, record.deviation, record.volatility)
                }
            }.toMap()
        }
    }

    fun rating(category: TimeControl.Category): Glicko2Rating =
        ratings[category] ?: Glicko2Rating()

    /**
     * Applies one rated game to the in-memory rating and returns the
     * before/after pair. Call `recordHistory` AFTER the game row is saved —
     * the history row has a foreign key to it.
     */
    fun applyGame(
        category: TimeControl.Category,
        opponentRating: Double,
        score: Double,
    ): Pair<Glicko2Rating, Glicko2Rating> {
        val before = rating(category)
        val after = Glicko2Calculator.update(
            before,
            listOf(
                Glicko2Result(
                    Glicko2Rating(opponentRating, 60.0, 0.06),
                    score,
                )
            ),
        )
        ratings = ratings + (category to after)
        return before to after
    }

    /** Persists the rating-history row for an already-saved game. */
    fun recordHistory(gameID: String?, category: TimeControl.Category) {
        val current = rating(category)
        runCatching {
            history.append(
                RatingHistoryRecord(
                    gameID = gameID,
                    at = Instant.now(),
                    category = category.rawValue,
                    rating = current.rating,
                    deviation = current.deviation,
                    volatility = current.volatility,
                )
            )
        }
    }
}
