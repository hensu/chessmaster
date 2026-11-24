// EngineKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

/**
 * The 10-level difficulty ladder (lichess offers 8; we extend to 10).
 * Low levels use Stockfish's "Skill Level" handicap (adds bounded move
 * randomness); mid/high levels use the calibrated UCI_Elo cap.
 * `ratingEstimate` is used as the opponent's Glicko-2 rating.
 */
data class StrengthLevel(
    val level: Int,
    val uciOptions: Map<String, String>,
    val movetimeMs: Int,
    val depth: Int?,
    val ratingEstimate: Int,
    /**
     * Chance per move of playing a random legal move instead of the
     * engine's choice. Stockfish's natural floor is ~1200 human Elo even
     * at Skill 0 / depth 1 (measured: 4% player score across all games),
     * so the low rungs blunder deliberately to actually PLAY at their
     * advertised ratingEstimate. Tune from win-rate data.
     */
    val blunderProbability: Double,
) {
    val id: Int get() = level

    /** Player-facing name for the level (shown in pickers and game titles). */
    val displayName: String
        get() = when (level) {
            1 -> "Newcomer"
            2 -> "Beginner"
            3 -> "Casual"
            4 -> "Improving"
            5 -> "Club player"
            6 -> "Strong club"
            7 -> "Expert"
            8 -> "Master"
            9 -> "Grandmaster"
            else -> "Maximum"
        }

    companion object {
        val all: List<StrengthLevel> = listOf(
            skill(1, skill = 0, depth = 1, movetimeMs = 50, rating = 450, blunder = 0.45),
            skill(2, skill = 2, depth = 3, movetimeMs = 80, rating = 700, blunder = 0.30),
            skill(3, skill = 4, depth = 5, movetimeMs = 100, rating = 900, blunder = 0.18),
            skill(4, skill = 6, depth = 8, movetimeMs = 200, rating = 1100, blunder = 0.10),
            skill(5, skill = 9, depth = 10, movetimeMs = 200, rating = 1350, blunder = 0.05),
            elo(6, elo = 1500, movetimeMs = 400, rating = 1500),
            elo(7, elo = 1800, movetimeMs = 400, rating = 1800),
            elo(8, elo = 2100, movetimeMs = 400, rating = 2100),
            elo(9, elo = 2500, movetimeMs = 800, rating = 2500),
            StrengthLevel(
                level = 10,
                uciOptions = mapOf("Skill Level" to "20", "UCI_LimitStrength" to "false"),
                movetimeMs = 1000,
                depth = null,
                ratingEstimate = 3000,
                blunderProbability = 0.0,
            ),
        )

        fun level(n: Int): StrengthLevel = all[maxOf(1, minOf(10, n)) - 1]

        private fun skill(
            level: Int, skill: Int, depth: Int, movetimeMs: Int, rating: Int, blunder: Double,
        ): StrengthLevel = StrengthLevel(
            level = level,
            uciOptions = mapOf("Skill Level" to "$skill", "UCI_LimitStrength" to "false"),
            movetimeMs = movetimeMs,
            depth = depth,
            ratingEstimate = rating,
            blunderProbability = blunder,
        )

        private fun elo(level: Int, elo: Int, movetimeMs: Int, rating: Int): StrengthLevel = StrengthLevel(
            level = level,
            uciOptions = mapOf(
                "Skill Level" to "20",
                "UCI_LimitStrength" to "true",
                "UCI_Elo" to "$elo",
            ),
            movetimeMs = movetimeMs,
            depth = null,
            ratingEstimate = rating,
            blunderProbability = 0.0,
        )
    }
}
