// Chessmaster — GPL-3.0-or-later
import XCTest

/// Learn tab: puzzle categories render, and a lesson can be completed
/// step-by-step by making the taught moves on the real board.
final class LearnTests: XCTestCase {
    @MainActor
    func testLessonRookStepsThrough() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--uitest"]
        app.launch()

        app.tabBars.buttons["Learn"].tap()
        XCTAssertTrue(app.staticTexts["Puzzles"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Mate in 1"].exists)
        XCTAssertTrue(app.staticTexts["Forks"].exists)

        app.staticTexts["The Rook"].tap()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Move the rook up to a4'")).firstMatch
            .waitForExistence(timeout: 5))

        // a1 -> a4: three squares up the board.
        let rook = app.images["White Rook on a1"]
        XCTAssertTrue(rook.waitForExistence(timeout: 5))
        let squareHeight = rook.frame.height
        rook.tap()
        rook.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -3 * squareHeight))
            .tap()

        // Step advances to the sideways-move prompt.
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'sideways'")).firstMatch
            .waitForExistence(timeout: 6))
    }

    @MainActor
    func testPuzzleCategoryOpens() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--uitest"]
        app.launch()

        app.tabBars.buttons["Learn"].tap()
        XCTAssertTrue(app.staticTexts["Mate in 1"].waitForExistence(timeout: 5))
        app.staticTexts["Mate in 1"].tap()

        // A puzzle loads: rating label + the find-the-move banner after the
        // setup move plays itself.
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Puzzle rating'")).firstMatch
            .waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'find the best move'")).firstMatch
            .waitForExistence(timeout: 8))
    }
}
