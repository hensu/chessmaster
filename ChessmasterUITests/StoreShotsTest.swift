// Temporary: stages App Store screenshot screens with hold markers.
import XCTest

final class StoreShotsTest: XCTestCase {
    @MainActor
    func testHoldStoreScreens() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--uitest", "--premium"]
        app.launch()

        XCTAssertTrue(app.tabBars.buttons["Learn"].waitForExistence(timeout: 8))
        NSLog("STORE_HOME_READY")
        Thread.sleep(forTimeInterval: 6)

        app.tabBars.buttons["Learn"].tap()
        XCTAssertTrue(app.staticTexts["Puzzles"].waitForExistence(timeout: 6))
        NSLog("STORE_LEARN_READY")
        Thread.sleep(forTimeInterval: 6)

        app.staticTexts["Mate in 1"].tap()
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'find the best move'")).firstMatch
            .waitForExistence(timeout: 8))
        NSLog("STORE_PUZZLE_READY")
        Thread.sleep(forTimeInterval: 6)

        // Fresh launch straight onto the board for the game + review shots.
        app.launchArguments = ["--autostart-game", "--uitest", "--premium"]
        app.launch()

        let pawn = app.images["White Pawn on e2"]
        XCTAssertTrue(pawn.waitForExistence(timeout: 8))
        let square = pawn.frame.height
        func move(_ label: String, files: CGFloat, ranks: CGFloat, expect: String) {
            let piece = app.images[label]
            XCTAssertTrue(piece.waitForExistence(timeout: 3), label)
            piece.tap()
            piece.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                .withOffset(CGVector(dx: files * square, dy: -ranks * square))
                .tap()
            XCTAssertTrue(app.images[expect].waitForExistence(timeout: 3), expect)
        }
        move("White Pawn on e2", files: 0, ranks: 2, expect: "White Pawn on e4")
        move("Black Pawn on e7", files: 0, ranks: -2, expect: "Black Pawn on e5")
        move("White Queen on d1", files: 4, ranks: 4, expect: "White Queen on h5")
        move("Black Knight on b8", files: 1, ranks: -2, expect: "Black Knight on c6")
        move("White Bishop on f1", files: -3, ranks: 3, expect: "White Bishop on c4")
        NSLog("STORE_GAME_READY")
        Thread.sleep(forTimeInterval: 6)

        move("Black Knight on g8", files: -1, ranks: -2, expect: "Black Knight on f6")
        move("White Queen on h5", files: -2, ranks: 2, expect: "White Queen on f7")

        // Premium: insights open automatically with analysis pre-computed.
        XCTAssertTrue(app.navigationBars["Analysis"].waitForExistence(timeout: 60))
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'accuracy'")).firstMatch
            .waitForExistence(timeout: 60))
        Thread.sleep(forTimeInterval: 1)
        NSLog("STORE_REVIEW_READY")
        Thread.sleep(forTimeInterval: 8)
    }
}
