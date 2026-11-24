// BoardUI — Chessmaster
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The piece lifted by the finger. Its position lives in a separate state read only while drawing. */
private data class BoardViewDragState(val from: Square, val piece: BoardPiece)

/**
 * Lichess-style chess board: layered squares, highlights, legal-move dots,
 * pieces, drag layer, and promotion picker. Presentation-only — game rules
 * stay behind the lambdas so this view also serves read-only replay.
 *
 * Squares, coordinates, highlights and dots are one Canvas. Each piece is keyed by
 * `BoardPiece.id` and slides when its square changes. The finger position during a drag is
 * read only in draw/layer blocks, so a drag frame recomposes nothing.
 */
@Composable
fun BoardView(
    pieces: List<BoardPiece>,
    orientation: Piece.Color = Piece.Color.white,
    lastMove: LastMove? = null,
    checkSquare: Square? = null,
    arrows: List<BoardArrow> = emptyList(),
    showsCoordinates: Boolean = true,
    theme: BoardTheme = BoardTheme.brown,
    interactive: Boolean = true,
    canSelect: (Square) -> Boolean = { false },
    legalTargets: (Square) -> List<Square> = { emptyList() },
    onMove: (Square, Square) -> Unit = { _, _ -> },
    pendingPromotion: PendingPromotion? = null,
    onPromote: (Piece.Kind) -> Unit = {},
    onCancelPromotion: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedSquare by remember { mutableStateOf<Square?>(null) }
    var dragging by remember { mutableStateOf<BoardViewDragState?>(null) }
    val dragLocation = remember { mutableStateOf(Offset.Zero) }

    val currentPieces by rememberUpdatedState(pieces)
    val currentOrientation by rememberUpdatedState(orientation)
    val currentCanSelect by rememberUpdatedState(canSelect)
    val currentLegalTargets by rememberUpdatedState(legalTargets)
    val currentOnMove by rememberUpdatedState(onMove)

    fun attemptMove(from: Square, to: Square) {
        if (currentLegalTargets(from).contains(to)) {
            currentOnMove(from, to)
            selectedSquare = null
        } else if (currentCanSelect(to)) {
            selectedSquare = to
        } else {
            selectedSquare = null
        }
    }

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val squareSize = min(constraints.maxWidth, constraints.maxHeight) / 8f
        val currentSquareSize by rememberUpdatedState(squareSize)
        val density = LocalDensity.current
        val squareDp = with(density) { squareSize.toDp() }
        val textMeasurer = rememberTextMeasurer()

        val gestureEnabled = interactive && pendingPromotion == null
        val gesture = if (!gestureEnabled) Modifier else Modifier.pointerInput(Unit) {
            // DragGesture(minimumDistance: 0): the press itself already counts.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position

                fun changed(location: Offset) {
                    val square = SquareGeometry.square(start, currentSquareSize, currentOrientation) ?: return
                    if (dragging == null) {
                        // Only lift a piece the user is allowed to move.
                        val piece = currentPieces.firstOrNull { it.square == square }
                        if (piece != null && currentCanSelect(square)) {
                            dragLocation.value = location
                            dragging = BoardViewDragState(square, piece)
                            selectedSquare = square
                        }
                    } else {
                        dragLocation.value = location
                    }
                }

                fun ended(location: Offset) {
                    val drag = dragging
                    dragging = null
                    val endSquare = SquareGeometry.square(location, currentSquareSize, currentOrientation)

                    if (drag != null) {
                        val moved = hypot(location.x - start.x, location.y - start.y) > currentSquareSize * 0.3f
                        if (moved && endSquare != null) {
                            if (endSquare != drag.from) {
                                attemptMove(drag.from, endSquare)
                            } else {
                                selectedSquare = drag.from
                            }
                        }
                        // A stationary press-release is a tap: selection already set,
                        // wait for the second tap.
                        return
                    }

                    // No piece lifted: this is a tap on a destination (or empty square).
                    val end = endSquare ?: return
                    val selected = selectedSquare
                    if (selected != null) {
                        if (selected == end) {
                            selectedSquare = null
                        } else if (currentCanSelect(end)) {
                            selectedSquare = end
                        } else {
                            attemptMove(selected, end)
                        }
                    }
                }

                changed(start)
                if (dragging != null) down.consume()
                var last = start
                var finished = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    last = change.position
                    if (!change.pressed) {
                        finished = true
                        break
                    }
                    changed(last)
                    if (dragging != null) {
                        // A lifted piece owns the touch: a scrolling parent must not take it.
                        change.consume()
                    } else {
                        // Nothing lifted: if a parent (a scrolling screen) took the touch,
                        // it is a scroll, not a tap on the board.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { it.id == down.id && it.isConsumed }) break
                    }
                }
                if (finished) ended(last) else dragging = null
            }
        }

        // Squares, coordinates, highlights and legal-move dots.
        val targets = selectedSquare?.let { legalTargets(it) } ?: emptyList()
        Canvas(Modifier.fillMaxSize().then(gesture)) {
            drawSquares(theme, squareSize, orientation)
            if (showsCoordinates) {
                drawCoordinates(textMeasurer, theme, squareSize, orientation)
            }

            // Highlights
            if (lastMove != null) {
                squareFill(lastMove.from, theme.lastMoveHighlight, squareSize, orientation)
                squareFill(lastMove.to, theme.lastMoveHighlight, squareSize, orientation)
            }
            selectedSquare?.let { squareFill(it, theme.selection, squareSize, orientation) }
            if (checkSquare != null) {
                val rect = SquareGeometry.rect(checkSquare, squareSize, orientation)
                val endRadius = squareSize * 0.65f
                drawRect(
                    brush = Brush.radialGradient(
                        0f to theme.checkCore.opacity(0.9),
                        (0.05f / 0.65f) to theme.checkCore.opacity(0.9),
                        1f to theme.checkCore.opacity(0.0),
                        center = rect.center,
                        radius = endRadius,
                    ),
                    topLeft = rect.topLeft,
                    size = rect.size,
                )
            }

            // Dots
            for (target in targets) {
                val rect = SquareGeometry.rect(target, squareSize, orientation)
                val occupied = pieces.any { it.square == target }
                if (occupied) {
                    // Capture: ring around the edge of the square (lichess style).
                    drawCircle(
                        theme.legalDot,
                        radius = squareSize * 0.95f / 2,
                        center = rect.center,
                        style = Stroke(width = squareSize * 0.09f),
                    )
                } else {
                    drawCircle(theme.legalDot, radius = squareSize * 0.33f / 2, center = rect.center)
                }
            }
        }

        // Pieces
        for (piece in pieces) {
            key(piece.id) {
                BoardViewPiece(
                    piece = piece,
                    squareSize = squareSize,
                    orientation = orientation,
                    hidden = { dragging?.piece?.id == piece.id },
                )
            }
        }

        if (arrows.isNotEmpty()) {
            ArrowsLayer(arrows, squareSize, orientation)
        }

        // Drag layer
        val drag = dragging
        if (drag != null) {
            // Ghost target indicator under the finger.
            Canvas(Modifier.fillMaxSize()) {
                val target = SquareGeometry.square(dragLocation.value, squareSize, orientation)
                if (target != null) {
                    val rect = SquareGeometry.rect(target, squareSize, orientation)
                    drawCircle(Color.Black.opacity(0.12), radius = squareSize, center = rect.center)
                }
            }
            PieceImage(
                drag.piece.kind,
                drag.piece.color,
                Modifier
                    .size(squareDp * 1.6f)
                    .graphicsLayer {
                        val location = dragLocation.value
                        translationX = location.x - size.width / 2
                        translationY = location.y - squareSize * 0.6f - size.height / 2
                    },
            )
        }

        if (pendingPromotion != null) {
            PromotionPickerView(
                color = pendingPromotion.color,
                square = pendingPromotion.square,
                squareSize = squareSize,
                orientation = orientation,
                onPick = onPromote,
                onCancel = onCancelPromotion,
            )
        }
    }
}

