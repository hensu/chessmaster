// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.delay

sealed class EngineOpponentError : Exception() {
    data object NnueMissing : EngineOpponentError()
    data class BadMove(val uci: String) : EngineOpponentError()
}

/** OpponentProvider backed by the embedded Stockfish at a ladder level. */
class EngineOpponent(level: Int, blunderProbability: Double = 0.0) : OpponentProvider {
    val level: StrengthLevel = StrengthLevel.level(level)
    /**
     * Chance per move of playing a random legal move instead of the
     * engine's choice — how "Auto" gets weaker than level 1.
     */
    // The ladder's own handicap keeps low levels at their advertised
    // strength; adaptive easing can push further, never weaker.
    val blunderProbability: Double = maxOf(this.level.blunderProbability, blunderProbability)

    override suspend fun nextMove(fen: String, remainingTime: Duration?): OpponentMove {
        // A sub-100ms reply feels jarring; keep a small minimum think time.
        val minimumThink = System.nanoTime() + 300_000_000L
        suspend fun sleepUntilMinimumThink() {
            val leftMs = (minimumThink - System.nanoTime()) / 1_000_000L
            if (leftMs > 0) delay(leftMs)
        }

        // Rare genuine howler at the weakest levels: real newcomers do
        // occasionally hang a piece outright.
        if (Random.nextDouble() < blunderProbability * 0.12) {
            val move = randomMove(fen)
            if (move != null) {
                sleepUntilMinimumThink()
                return move
            }
        }

        val big = StockfishNets.big
        val small = StockfishNets.small
        if (big == null || small == null) throw EngineOpponentError.NnueMissing

        val engine = UCIEngine.shared
        engine.startIfNeeded(evalFileBig = big, evalFileSmall = small)
        // Options are folded into each search so the whole move transaction
        // stays atomic even while background analysis shares the engine.
        val options = level.uciOptions

        val chosenUCI: String
        if (blunderProbability > 0) {
            // Consistently mediocre beats alternating perfect/terrible:
            // sample the engine's own candidates, temperature-scaled so
            // weak levels prefer plausibly-bad moves (50-200cp drops).
            val candidates = engine.searchCandidates(
                fen = fen, movetimeMs = level.movetimeMs, depth = level.depth, count = 6,
                options = options,
            )
            if (candidates.isEmpty()) {
                val result = engine.search(
                    fen = fen, movetimeMs = level.movetimeMs, depth = level.depth, options = options,
                )
                chosenUCI = result.bestMoveUCI
            } else {
                val index = WeakMovePicker.pick(
                    candidates,
                    temperatureCp = blunderProbability * 500,
                    random = Random.nextDouble(),
                )
                chosenUCI = candidates[index].moveUCI
            }
        } else {
            val result = engine.search(
                fen = fen, movetimeMs = level.movetimeMs, depth = level.depth, options = options,
            )
            chosenUCI = result.bestMoveUCI
        }
        sleepUntilMinimumThink()

        return OpponentMove.of(chosenUCI) ?: throw EngineOpponentError.BadMove(chosenUCI)
    }

    /** A uniformly random legal move for the side to move. */
    private fun randomMove(fen: String): OpponentMove? {
        val position = Position.of(fen) ?: return null
        val board = Board(position)
        val moves = position.pieces
            .filter { it.color == position.sideToMove }
            .flatMap { piece -> board.legalMoves(piece.square).map { piece to it } }
        val (piece, target) = moves.randomOrNull() ?: return null
        val promotes = piece.kind == Piece.Kind.pawn && (target.rank.value == 1 || target.rank.value == 8)
        return OpponentMove(from = piece.square, to = target, promotion = if (promotes) Piece.Kind.queen else null)
    }
}
