// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later
//
// chesskit-swift's value types (Square, Piece, Position, Board) as the domain vocabulary,
// backed by chesslib (Apache-2.0) for rules and move generation. All rule interactions go
// through this file, GameSession and GameReplay, so a library swap only touches this folder.

package com.hensu.chessmaster

import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.Board as LibBoard
import com.github.bhlangonijr.chesslib.Piece as LibPiece
import com.github.bhlangonijr.chesslib.Square as LibSquare

/** A board square. Raw values are laid out a1=0, b1=1, ..., h8=63 (rank-major). */
enum class Square {
    a1, b1, c1, d1, e1, f1, g1, h1,
    a2, b2, c2, d2, e2, f2, g2, h2,
    a3, b3, c3, d3, e3, f3, g3, h3,
    a4, b4, c4, d4, e4, f4, g4, h4,
    a5, b5, c5, d5, e5, f5, g5, h5,
    a6, b6, c6, d6, e6, f6, g6, h6,
    a7, b7, c7, d7, e7, f7, g7, h7,
    a8, b8, c8, d8, e8, f8, g8, h8;

    /** a = 1 ... h = 8. */
    class File(val number: Int)
    /** 1 ... 8. */
    class Rank(val value: Int)

    val rawValue: Int get() = ordinal
    val notation: String get() = name
    val file: File get() = File(ordinal % 8 + 1)
    val rank: Rank get() = Rank(ordinal / 8 + 1)

    internal val lib: LibSquare get() = LibSquare.squareAt(ordinal)

    companion object {
        /** Square(rawValue:) */
        fun fromRaw(raw: Int): Square? = entries.getOrNull(raw)
        /** Square("e4"); like chesskit, an unknown notation is a1. */
        fun of(notation: String): Square = entries.firstOrNull { it.name == notation.lowercase() } ?: a1
        internal fun from(lib: LibSquare): Square = entries[lib.ordinal]
    }
}

data class Piece(val kind: Kind, val color: Color, val square: Square) {
    enum class Color {
        white, black;
        val opposite: Color get() = if (this == white) black else white
        internal val lib: Side get() = if (this == white) Side.WHITE else Side.BLACK
        companion object {
            internal fun from(side: Side): Color = if (side == Side.WHITE) white else black
        }
    }

    enum class Kind(val notation: String) {
        pawn(""), knight("N"), bishop("B"), rook("R"), queen("Q"), king("K");
        internal val lib: PieceType
            get() = when (this) {
                pawn -> PieceType.PAWN
                knight -> PieceType.KNIGHT
                bishop -> PieceType.BISHOP
                rook -> PieceType.ROOK
                queen -> PieceType.QUEEN
                king -> PieceType.KING
            }
        companion object {
            internal fun from(type: PieceType): Kind = when (type) {
                PieceType.PAWN -> pawn
                PieceType.KNIGHT -> knight
                PieceType.BISHOP -> bishop
                PieceType.ROOK -> rook
                PieceType.QUEEN -> queen
                else -> king
            }
        }
    }
}

/** An immutable position. `Position.of(fen)` is chesskit's failable `Position(fen:)`. */
class Position internal constructor(internal val lib: LibBoard) {
    val fen: String get() = lib.fen
    val sideToMove: Piece.Color get() = Piece.Color.from(lib.sideToMove)

    /** Every piece on the board, a1 → h8. */
    val pieces: List<Piece>
        get() = Square.entries.mapNotNull { piece(it) }

    fun piece(square: Square): Piece? {
        val p = lib.getPiece(square.lib)
        if (p == LibPiece.NONE) return null
        return Piece(Piece.Kind.from(p.pieceType), Piece.Color.from(p.pieceSide), square)
    }

    companion object {
        const val standardFEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val standard: Position get() = of(standardFEN)!!

        fun of(fen: String): Position? {
            val fields = fen.trim().split(Regex("\\s+"))
            if (fields.size < 4 || fields[0].count { it == '/' } != 7) return null
            if (fields[0].count { it == 'K' } != 1 || fields[0].count { it == 'k' } != 1) return null
            return runCatching {
                val board = LibBoard()
                board.loadFromFen(fen.trim())
                Position(board)
            }.getOrNull()
        }
    }
}

/**
 * A read-only rules view of one position (random-move picking, "any legal move?" checks).
 * Live games go through GameSession, which keeps the move history repetition needs.
 */
class Board(val position: Position) {
    sealed class State {
        data object Active : State()
        data class Check(val color: Piece.Color) : State()
        data class Checkmate(val color: Piece.Color) : State()
        data class Draw(val reason: GameResult.DrawReason) : State()
    }

    /** Legal destination squares for the piece on `square` (empty when it is not that side's turn). */
    fun legalMoves(square: Square): List<Square> = ChessRules.legalTargets(position.lib, square)

    val state: State get() = ChessRules.state(position.lib)
}

/** Rule helpers shared by Board, GameSession and GameReplay. */
internal object ChessRules {
    fun copy(board: LibBoard): LibBoard = board.clone()

    fun legalTargets(board: LibBoard, square: Square): List<Square> =
        board.legalMoves().filter { it.from == square.lib }.map { Square.from(it.to) }.distinct()

