// BoardUI — Chessmaster
// GPL-3.0-or-later

import SwiftUI

/// Board colors. The default is the classic lichess brown theme.
public struct BoardTheme: Sendable, Equatable {
    public var lightSquare: Color
    public var darkSquare: Color
    public var lastMoveHighlight: Color
    public var selection: Color
    public var legalDot: Color
    public var checkCore: Color

    public static let brown = BoardTheme(
        lightSquare: Color(red: 0xF0 / 255, green: 0xD9 / 255, blue: 0xB5 / 255),
        darkSquare: Color(red: 0xB5 / 255, green: 0x88 / 255, blue: 0x63 / 255),
        lastMoveHighlight: Color(red: 0x9B / 255, green: 0xC7 / 255, blue: 0x00 / 255).opacity(0.41),
        selection: Color(red: 0x14 / 255, green: 0x55 / 255, blue: 0x1E / 255).opacity(0.5),
        legalDot: Color(red: 0x14 / 255, green: 0x55 / 255, blue: 0x1E / 255).opacity(0.5),
        checkCore: Color(red: 1.0, green: 0.0, blue: 0.0)
    )

    public init(
        lightSquare: Color, darkSquare: Color, lastMoveHighlight: Color,
        selection: Color, legalDot: Color, checkCore: Color
    ) {
        self.lightSquare = lightSquare
        self.darkSquare = darkSquare
        self.lastMoveHighlight = lastMoveHighlight
        self.selection = selection
        self.legalDot = legalDot
        self.checkCore = checkCore
    }
}
