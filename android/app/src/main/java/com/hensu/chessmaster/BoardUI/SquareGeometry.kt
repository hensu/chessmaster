// BoardUI — Chessmaster
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.floor

/**
 * Pure square <-> point math. All board layout goes through here so
 * flipping the board is a single `orientation` parameter.
 */
object SquareGeometry {
    /** 0-based column (x, `first`) and row (y, `second`) of a square as drawn, top-left origin. */
    fun gridCoordinates(of: Square, orientation: Piece.Color): Pair<Int, Int> {
        val file = of.file.number - 1     // 0...7, a = 0
        val rank = of.rank.value - 1      // 0...7, 1st rank = 0
        return when (orientation) {
            Piece.Color.white -> Pair(file, 7 - rank)
            Piece.Color.black -> Pair(7 - file, rank)
        }
    }

    fun rect(of: Square, squareSize: Float, orientation: Piece.Color): Rect {
        val (x, y) = gridCoordinates(of, orientation)
        return Rect(
            left = x * squareSize,
            top = y * squareSize,
            right = x * squareSize + squareSize,
            bottom = y * squareSize + squareSize,
        )
    }

    fun center(of: Square, squareSize: Float, orientation: Piece.Color): Offset =
        rect(of, squareSize, orientation).center

    /** The square containing `point`, or null if outside the board. */
    fun square(at: Offset, squareSize: Float, orientation: Piece.Color): Square? {
        val x = floor(at.x / squareSize).toInt()
        val y = floor(at.y / squareSize).toInt()
        if (x !in 0..7 || y !in 0..7) return null
        val file: Int
        val rank: Int
        when (orientation) {
            Piece.Color.white -> {
                file = x + 1
                rank = 8 - y
            }
            Piece.Color.black -> {
                file = 8 - x
                rank = y + 1
            }
        }
        // Square raw values are laid out a1=0, b1=1, ..., h8=63 (rank-major).
        return Square.fromRaw((rank - 1) * 8 + (file - 1))
    }
}
