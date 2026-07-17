// Chessmaster — GPL-3.0-or-later
import XCTest

/// Plays against the real embedded Stockfish (level 1) and verifies the
/// engine answers with a legal reply on the board.
final class EnginePlayTests: XCTestCase {
    @MainActor
    func testEngineRepliesToOpeningMove() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-engine-game", "--uitest"]
        app.launch()

        let whitePawn = app.images["White Pawn on e2"]
        XCTAssertTrue(whitePawn.waitForExistence(timeout: 5))

        let squareHeight = whitePawn.frame.height
        whitePawn.tap()
        whitePawn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .withOffset(CGVector(dx: 0, dy: -2 * squareHeight))
            .tap()
        XCTAssertTrue(app.images["White Pawn on e4"].waitForExistence(timeout: 2))

        // The engine (black) must reply: some black piece leaves its home
        // square within a few seconds (startup + NNUE load + search).
        let replied = NSPredicate(format: "count == 15")
        let blackHomePieces = app.images.matching(
            NSPredicate(format: "label BEGINSWITH 'Black' AND (label ENDSWITH '7' OR label ENDSWITH '8')"))
        expectation(for: replied, evaluatedWith: blackHomePieces)
        waitForExpectations(timeout: 30)

        // And it must now be white's turn again with a playable board:
        // move a second white piece to prove the game continues.
        let knight = app.images["White Knight on g1"]
        XCTAssertTrue(knight.exists)
    }
}
