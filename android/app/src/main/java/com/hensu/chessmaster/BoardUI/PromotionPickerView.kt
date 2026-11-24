// BoardUI — Chessmaster
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val promotionChoices: List<Piece.Kind> =
    listOf(Piece.Kind.queen, Piece.Kind.knight, Piece.Kind.rook, Piece.Kind.bishop)

/**
 * Lichess-style promotion picker: a column of choices over the promotion
 * square, with the rest of the board dimmed.
 *
 * `squareSize` is in pixels (the board measures itself in pixels).
 */
@Composable
fun PromotionPickerView(
    color: Piece.Color,
    square: Square,
    squareSize: Float,
    orientation: Piece.Color,
    onPick: (Piece.Kind) -> Unit,
    onCancel: () -> Unit,
) {
    val rect = SquareGeometry.rect(square, squareSize, orientation)
    val (_, y) = SquareGeometry.gridCoordinates(square, orientation)
    // Extend downward if the promotion square is drawn at the top,
    // upward if at the bottom (black promoting toward the viewer).
    val goesDown = y < 4
    val squareDp = with(LocalDensity.current) { squareSize.toDp() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.opacity(0.5))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() }
        )

        Column(
            Modifier.offset {
                IntOffset(
                    x = rect.left.roundToInt(),
                    y = (if (goesDown) rect.top else rect.bottom - squareSize * promotionChoices.size).roundToInt(),
                )
            }
        ) {
            for (kind in promotionChoices) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(squareDp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { onPick(kind) }
                        .semantics(mergeDescendants = true) { contentDescription = "Promote to ${kind.name}" },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .shadow(2.dp, CircleShape)
                            .background(Color(0xFFF2F2F2), CircleShape)
                    )
                    PieceImage(kind, color, Modifier.fillMaxSize().padding(squareDp * 0.08f))
                }
            }
        }
    }
}
