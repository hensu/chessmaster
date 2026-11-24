// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the share card shows, pulled from the engine review (free) and,
 * when it exists, the coach's note on the same moment.
 */
data class ShareCardContent(
    val headline: String,           // "Won vs Chess AI Level 5"
    val accuracy: Int,
    val playedLike: Int,
    val orientation: Piece.Color,
    val moment: Moment?,
) {
    data class Moment(
        val pieces: List<BoardPiece>,
        val arrows: List<BoardArrow>,
        val moveLabel: String,      // "18. Qxb7??"
        val drop: Int,              // win chance lost, percent
        val coachLine: String?,
    )
}

/** nil until the engine review exists. */
val AnalysisViewModel.shareCardContent: ShareCardContent?
    get() {
        val analysis = analysis ?: return null
        val replay = replay ?: return null
        val userIsWhite = game.playerColor != "black"
        val accuracy = if (userIsWhite) analysis.accuracyWhite else analysis.accuracyBlack

        val worst = userMistakes
            .mapNotNull { eval -> dropFor(eval.ply)?.let { Pair(eval, it) } }
            .maxByOrNull { it.second }
        var moment: ShareCardContent.Moment? = null
        if (worst != null && worst.second >= 8) {
            val (eval, drop) = worst
            val arrows = mutableListOf<BoardArrow>()
            OpponentMove.of(eval.uci)?.let { played ->
                arrows.add(BoardArrow(played.from, played.to, Sys.red))
            }
            eval.bestMoveUCI?.let { bestUCI ->
                OpponentMove.of(bestUCI)?.let { best ->
                    arrows.add(BoardArrow(best.from, best.to, Sys.green))
                }
            }
            val number = (eval.ply + 1) / 2
            moment = ShareCardContent.Moment(
                pieces = replay.boardPieces(eval.ply - 1),
                arrows = arrows,
                moveLabel = "$number${if (eval.moverIsWhite) "." else "..."} ${eval.san}${eval.classification.glyph}",
                drop = drop.roundToInt(),
                coachLine = coachNote(eval.ply)?.whatWentWrong,
            )
        }

        return ShareCardContent(
            headline = shareHeadline,
            accuracy = accuracy.roundToInt(),
            playedLike = AnalysisViewModel.playedLikeRating(accuracy),
            orientation = if (userIsWhite) Piece.Color.white else Piece.Color.black,
            moment = moment,
        )
    }

private val AnalysisViewModel.shareHeadline: String
    get() {
        val userIsWhite = game.playerColor != "black"
        val won = (game.result == "whiteWin" && userIsWhite) || (game.result == "blackWin" && !userIsWhite)
        val verb = if (game.result == "draw") "Drew" else (if (won) "Won" else "Lost")
        val opponent = when (game.opponentType) {
            "engine" -> game.engineLevel?.let { "Chess AI Level $it" } ?: "Chess AI"
            "friend" -> "a friend"
            else -> "an opponent"
        }
        return "$verb vs $opponent"
    }

/**
 * A playful "played like" number from accuracy (rounded to 50). Not a
 * rating: a rough curve so 70% reads ~1100 and 90% ~1800.
 */
fun AnalysisViewModel.Companion.playedLikeRating(accuracy: Double): Int {
    val a = min(max(accuracy, 30.0), 99.0)
    val raw = if (a < 70) 400 + (a - 50) * 35 else 1100 + (a - 70) * 35
    return max(200, (raw / 50).roundToInt() * 50)
}

/**
 * 4:5 portrait card (1080×1350 at 3x) for Instagram, TikTok, Messages.
 * On Android the card is drawn straight into a bitmap (the twin of the
 * SwiftUI view + ImageRenderer); the composable shows that bitmap.
 */
@Composable
fun ShareCardView(content: ShareCardContent, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { ShareCardView.image(content) }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier.size(360.dp, 450.dp))
    }
}

object ShareCardView {
    private val ground = rgb(0.078, 0.063, 0.051)
    private val panel = rgb(0.122, 0.094, 0.075)
    private val cream = rgb(0.941, 0.851, 0.710)
    private val muted = rgb(0.651, 0.580, 0.490)
    private val green = rgb(0.498, 0.722, 0.290)
    private val red = rgb(0.875, 0.325, 0.325)
    private val white = Color.White

