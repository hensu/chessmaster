// Chessmaster — GPL-3.0-or-later
import XCTest

/// Finishes an engine game, opens it from History, and runs the free
/// Stockfish blunder-check pass end to end.
final class AnalysisUITests: XCTestCase {
    @MainActor
    func testAnalyzeFinishedGameShowsAccuracy() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-engine-game", "--uitest"]
        app.launch()

        // Play one move, wait for engine reply, then resign.
        let whitePawn = app.images["White Pawn on e2"]
        XCTAssertTrue(whitePawn.waitForExistence(timeout: 5))
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
        let confirm = app.buttons["Resign"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 3))
        confirm.tap()
        XCTAssertTrue(app.staticTexts["You lost"].waitForExistence(timeout: 5))
        app.buttons["Done"].firstMatch.tap()

        // Open the game from History and analyze it.
        app.tabBars.buttons["History"].tap()
        let row = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Level 1'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()

        let analyzeButton = app.buttons["Analyze game"]
        XCTAssertTrue(analyzeButton.waitForExistence(timeout: 5))
        analyzeButton.tap()

        // The 2-ply game analyzes in a few seconds; accuracy badges appear.
        let accuracy = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'accuracy'")).firstMatch
        XCTAssertTrue(accuracy.waitForExistence(timeout: 60), "accuracy badges should appear after analysis")

        // Move list is tappable for replay.
        XCTAssertTrue(app.staticTexts["1."].exists)
    }
}
