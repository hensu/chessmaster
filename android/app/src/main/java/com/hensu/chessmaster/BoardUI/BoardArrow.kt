// BoardUI — Chessmaster
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A move arrow drawn over the board (lichess-style): red for the move
 * played at a mistake, green for the engine's better move.
 */
data class BoardArrow(val from: Square, val to: Square, val color: Color)

/** `squareSize` is in pixels (the board measures itself in pixels). */
@Composable
fun ArrowsLayer(arrows: List<BoardArrow>, squareSize: Float, orientation: Piece.Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        for (arrow in arrows) {
            drawBoardArrow(arrow, squareSize, orientation)
        }
    }
}

private fun DrawScope.drawBoardArrow(arrow: BoardArrow, squareSize: Float, orientation: Piece.Color) {
    val start = SquareGeometry.center(arrow.from, squareSize, orientation)
    val end = SquareGeometry.center(arrow.to, squareSize, orientation)
    val angle = atan2(end.y - start.y, end.x - start.x)

    val headLength = squareSize * 0.38f
    val lineWidth = squareSize * 0.17f
    // Stop the shaft where the arrowhead begins.
    val shaftEnd = Offset(
        x = end.x - cos(angle) * headLength * 0.8f,
        y = end.y - sin(angle) * headLength * 0.8f,
    )

    drawLine(
        color = arrow.color.opacity(0.75),
        start = Offset(
            x = start.x + cos(angle) * squareSize * 0.18f,
            y = start.y + sin(angle) * squareSize * 0.18f,
        ),
        end = shaftEnd,
        strokeWidth = lineWidth,
        cap = StrokeCap.Round,
    )

    val head = Path()
    val headWidth = headLength * 0.75f
    head.moveTo(end.x, end.y)
    head.lineTo(
        end.x - cos(angle) * headLength - sin(angle) * headWidth / 2,
        end.y - sin(angle) * headLength + cos(angle) * headWidth / 2,
    )
    head.lineTo(
        end.x - cos(angle) * headLength + sin(angle) * headWidth / 2,
        end.y - sin(angle) * headLength - cos(angle) * headWidth / 2,
    )
    head.close()
    drawPath(head, arrow.color.opacity(0.75))
}
