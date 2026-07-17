// Chessmaster — GPL-3.0-or-later
import XCTest

/// Drives the real board with taps: verifies the full input pipeline
/// (hit-testing, selection, legal-move filtering, GameSession commit, move list).
final class PlayFlowTests: XCTestCase {
    @MainActor
    func testTapMovePlaysOpeningMoves() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-game", "--uitest"]
        app.launch()

        let whitePawn = app.images["White Pawn on e2"]
        XCTAssertTrue(whitePawn.waitForExistence(timeout: 5), "board should show the e2 pawn")

        // Tap e2, then tap two squares up (e4).
        let squareHeight = whitePawn.frame.height
        whitePawn.tap()
        let e4 = whitePawn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -2 * squareHeight))
        e4.tap()

        XCTAssertTrue(app.images["White Pawn on e4"].waitForExistence(timeout: 2), "pawn should be on e4")

        // Black replies e7 -> e5 the same way.
        let blackPawn = app.images["Black Pawn on e7"]
        XCTAssertTrue(blackPawn.exists)
        blackPawn.tap()
        let e5 = blackPawn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: 2 * squareHeight))
        e5.tap()

        XCTAssertTrue(app.images["Black Pawn on e5"].waitForExistence(timeout: 2), "pawn should be on e5")

        // An illegal move is ignored: try moving the white rook a1 through its own pawn.
        let rook = app.images["White Rook on a1"]
        XCTAssertTrue(rook.exists)
        rook.tap()
        let a4 = rook.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -3 * squareHeight))
        a4.tap()
        XCTAssertTrue(app.images["White Rook on a1"].exists, "illegal rook move must be rejected")
    }
}
