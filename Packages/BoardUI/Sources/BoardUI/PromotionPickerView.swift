// BoardUI — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import SwiftUI

/// Lichess-style promotion picker: a column of choices over the promotion
/// square, with the rest of the board dimmed.
struct PromotionPickerView: View {
    let color: Piece.Color
    let square: Square
    let squareSize: CGFloat
    let orientation: Piece.Color
    let onPick: (Piece.Kind) -> Void
    let onCancel: () -> Void

    private static let choices: [Piece.Kind] = [.queen, .knight, .rook, .bishop]

    var body: some View {
        let rect = SquareGeometry.rect(of: square, squareSize: squareSize, orientation: orientation)
        let (_, y) = SquareGeometry.gridCoordinates(of: square, orientation: orientation)
        // Extend downward if the promotion square is drawn at the top,
        // upward if at the bottom (black promoting toward the viewer).
        let goesDown = y < 4

        ZStack(alignment: .topLeading) {
            Color.black.opacity(0.5)
                .onTapGesture { onCancel() }

            VStack(spacing: 0) {
                ForEach(Array(Self.choices.enumerated()), id: \.element) { index, kind in
                    Button {
                        onPick(kind)
                    } label: {
                        ZStack {
                            Circle()
                                .fill(Color(white: 0.95))
                                .shadow(radius: 2)
                            pieceImage(kind: kind, color: color)
                                .resizable()
                                .scaledToFit()
                                .padding(squareSize * 0.08)
                        }
                        .frame(width: squareSize, height: squareSize)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Promote to \(String(describing: kind))")
                }
            }
            .offset(
                x: rect.minX,
                y: goesDown ? rect.minY : rect.maxY - squareSize * CGFloat(Self.choices.count)
            )
        }
    }
}
