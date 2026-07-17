// BoardUI — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import SwiftUI

extension Piece.Kind {
    var assetLetter: String {
        switch self {
        case .king: "K"
        case .queen: "Q"
        case .rook: "R"
        case .bishop: "B"
        case .knight: "N"
        case .pawn: "P"
        }
    }
}

/// cburnett piece image from the package asset catalog, e.g. "wK".
public func pieceImage(kind: Piece.Kind, color: Piece.Color) -> Image {
    let name = (color == .white ? "w" : "b") + kind.assetLetter
    return Image(name, bundle: .module)
}
