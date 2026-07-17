// Chessmaster — GPL-3.0-or-later
import XCTest

final class PersistenceUITests: XCTestCase {
    /// Kill the app mid-game, relaunch, and continue from the same position.
    @MainActor
    func testResumeAfterKill() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--uitest"]
        app.launch()

        let quickPlay = app.buttons.matching(NSPredicate(format: "label CONTAINS '3+2'")).firstMatch
        XCTAssertTrue(quickPlay.waitForExistence(timeout: 5))
        quickPlay.tap()

        let whitePawn = app.images["White Pawn on e2"]
        XCTAssertTrue(whitePawn.waitForExistence(timeout: 5))
        let squareHeight = whitePawn.frame.height
        whitePawn.tap()
        whitePawn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -2 * squareHeight))
            .tap()
        XCTAssertTrue(app.images["White Pawn on e4"].waitForExistence(timeout: 2))

        app.terminate()
        app.launch()

        let continueButton = app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'Continue game'")).firstMatch
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5), "resume card should appear")
        continueButton.tap()

        // The board must restore with the pawn already on e4. (The engine
        // opponent may already have replied, so black's pawns can vary.)
        XCTAssertTrue(app.images["White Pawn on e4"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.images["Black King on e8"].exists)
    }

    /// A finished game shows up in History with its rating delta, and
    /// Profile shows the record.
    @MainActor
    func testFinishedGameAppearsInHistory() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-engine-game"]
        app.launch()

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

        app.tabBars.buttons["History"].tap()
        let row = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Level 1'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "game should be listed in history")

        app.tabBars.buttons["Profile"].tap()
        XCTAssertTrue(app.staticTexts["Losses"].waitForExistence(timeout: 5))
    }
}