/**
 * One piece, kept alive across moves by `key(piece.id)`. It slides to its new square
 * (`.animation(.easeOut(duration: 0.12), value: piece.square)`); flipping or resizing the
 * board is not animated, as on iOS, because only the square is.
 */
@Composable
private fun BoardViewPiece(piece: BoardPiece, squareSize: Float, orientation: Piece.Color, hidden: () -> Boolean) {
    // Position in board units, white's point of view: x = file 0...7, y = rank 0...7.
    val target = Offset((piece.square.file.number - 1).toFloat(), (piece.square.rank.value - 1).toFloat())
    val position = remember { Animatable(target, Offset.VectorConverter) }
    LaunchedEffect(target) {
        if (position.targetValue != target) {
            position.animateTo(target, tween(durationMillis = 120, easing = EaseOut))
        }
    }
    val label = "${if (piece.color == Piece.Color.white) "White" else "Black"} ${piece.kind.name} on ${piece.square.notation}"
    PieceImage(
        piece.kind,
        piece.color,
        Modifier
            .size(with(LocalDensity.current) { squareSize.toDp() })
            .graphicsLayer {
                val p = position.value
                val white = orientation == Piece.Color.white
                // Centered on the square, like `.position(center)`.
                translationX = ((if (white) p.x else 7 - p.x) + 0.5f) * squareSize - size.width / 2
                translationY = ((if (white) 7 - p.y else p.y) + 0.5f) * squareSize - size.height / 2
                alpha = if (hidden()) 0f else 1f
            }
            .semantics { contentDescription = label },
    )
}

