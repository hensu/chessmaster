// BoardUI — Chessmaster
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

val Piece.Kind.assetLetter: String
    get() = when (this) {
        Piece.Kind.king -> "K"
        Piece.Kind.queen -> "Q"
        Piece.Kind.rook -> "R"
        Piece.Kind.bishop -> "B"
        Piece.Kind.knight -> "N"
        Piece.Kind.pawn -> "P"
    }

/** cburnett piece image from `res/drawable-nodpi`, e.g. `R.drawable.piece_wk`. */
fun pieceImageRes(kind: Piece.Kind, color: Piece.Color): Int =
    if (color == Piece.Color.white) {
        when (kind) {
            Piece.Kind.king -> R.drawable.piece_wk
            Piece.Kind.queen -> R.drawable.piece_wq
            Piece.Kind.rook -> R.drawable.piece_wr
            Piece.Kind.bishop -> R.drawable.piece_wb
            Piece.Kind.knight -> R.drawable.piece_wn
            Piece.Kind.pawn -> R.drawable.piece_wp
        }
    } else {
        when (kind) {
            Piece.Kind.king -> R.drawable.piece_bk
            Piece.Kind.queen -> R.drawable.piece_bq
            Piece.Kind.rook -> R.drawable.piece_br
            Piece.Kind.bishop -> R.drawable.piece_bb
            Piece.Kind.knight -> R.drawable.piece_bn
            Piece.Kind.pawn -> R.drawable.piece_bp
        }
    }

/** `pieceImage(kind:color:).resizable().scaledToFit()`: the piece, fitted into `modifier`'s size. */
@Composable
fun PieceImage(kind: Piece.Kind, color: Piece.Color, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(pieceImageRes(kind, color)),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
