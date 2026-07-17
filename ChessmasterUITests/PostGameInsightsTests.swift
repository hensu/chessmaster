// Chessmaster — GPL-3.0-or-later
import XCTest

/// Premium: after a game ends, the blunder-check runs automatically and the
/// analysis view is presented on its own — with the worst moment framed
/// on the board.
final class PostGameInsightsTests: XCTestCase {
    @MainActor
    func testInsightsAppearAutomaticallyAfterGame() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-game", "--premium", "--uitest"]
        app.launch()

        // Deterministic game with a known blunder (3...Nf6??).
        try PremiumTests.playScholarsMate(app)

        // Do not tap anything: the game-over sheet appears, then the
        // insights replace it on their own once the on-device analysis
        // finishes (a short game analyzes in ~1s, so don't race the sheet).
        let analysisTitle = app.navigationBars["Analysis"]
        XCTAssertTrue(analysisTitle.waitForExistence(timeout: 60),
                      "analysis should auto-present after the game")

        // The board is framed at the worst moment with its critical chip.
        let blunderChip = app.buttons.matching(
            NSPredicate(format: "label CONTAINS '??'")).firstMatch
        XCTAssertTrue(blunderChip.waitForExistence(timeout: 5),
                      "the blunder should be flagged in the critical strip")

        // Hold briefly so the screen is capturable in CI artifacts.
        sleep(6)

        // Skipping works: Close returns to Home.
        app.buttons["Close"].tap()
    }
}
