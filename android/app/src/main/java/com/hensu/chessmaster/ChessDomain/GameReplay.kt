// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

/**
 * A parsed game flattened to its main line — the input shape for analysis
 * and the replay UI.
 */
class GameReplay private constructor(val startFEN: String, val plies: List<Ply>) {
    data class Ply(
        /** 1-based ply number. */
        val ply: Int,
        val san: String,
        val uci: String,
        val mover: Piece.Color,
        /** FEN of the position after this move. */
        val fenAfter: String,
    ) {
        val id: Int get() = ply
        val moveNumber: Int get() = (ply + 1) / 2
    }

    /** FEN before the given 1-based ply. */
    fun fenBefore(ply: Int): String = if (ply <= 1) startFEN else plies[ply - 2].fenAfter

    /**
     * Pieces at a ply with identity that is stable ACROSS plies: ids are
     * assigned once from the starting position and follow each piece
     * through the moves. Rendering these lets the replay UI slide the
     * moved piece, instead of refreshing every piece the way FEN-derived
     * lists do (piece order differs between positions).
     */
    fun boardPieces(atPly: Int): List<BoardPiece> {
        val position = Position.of(startFEN) ?: return emptyList()
        val board = ChessRules.copy(position.lib)
        val pieces = position.pieces.mapIndexed { index, piece ->
            BoardPiece(index, piece.kind, piece.color, piece.square)
        }.toMutableList()
        for (ply in plies.take(maxOf(0, minOf(atPly, plies.size)))) {
            val uciMove = OpponentMove.of(ply.uci) ?: break
            val move = ChessRules.find(board, uciMove.from, uciMove.to, uciMove.promotion) ?: break
            ChessRules.apply(board, move, pieces)
            board.doMove(move)
        }
        return pieces
    }

    companion object {
        /**
         * Parses PGN and walks the main variation (the failable `GameReplay(pgn:)`). Reads the
         * games this app writes and the ones the iOS app synced: headers, `{comments}`,
         * `(variations)`, `$nags` and move numbers are skipped.
         */
        fun of(pgn: String): GameReplay? {
            var fen: String? = null
            val body = StringBuilder()
            for (line in pgn.lines()) {
                val t = line.trim()
                if (t.startsWith("[") && t.endsWith("]")) {
                    val m = Regex("""^\[(\w+)\s+"(.*)"\]$""").find(t)
                    if (m != null && m.groupValues[1] == "FEN") fen = m.groupValues[2]
                } else {
                    body.append(t).append(' ')
                }
            }
            val position = (if (fen != null) Position.of(fen) else Position.standard) ?: return null
            val board = ChessRules.copy(position.lib)

            // Drop comments and (nested) variations.
            val text = StringBuilder()
            var braces = 0
            var parens = 0
            for (c in body) {
                when {
                    c == '{' -> braces++
                    c == '}' -> { if (braces > 0) braces--; text.append(' ') }
                    braces > 0 -> {}
                    c == '(' -> parens++
                    c == ')' -> { if (parens > 0) parens--; text.append(' ') }
                    parens > 0 -> {}
                    else -> text.append(c)
                }
            }

            val plies = mutableListOf<Ply>()
            for (raw in text.split(Regex("\\s+"))) {
                var token = raw.replace(Regex("""^\d+\.+"""), "")
                if (token.isEmpty() || token.startsWith("$")) continue
                if (token in listOf("1-0", "0-1", "1/2-1/2", "*")) break
                token = token.trimEnd('!', '?')
                val move = ChessRules.moveFromSAN(board, token) ?: return null
                val mover = Piece.Color.from(board.sideToMove)
                val san = ChessRules.san(board, move)
                board.doMove(move)
                plies.add(Ply(plies.size + 1, san, ChessRules.uci(move), mover, board.fen))
            }
            return GameReplay(position.fen, plies)
        }
    }
}
