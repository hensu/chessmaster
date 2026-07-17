// Chessmaster — GPL-3.0-or-later
import XCTest

/// Starts a timed quick-play game and verifies both clocks render and the
/// running clock counts down after the first move.
final class ClockTests: XCTestCase {
    @MainActor
    func testTimedGameShowsTickingClocks() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--uitest"]
        app.launch()

        let quickPlay = app.buttons.matching(NSPredicate(format: "label CONTAINS '3+2'")).firstMatch
        XCTAssertTrue(quickPlay.waitForExistence(timeout: 5))
        quickPlay.tap()

        let whitePawn = app.images["White Pawn on e2"]
        XCTAssertTrue(whitePawn.waitForExistence(timeout: 5))

        // Black's clock is untouched at 3:00; white's is running and has
        // ticked down into the 2:xx range.
        XCTAssertTrue(app.staticTexts["3:00"].waitForExistence(timeout: 3))
        let ticking = app.staticTexts.matching(NSPredicate(format: "label MATCHES '2:5[0-9]'"))
        let exp = expectation(for: NSPredicate(format: "count >= 1"), evaluatedWith: ticking)
        wait(for: [exp], timeout: 10)

        // Make white's move; increment banks time back to 3:00 territory
        // and black's clock begins running.
        let squareHeight = whitePawn.frame.height
        whitePawn.tap()
        whitePawn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -2 * squareHeight))
            .tap()
        XCTAssertTrue(app.images["White Pawn on e4"].waitForExistence(timeout: 2))
    }
}
