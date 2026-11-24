// Chessmaster — GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Picks the Stockfish level for "Auto" mode. The level is **sticky and
 * per-time-control**: it's seeded once from the player's rating, then only
 * moves after a decisive run — +1 after `streakLength` wins in a row, −1
 * after that many losses — and holds otherwise. That gives steady, legible
 * progression (you can sit at Level 5 for 10+0 and Level 1 for 1+0) instead
 * of bouncing every game as the rating wobbles across a level boundary.
 *
 * Movement is event-driven: `recordResult` is called once when a rated Auto
 * game finishes. `level(category, …)` is a pure read (it seeds on first use), so the
 * displayed level never changes just from being re-read.
 */
object AdaptiveLevel {
    const val streakLength = 3

    data class Recommendation(
        var level: Int,
        /**
         * Probability the engine plays a random legal move. Level 1 is the
         * ladder floor but Stockfish's floor is still too strong for a true
         * beginner — a player still losing there gets an increasingly
         * blunder-prone opponent until they start winning again.
         */
        var blunderProbability: Double,
    )

    // MARK: - Read (pure)

    /** The sticky Auto level for a category, plus level-1 blunder easing. */
    fun level(
        category: TimeControl.Category, rating: Glicko2Rating, recentGames: List<GameRecord>,
    ): Recommendation {
        val level = storedLevel(category, rating)

        // Below the floor, keep easing off: every further loss makes the
        // engine blunder more (on top of level 1's built-in 45% handicap),
        // capped at about two of every three moves being random.
        val losses = consecutiveLosses(recentGames, category)
        val blunder = if (level == 1 && losses >= streakLength)
            min(0.65, 0.45 + 0.07 * (losses - streakLength + 1).toDouble())
        else 0.0
        return Recommendation(level = level, blunderProbability = blunder)
    }

    // MARK: - Write (once per finished game)

    /**
     * Record a finished rated Auto game and adjust that category's level:
     * +1 after `streakLength` wins in a row, −1 after that many losses, then
     * the streak resets and must rebuild (so a single loss after a hot streak
     * never snaps you back down). A draw resets the streak.
     */
    fun recordResult(
        category: TimeControl.Category, didWin: Boolean?, seedRating: Glicko2Rating,
    ) {
        var level = storedLevel(category, seedRating)
        var streak = Defaults.standard.integer(streakKey(category))
        when (didWin) {
            true -> {
                streak = max(streak, 0) + 1
                if (streak >= streakLength) { level = min(10, level + 1); streak = 0 }
            }
            false -> {
                streak = min(streak, 0) - 1
                if (streak <= -streakLength) { level = max(1, level - 1); streak = 0 }
            }
            null -> streak = 0
        }
        Defaults.standard.set(levelKey(category), level)
        Defaults.standard.set(streakKey(category), streak)
    }

    // MARK: - Storage

    private fun levelKey(c: TimeControl.Category): String = "autoLevel.${c.rawValue}"
    private fun streakKey(c: TimeControl.Category): String = "autoStreak.${c.rawValue}"

    /**
     * The persisted level for a category, seeding (and storing) from rating
     * on first use so the read is stable thereafter.
     */
    private fun storedLevel(category: TimeControl.Category, seedRating: Glicko2Rating): Int {
        val stored = Defaults.standard.integer(levelKey(category))
        if (stored >= 1) return stored
        val seed = baseLevel(seedRating)
        Defaults.standard.set(levelKey(category), seed)
        return seed
    }

    // MARK: - Seeding

    /**
     * Closest level by rating. Players without an established rating
     * (fresh install, deviation still near the 350 default) start from
     * their onboarding self-assessment, or gently at level 3 — never at
     * the level the 1500 default rating would imply.
     */
    private fun baseLevel(rating: Glicko2Rating): Int {
        if (rating.deviation >= 250) {
            val selfRating = Defaults.standard.integer("onboarding.selfRating")
            return if (selfRating > 0) closestLevel(selfRating.toDouble()) else 3
        }
        return closestLevel(rating.rating)
    }

    private fun closestLevel(rating: Double): Int =
        StrengthLevel.all.minByOrNull { abs(it.ratingEstimate.toDouble() - rating) }?.level ?: 3

    /**
     * Losses in a row (most recent first) in this category against the
     * engine; a win or draw ends the run. Drives the level-1 easing-off.
     */
    private fun consecutiveLosses(recentGames: List<GameRecord>, category: TimeControl.Category): Int =
        recentGames
            .filter {
                it.opponentType == "engine" && it.termination != "aborted" &&
                    it.ratingCategory == category.rawValue
            }
            .takeWhile { won(it) == false }
            .size

    /**
     * true = win, false = loss, nil = draw. Note `GameRecord.result` is
     * stored camelCase locally ("whiteWin"/"blackWin") — the server's
     * snake_case is a separate on-the-wire format.
     */
    private fun won(game: GameRecord): Boolean? =
        when (game.result) {
            "whiteWin" -> game.playerColor == "white"
            "blackWin" -> game.playerColor == "black"
            else -> null
        }
}