    /** The legal move from → to; for a promotion, the one promoting to `promotion` (queen if null). */
    fun find(board: LibBoard, from: Square, to: Square, promotion: Piece.Kind?): Move? {
        val candidates = board.legalMoves().filter { it.from == from.lib && it.to == to.lib }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1 && candidates[0].promotion == LibPiece.NONE) return candidates[0]
        val want = (promotion ?: Piece.Kind.queen).lib
        return candidates.firstOrNull { it.promotion.pieceType == want }
    }

    fun isPromotion(board: LibBoard, from: Square, to: Square): Boolean =
        board.legalMoves().any { it.from == from.lib && it.to == to.lib && it.promotion != LibPiece.NONE }

    fun state(board: LibBoard): Board.State {
        val side = Piece.Color.from(board.sideToMove)
        if (board.isMated) return Board.State.Checkmate(side)
        if (board.isStaleMate) return Board.State.Draw(GameResult.DrawReason.stalemate)
        if (board.isInsufficientMaterial) return Board.State.Draw(GameResult.DrawReason.insufficientMaterial)
        if (board.halfMoveCounter >= 100) return Board.State.Draw(GameResult.DrawReason.fiftyMoves)
        if (board.isRepetition) return Board.State.Draw(GameResult.DrawReason.repetition)
        if (board.isKingAttacked) return Board.State.Check(side)
        return Board.State.Active
    }

    fun uci(move: Move): String {
        var s = Square.from(move.from).notation + Square.from(move.to).notation
        if (move.promotion != LibPiece.NONE) s += Piece.Kind.from(move.promotion.pieceType).notation.lowercase()
        return s
    }

    fun isCastle(board: LibBoard, move: Move): Boolean {
        val piece = board.getPiece(move.from)
        return piece.pieceType == PieceType.KING &&
            kotlin.math.abs(move.from.ordinal % 8 - move.to.ordinal % 8) == 2
    }

    fun isEnPassant(board: LibBoard, move: Move): Boolean {
        val piece = board.getPiece(move.from)
        return piece.pieceType == PieceType.PAWN &&
            move.from.ordinal % 8 != move.to.ordinal % 8 &&
            board.getPiece(move.to) == LibPiece.NONE
    }

    fun isCapture(board: LibBoard, move: Move): Boolean =
        board.getPiece(move.to) != LibPiece.NONE || isEnPassant(board, move)

    /** Standard algebraic notation for a legal move in `board` (before it is played). */
    fun san(board: LibBoard, move: Move): String {
        val piece = board.getPiece(move.from)
        val type = piece.pieceType
        val from = Square.from(move.from)
        val to = Square.from(move.to)
        val sb = StringBuilder()
        if (isCastle(board, move)) {
            sb.append(if (to.file.number > from.file.number) "O-O" else "O-O-O")
        } else {
            val capture = isCapture(board, move)
            if (type == PieceType.PAWN) {
                if (capture) sb.append(from.notation[0])
            } else {
                sb.append(Piece.Kind.from(type).notation)
                val rivals = board.legalMoves().filter {
                    it.to == move.to && it.from != move.from && board.getPiece(it.from) == piece
                }.map { Square.from(it.from) }
                if (rivals.isNotEmpty()) {
                    when {
                        rivals.none { it.file.number == from.file.number } -> sb.append(from.notation[0])
                        rivals.none { it.rank.value == from.rank.value } -> sb.append(from.notation[1])
                        else -> sb.append(from.notation)
                    }
                }
            }
            if (capture) sb.append('x')
            sb.append(to.notation)
            if (move.promotion != LibPiece.NONE) {
                sb.append('=').append(Piece.Kind.from(move.promotion.pieceType).notation)
            }
        }
        val after = copy(board)
        after.doMove(move)
        if (after.isMated) sb.append('#') else if (after.isKingAttacked) sb.append('+')
        return sb.toString()
    }

    /** The legal move whose SAN matches `token` (check marks and annotations ignored). */
    fun moveFromSAN(board: LibBoard, token: String): Move? {
        fun bare(s: String) = s.trimEnd('+', '#', '!', '?').replace("0-0-0", "O-O-O").replace("0-0", "O-O")
        val want = bare(token)
        return board.legalMoves().firstOrNull { bare(san(board, it)) == want }
    }

    /**
     * Moves the stable-identity pieces the way `move` (about to be played on `board`) does:
     * captured piece removed (en passant included), rook follows a castling king, pawn promotes.
     */
    fun apply(board: LibBoard, move: Move, pieces: MutableList<BoardPiece>) {
        val from = Square.from(move.from)
        val to = Square.from(move.to)
        if (isEnPassant(board, move)) {
            val captured = Square.fromRaw((from.rank.value - 1) * 8 + (to.file.number - 1))
            pieces.removeAll { it.square == captured }
        } else if (board.getPiece(move.to) != LibPiece.NONE) {
            pieces.removeAll { it.square == to }
        }
        fun slide(a: Square, b: Square) {
            val i = pieces.indexOfFirst { it.square == a }
            if (i >= 0) pieces[i] = pieces[i].copy(square = b)
        }
        if (isCastle(board, move)) {
            val rank = from.rank.value - 1
            val kingSide = to.file.number > from.file.number
            slide(from, to)
            slide(Square.fromRaw(rank * 8 + if (kingSide) 7 else 0)!!, Square.fromRaw(rank * 8 + if (kingSide) 5 else 3)!!)
        } else {
            slide(from, to)
        }
        if (move.promotion != LibPiece.NONE) {
            val i = pieces.indexOfFirst { it.square == to }
            if (i >= 0) pieces[i] = pieces[i].copy(kind = Piece.Kind.from(move.promotion.pieceType))
        }
    }
}
