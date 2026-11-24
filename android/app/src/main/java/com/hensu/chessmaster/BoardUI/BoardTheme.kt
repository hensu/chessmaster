// BoardUI — Chessmaster
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.ui.graphics.Color

/** Board colors. The default is the classic lichess brown theme. */
data class BoardTheme(
    var lightSquare: Color,
    var darkSquare: Color,
    var lastMoveHighlight: Color,
    var selection: Color,
    var legalDot: Color,
    var checkCore: Color,
) {
    companion object {
        val brown = BoardTheme(
            lightSquare = rgb(0xF0 / 255.0, 0xD9 / 255.0, 0xB5 / 255.0),
            darkSquare = rgb(0xB5 / 255.0, 0x88 / 255.0, 0x63 / 255.0),
            lastMoveHighlight = rgb(0x9B / 255.0, 0xC7 / 255.0, 0x00 / 255.0).opacity(0.41),
            selection = rgb(0x14 / 255.0, 0x55 / 255.0, 0x1E / 255.0).opacity(0.5),
            legalDot = rgb(0x14 / 255.0, 0x55 / 255.0, 0x1E / 255.0).opacity(0.5),
            checkCore = rgb(1.0, 0.0, 0.0),
        )
    }
}
