// Chessmaster — GPL-3.0-or-later
import SwiftUI
import BoardUI
import ChessDomain

/// A tiny static board showing one move from the starting position, with the
/// from/to squares highlighted — so "e4" or "Nf3" reads as a picture.
struct MiniMoveBoard: View {
    let from: String
    let to: String
    var square: CGFloat = 10

    private static let files = Array("abcdefgh")
    private static let backRank: [Piece.Kind] = [.rook, .knight, .bishop, .queen, .king, .bishop, .knight, .rook]
    private let theme = BoardTheme.brown

    var body: some View {
        VStack(spacing: 0) {
            ForEach((1...8).reversed(), id: \.self) { rank in
                HStack(spacing: 0) {
                    ForEach(0..<8, id: \.self) { file in
                        cell(file: file, rank: rank)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .accessibilityHidden(true)
    }

    private func cell(file: Int, rank: Int) -> some View {
        let name = "\(Self.files[file])\(rank)"
        let light = (file + rank) % 2 == 0
        return ZStack {
            Rectangle().fill(light ? theme.lightSquare : theme.darkSquare)
            if name == from || name == to {
                Rectangle().fill(theme.lastMoveHighlight)
            }
            if let piece = piece(at: name) {
                pieceImage(kind: piece.kind, color: piece.color)
                    .resizable()
                    .scaledToFit()
            }
        }
        .frame(width: square, height: square)
    }

    /// The starting position with this one move played.
    private func piece(at name: String) -> (kind: Piece.Kind, color: Piece.Color)? {
        if name == from { return nil }
        let source = name == to ? from : name
        let chars = Array(source)
        guard let file = Self.files.firstIndex(of: chars[0]), let rank = Int(String(chars[1])) else { return nil }
        switch rank {
        case 1: return (Self.backRank[file], .white)
        case 2: return (.pawn, .white)
        case 7: return (.pawn, .black)
        case 8: return (Self.backRank[file], .black)
        default: return nil
        }
    }
}