    /** The card in points; rendered at 3x. */
    private const val width = 360f
    private const val height = 450f
    private const val scale = 3f
    private const val padding = 20f
    private const val spacing = 14f

    /** Rendered card for the share sheet. */
    fun image(content: ShareCardContent): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap((width * scale).toInt(), (height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        draw(canvas, content)
        bitmap
    }.getOrNull()

    private fun draw(canvas: Canvas, content: ShareCardContent) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = ground.toArgb()
        canvas.drawRect(0f, 0f, width, height, fill)

        val left = padding
        val right = width - padding
        val inner = right - left
        var y = padding

        // Header: icon, "Chess AI", "Game review".
        val icon = BitmapFactory.decodeResource(App.context.resources, R.drawable.app_icon_image)
        if (icon != null) {
            val rect = RectF(left, y, left + 26f, y + 26f)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, 6f, 6f, Path.Direction.CW) })
            canvas.drawBitmap(icon, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
        val brand = text(17f, 700, cream, serif = true)
        drawCentered(canvas, "Chess AI", left + 26f + 8f, y + 13f, brand)
        val review = text(13f, 600, muted)
        drawCentered(canvas, "Game review", right - review.measureText("Game review"), y + 13f, review)
        y += 26f + spacing

        // Headline.
        val headline = layout(content.headline, text(22f, 700, white, serif = true), inner, 4)
        canvas.save()
        canvas.translate(left, y)
        headline.draw(canvas)
        canvas.restore()
        y += headline.height + spacing

        // Accuracy + "played like".
        val statWidth = (inner - 10f) / 2
        val statHeight = stat(canvas, "${content.accuracy}%", "Accuracy", left, y, statWidth)
        stat(canvas, "~${content.playedLike}", "Played like", left + statWidth + 10f, y, statWidth)
        y += statHeight + spacing

        val moment = content.moment
        if (moment != null) {
            val label = text(13f, 700, muted)
            val move = text(15f, 800, red, mono = true)
            val rowHeight = max(lineHeight(label), lineHeight(move))
            val line = layout(
                moment.coachLine ?: "Cost me ${moment.drop}% win chance. The green arrow was the move.",
                text(13f, 400, white.opacity(0.9)), inner - 24f, 3,
            )
            val panelHeight = 12f + rowHeight + 8f + 140f + 8f + line.height + 12f
            fill.color = panel.toArgb()
            canvas.drawRoundRect(RectF(left, y, right, y + panelHeight), 14f, 14f, fill)

            var py = y + 12f
            drawCentered(canvas, "My biggest mistake", left + 12f, py + rowHeight / 2, label)
            drawCentered(canvas, moment.moveLabel, right - 12f - move.measureText(moment.moveLabel), py + rowHeight / 2, move)
            py += rowHeight + 8f

            board(canvas, moment, content.orientation, left + (inner - 140f) / 2, py, 140f)
            py += 140f + 8f

            canvas.save()
            canvas.translate(left + 12f, py)
            line.draw(canvas)
            canvas.restore()
        } else {
            val emoji = text(40f, 400, white)
            val none = text(17f, 700, green, serif = true)
            val panelHeight = 30f + lineHeight(emoji) + 6f + lineHeight(none) + 30f
            fill.color = panel.toArgb()
            canvas.drawRoundRect(RectF(left, y, right, y + panelHeight), 14f, 14f, fill)
            var py = y + 30f
            drawCentered(canvas, "🎯", left + (inner - emoji.measureText("🎯")) / 2, py + lineHeight(emoji) / 2, emoji)
            py += lineHeight(emoji) + 6f
            val message = "No big mistakes this game"
            drawCentered(canvas, message, left + (inner - none.measureText(message)) / 2, py + lineHeight(none) / 2, none)
        }

        // Footer, pinned to the bottom (Spacer above it).
        val ask = text(13f, 600, cream)
        val site = text(13f, 700, green)
        val footerCenter = height - padding - max(lineHeight(ask), lineHeight(site)) / 2
        drawCentered(canvas, "Can you beat my accuracy?", left, footerCenter, ask)
        drawCentered(canvas, "getchessai.com", right - site.measureText("getchessai.com"), footerCenter, site)
    }

    /** One stat tile; returns its height. */
    private fun stat(canvas: Canvas, value: String, label: String, x: Float, y: Float, w: Float): Float {
        val valuePaint = text(30f, 800, white)
        val labelPaint = text(12f, 600, muted)
        val h = 10f + lineHeight(valuePaint) + 2f + lineHeight(labelPaint) + 10f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = panel.toArgb()
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, fill)
        var cy = y + 10f
        drawCentered(canvas, value, x + (w - valuePaint.measureText(value)) / 2, cy + lineHeight(valuePaint) / 2, valuePaint)
        cy += lineHeight(valuePaint) + 2f
        drawCentered(canvas, label, x + (w - labelPaint.measureText(label)) / 2, cy + lineHeight(labelPaint) / 2, labelPaint)
        return h
    }

    /**
     * The mini board: BoardView(pieces, orientation, arrows, showsCoordinates: false)
     * at `size` points, clipped to a 6-point rounded rectangle.
     */
    private fun board(canvas: Canvas, moment: ShareCardContent.Moment, orientation: Piece.Color, x: Float, y: Float, size: Float) {
        val squareSize = size / 8
        val theme = BoardTheme.brown
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.translate(x, y)
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size, size), 6f, 6f, Path.Direction.CW) })

        for (square in Square.entries) {
            val rect = SquareGeometry.rect(square, squareSize, orientation)
            // a1 is dark: an odd file + rank sum is a light square.
            val isLight = (square.file.number + square.rank.value) % 2 == 1
            paint.color = (if (isLight) theme.lightSquare else theme.darkSquare).toArgb()
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
        }

        paint.color = android.graphics.Color.WHITE
        for (piece in moment.pieces) {
            val image = BitmapFactory.decodeResource(App.context.resources, pieceImageRes(piece.kind, piece.color)) ?: continue
            val rect = SquareGeometry.rect(piece.square, squareSize, orientation)
            canvas.drawBitmap(image, null, RectF(rect.left, rect.top, rect.right, rect.bottom), paint)
        }

        for (arrow in moment.arrows) {
            arrow(canvas, arrow, squareSize, orientation)
        }
        canvas.restore()
    }

    /** Same geometry as BoardUI's ArrowsLayer. */
    private fun arrow(canvas: Canvas, arrow: BoardArrow, squareSize: Float, orientation: Piece.Color) {
        val start = SquareGeometry.center(arrow.from, squareSize, orientation)
        val end = SquareGeometry.center(arrow.to, squareSize, orientation)
        val angle = atan2(end.y - start.y, end.x - start.x)

        val headLength = squareSize * 0.38f
        val lineWidth = squareSize * 0.17f
        // Stop the shaft where the arrowhead begins.
        val shaftEndX = end.x - cos(angle) * headLength * 0.8f
        val shaftEndY = end.y - sin(angle) * headLength * 0.8f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = arrow.color.opacity(0.75).toArgb()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lineWidth
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            start.x + cos(angle) * squareSize * 0.18f,
            start.y + sin(angle) * squareSize * 0.18f,
            shaftEndX, shaftEndY, paint,
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
        paint.style = Paint.Style.FILL
        canvas.drawPath(head, paint)
    }

    // MARK: Text

    /** `.font(.system(size:weight:design:))` + `.foregroundStyle(color)`. */
    private fun text(size: Float, weight: Int, color: Color, serif: Boolean = false, mono: Boolean = false): TextPaint {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        val family = if (serif) Typeface.SERIF else if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        paint.typeface = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, weight, false)
        } else {
            Typeface.create(family, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
        paint.textSize = size
        paint.color = color.toArgb()
        return paint
    }

    private fun lineHeight(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return metrics.descent - metrics.ascent
    }

    /** Draws one line starting at `x`, vertically centered on `centerY`. */
    private fun drawCentered(canvas: Canvas, text: String, x: Float, centerY: Float, paint: Paint) {
        val metrics = paint.fontMetrics
        canvas.drawText(text, x, centerY - (metrics.ascent + metrics.descent) / 2, paint)
    }

    /** Wrapping text with `.lineLimit(maxLines)`. */
    private fun layout(text: String, paint: TextPaint, width: Float, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}