// MARK: Layers

/** `square.color == .light` (a1 is dark). */
private fun Square.isLightSquare(): Boolean = (file.number + rank.value) % 2 == 1

private fun DrawScope.drawSquares(theme: BoardTheme, squareSize: Float, orientation: Piece.Color) {
    for (square in Square.entries) {
        val rect = SquareGeometry.rect(square, squareSize, orientation)
        drawRect(if (square.isLightSquare()) theme.lightSquare else theme.darkSquare, rect.topLeft, rect.size)
    }
}

private fun DrawScope.squareFill(square: Square, color: Color, squareSize: Float, orientation: Piece.Color) {
    val rect = SquareGeometry.rect(square, squareSize, orientation)
    drawRect(color, rect.topLeft, rect.size)
}

private fun DrawScope.drawCoordinates(
    textMeasurer: TextMeasurer,
    theme: BoardTheme,
    squareSize: Float,
    orientation: Piece.Color,
) {
    val fontSize = max(8 * density, squareSize * 0.22f)
    val style = TextStyle(fontSize = fontSize.toSp(), fontWeight = FontWeight.SemiBold)
    // Rank numbers in the top-left of the leftmost column.
    for (rankValue in 1..8) {
        val square = squareOn(if (orientation == Piece.Color.white) 1 else 8, rankValue)
        val (x, _) = SquareGeometry.gridCoordinates(square, orientation)
        if (x != 0) continue
        val rect = SquareGeometry.rect(square, squareSize, orientation)
        val color = if (square.isLightSquare()) theme.darkSquare else theme.lightSquare
        drawCenteredText(
            textMeasurer, rankValue.toString(), style, color,
            Offset(rect.left + fontSize * 0.45f, rect.top + fontSize * 0.55f),
        )
    }
    // File letters in the bottom-right of the bottom row.
    for (fileNumber in 1..8) {
        val square = squareOn(fileNumber, if (orientation == Piece.Color.white) 1 else 8)
        val (_, y) = SquareGeometry.gridCoordinates(square, orientation)
        if (y != 7) continue
        val rect = SquareGeometry.rect(square, squareSize, orientation)
        val color = if (square.isLightSquare()) theme.darkSquare else theme.lightSquare
        val letter = (96 + fileNumber).toChar().toString()
        drawCenteredText(
            textMeasurer, letter, style, color,
            Offset(rect.right - fontSize * 0.45f, rect.bottom - fontSize * 0.6f),
        )
    }
}

/** `context.draw(Text, at:)`: the text centered on `at`. */
private fun DrawScope.drawCenteredText(textMeasurer: TextMeasurer, text: String, style: TextStyle, color: Color, at: Offset) {
    val layout = textMeasurer.measure(text, style, softWrap = false)
    drawText(layout, color, Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
}

private fun squareOn(file: Int, rank: Int): Square =
    Square.fromRaw((rank - 1) * 8 + (file - 1)) ?: Square.a1
