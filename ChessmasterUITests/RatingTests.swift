// Chessmaster — GPL-3.0-or-later
import XCTest

/// Plays a rated engine game, resigns, and verifies the Glicko-2 rating
/// delta shows on the game-over sheet.
final class RatingTests: XCTestCase {
    @MainActor
    func testResigningRatedGameShowsRatingDelta() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-engine-game", "--uitest"]
        app.launch()

        let whitePawn = app.images["White Pawn on e2"]
        XCTAssertTrue(whitePawn.waitForExistence(timeout: 5))

        // Two plies must exist before Resign replaces Abort.
        let squareHeight = whitePawn.frame.height
        whitePawn.tap()
        whitePawn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -2 * squareHeight))
            .tap()
        let blackMoved = app.images.matching(
            NSPredicate(format: "label BEGINSWITH 'Black' AND (label ENDSWITH '7' OR label ENDSWITH '8')"))
        expectation(for: NSPredicate(format: "count == 15"), evaluatedWith: blackMoved)
        waitForExpectations(timeout: 30)

        app.buttons["Resign"].firstMatch.tap()
        // Confirmation dialog.
        let confirm = app.buttons["Resign"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 3))
        confirm.tap()

        XCTAssertTrue(app.staticTexts["You lost"].waitForExistence(timeout: 5))
        // A negative delta like "-12" must be shown.
        let delta = app.staticTexts.matching(NSPredicate(format: "label MATCHES '-[0-9]+'"))
        XCTAssertTrue(delta.firstMatch.waitForExistence(timeout: 3), "rating delta should appear")
    }
}
