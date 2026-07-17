// BoardUI — Chessmaster
// GPL-3.0-or-later

import Testing
import ChessDomain
@testable import BoardUI

@Suite struct SquareGeometryTests {
    @Test func whiteOrientationCorners() {
        // a1 draws bottom-left, h8 top-right.
        #expect(SquareGeometry.gridCoordinates(of: .a1, orientation: .white) == (0, 7))
        #expect(SquareGeometry.gridCoordinates(of: .h8, orientation: .white) == (7, 0))
        #expect(SquareGeometry.gridCoordinates(of: .e4, orientation: .white) == (4, 4))
    }

    @Test func blackOrientationCorners() {
        // Flipped: a1 draws top-right.
        #expect(SquareGeometry.gridCoordinates(of: .a1, orientation: .black) == (7, 0))
        #expect(SquareGeometry.gridCoordinates(of: .h8, orientation: .black) == (0, 7))
    }

    @Test func pointRoundTripsToSquare() {
        for orientation in [Piece.Color.white, .black] {
            for square in Square.allCases {
                let center = SquareGeometry.center(of: square, squareSize: 40, orientation: orientation)
                #expect(SquareGeometry.square(at: center, squareSize: 40, orientation: orientation) == square)
            }
        }
    }

    @Test func outsidePointsAreNil() {
        #expect(SquareGeometry.square(at: .init(x: -1, y: 10), squareSize: 40, orientation: .white) == nil)
        #expect(SquareGeometry.square(at: .init(x: 321, y: 10), squareSize: 40, orientation: .white) == nil)
    }
}
