// BoardUI — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import CoreGraphics

/// Pure square <-> point math. All board layout goes through here so
/// flipping the board is a single `orientation` parameter.
public enum SquareGeometry {
    /// 0-based column (x) and row (y) of a square as drawn, top-left origin.
    public static func gridCoordinates(of square: Square, orientation: Piece.Color) -> (x: Int, y: Int) {
        let file = square.file.number - 1     // 0...7, a = 0
        let rank = square.rank.value - 1      // 0...7, 1st rank = 0
        switch orientation {
        case .white: return (x: file, y: 7 - rank)
        case .black: return (x: 7 - file, y: rank)
        }
    }

    public static func rect(of square: Square, squareSize: CGFloat, orientation: Piece.Color) -> CGRect {
        let (x, y) = gridCoordinates(of: square, orientation: orientation)
        return CGRect(
            x: CGFloat(x) * squareSize,
            y: CGFloat(y) * squareSize,
            width: squareSize,
            height: squareSize
        )
    }

    public static func center(of square: Square, squareSize: CGFloat, orientation: Piece.Color) -> CGPoint {
        let r = rect(of: square, squareSize: squareSize, orientation: orientation)
        return CGPoint(x: r.midX, y: r.midY)
    }

    /// The square containing `point`, or nil if outside the board.
    public static func square(at point: CGPoint, squareSize: CGFloat, orientation: Piece.Color) -> Square? {
        let x = Int(floor(point.x / squareSize))
        let y = Int(floor(point.y / squareSize))
        guard (0...7).contains(x), (0...7).contains(y) else { return nil }
        let file: Int, rank: Int
        switch orientation {
        case .white:
            file = x + 1
            rank = 8 - y
        case .black:
            file = 8 - x
            rank = y + 1
        }
        // Square raw values are laid out a1=0, b1=1, ..., h8=63 (rank-major).
        return Square(rawValue: (rank - 1) * 8 + (file - 1))
    }
}
