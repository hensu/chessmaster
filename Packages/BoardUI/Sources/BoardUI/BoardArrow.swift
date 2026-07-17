// BoardUI — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import SwiftUI

/// A move arrow drawn over the board (lichess-style): red for the move
/// played at a mistake, green for the engine's better move.
public struct BoardArrow: Hashable, Sendable {
    public let from: Square
    public let to: Square
    public let color: Color

    public init(from: Square, to: Square, color: Color) {
        self.from = from
        self.to = to
        self.color = color
    }
}

struct ArrowsLayer: View {
    let arrows: [BoardArrow]
    let squareSize: CGFloat
    let orientation: Piece.Color

    var body: some View {
        Canvas { context, _ in
            for arrow in arrows {
                draw(arrow, in: context)
            }
        }
        .allowsHitTesting(false)
    }

    private func draw(_ arrow: BoardArrow, in context: GraphicsContext) {
        let start = SquareGeometry.center(of: arrow.from, squareSize: squareSize, orientation: orientation)
        let end = SquareGeometry.center(of: arrow.to, squareSize: squareSize, orientation: orientation)
        let angle = atan2(end.y - start.y, end.x - start.x)

        let headLength = squareSize * 0.38
        let lineWidth = squareSize * 0.17
        // Stop the shaft where the arrowhead begins.
        let shaftEnd = CGPoint(
            x: end.x - cos(angle) * headLength * 0.8,
            y: end.y - sin(angle) * headLength * 0.8
        )

        var shaft = Path()
        shaft.move(to: CGPoint(
            x: start.x + cos(angle) * squareSize * 0.18,
            y: start.y + sin(angle) * squareSize * 0.18
        ))
        shaft.addLine(to: shaftEnd)
        context.stroke(
            shaft,
            with: .color(arrow.color.opacity(0.75)),
            style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
        )

        var head = Path()
        let headWidth = headLength * 0.75
        head.move(to: end)
        head.addLine(to: CGPoint(
            x: end.x - cos(angle) * headLength - sin(angle) * headWidth / 2,
            y: end.y - sin(angle) * headLength + cos(angle) * headWidth / 2
        ))
        head.addLine(to: CGPoint(
            x: end.x - cos(angle) * headLength + sin(angle) * headWidth / 2,
            y: end.y - sin(angle) * headLength - cos(angle) * headWidth / 2
        ))
        head.closeSubpath()
        context.fill(head, with: .color(arrow.color.opacity(0.75)))
    }
}
