// AnalysisKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.math.exp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MoveClassification(val rawValue: String) {
    @SerialName("good") good("good"),
    @SerialName("inaccuracy") inaccuracy("inaccuracy"),   // ?!
    @SerialName("mistake") mistake("mistake"),            // ?
    @SerialName("blunder") blunder("blunder");            // ??

    val glyph: String
        get() = when (this) {
            good -> ""
            inaccuracy -> "?!"
            mistake -> "?"
            blunder -> "??"
        }

    companion object {
        fun fromRaw(raw: String): MoveClassification? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Lichess's classification model: centipawns are mapped to win probability,
 * and a move is judged by how much win probability the mover threw away.
 */
object MoveClassifier {
    /**
     * Win probability (0-100) for the given side, from a white-POV
     * centipawn eval. Constant is lichess's fitted value.
     */
    fun winPercent(cpWhite: Int, `for`: Boolean): Double {
        val moverIsWhite = `for`
        val cp = (if (moverIsWhite) cpWhite else -cpWhite).toDouble()
        val clamped = minOf(maxOf(cp, -1000.0), 1000.0)
        return 50 + 50 * (2 / (1 + exp(-0.00368208 * clamped)) - 1)
    }

    /**
     * Thresholds on win-probability drop. Tighter than lichess's 10/20/30:
     * at club level games are usually lost through repeated 6-9% bleeds
     * that a 10% floor never flags, leaving "nothing to review" in games
     * the player clearly lost on merit.
     */
    fun classify(winPercentDrop: Double): MoveClassification = when {
        winPercentDrop < 6 -> MoveClassification.good
        winPercentDrop < 14 -> MoveClassification.inaccuracy
        winPercentDrop < 25 -> MoveClassification.mistake
        else -> MoveClassification.blunder
    }

    /**
     * A move shouldn't be flagged when the game was already decided —
     * "you were dead lost anyway" flags are noise ("inevitable moves"),
     * and mopping-up moves in won positions don't need lessons either.
     */
    fun isPositionDecided(moverWinPercentBefore: Double): Boolean =
        moverWinPercentBefore < 10 || moverWinPercentBefore > 95

    /** Lichess's per-move accuracy curve (0-100) from win-probability drop. */
    fun accuracy(winPercentDrop: Double): Double =
        minOf(100.0, maxOf(0.0, 103.1668 * exp(-0.04354 * maxOf(0.0, winPercentDrop)) - 3.1669))
}
