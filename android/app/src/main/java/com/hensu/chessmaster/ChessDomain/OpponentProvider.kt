// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.time.Duration

/** A move chosen by an opponent, in coordinate form. */
data class OpponentMove(var from: Square, var to: Square, var promotion: Piece.Kind? = null) {
    companion object {
        /** Parse a UCI move string like "e2e4" or "e7e8q". */
        fun of(uci: String): OpponentMove? {
            if (uci.length != 4 && uci.length != 5) return null
            val from = Square.of(uci.substring(0, 2))
            val to = Square.of(uci.substring(2, 4))
            var promotion: Piece.Kind? = null
            if (uci.length == 5) {
                promotion = when (uci.last()) {
                    'q' -> Piece.Kind.queen
                    'r' -> Piece.Kind.rook
                    'b' -> Piece.Kind.bishop
                    'n' -> Piece.Kind.knight
                    else -> return null
                }
            }
            return OpponentMove(from, to, promotion)
        }
    }
}

/**
 * Supplies the opponent's moves. V1: EngineOpponent (Stockfish).
 * V2: FriendOpponent (live multiplayer) implements the same interface.
 */
interface OpponentProvider {
    /**
     * Return the opponent's move for the current position.
     * `fen`: FEN of the position to move in.
     * `remainingTime`: opponent clock remaining, if timed (lets engines budget).
     */
    suspend fun nextMove(fen: String, remainingTime: Duration?): OpponentMove
}
