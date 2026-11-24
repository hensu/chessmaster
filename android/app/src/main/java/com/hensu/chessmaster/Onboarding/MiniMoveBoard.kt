// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

private const val miniMoveBoardFiles = "abcdefgh"
private val miniMoveBoardBackRank: List<Piece.Kind> = listOf(
    Piece.Kind.rook, Piece.Kind.knight, Piece.Kind.bishop, Piece.Kind.queen,
    Piece.Kind.king, Piece.Kind.bishop, Piece.Kind.knight, Piece.Kind.rook,
)

/**
 * A tiny static board showing one move from the starting position, with the
 * from/to squares highlighted — so "e4" or "Nf3" reads as a picture.
 */
@Composable
fun MiniMoveBoard(from: String, to: String, square: Float = 10f, modifier: Modifier = Modifier) {
    val theme = BoardTheme.brown
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .clearAndSetSemantics { }
    ) {
        for (rank in 8 downTo 1) {
            Row {
                for (file in 0 until 8) {
                    val name = "${miniMoveBoardFiles[file]}$rank"
                    val light = (file + rank) % 2 == 0
                    Box(Modifier.size(square.dp).background(if (light) theme.lightSquare else theme.darkSquare)) {
                        if (name == from || name == to) {
                            Box(Modifier.fillMaxSize().background(theme.lastMoveHighlight))
                        }
                        val piece = miniMoveBoardPiece(name, from, to)
                        if (piece != null) {
                            PieceImage(piece.first, piece.second, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** The starting position with this one move played. */
private fun miniMoveBoardPiece(name: String, from: String, to: String): Pair<Piece.Kind, Piece.Color>? {
    if (name == from) return null
    val source = if (name == to) from else name
    val file = miniMoveBoardFiles.indexOf(source[0])
    val rank = source[1].digitToIntOrNull()
    if (file < 0 || rank == null) return null
    return when (rank) {
        1 -> Pair(miniMoveBoardBackRank[file], Piece.Color.white)
        2 -> Pair(Piece.Kind.pawn, Piece.Color.white)
        7 -> Pair(Piece.Kind.pawn, Piece.Color.black)
        8 -> Pair(miniMoveBoardBackRank[file], Piece.Color.black)
        else -> null
    }
}
