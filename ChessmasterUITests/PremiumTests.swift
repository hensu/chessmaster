// Chessmaster — GPL-3.0-or-later
import XCTest

final class PremiumTests: XCTestCase {
    /// A free user tapping the coaching button gets the paywall.
    @MainActor
    func testCoachingButtonShowsPaywallForFreeUsers() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-game", "--uitest"]
        app.launch()
        try Self.playScholarsMate(app)

        // Free users: no auto-insights — the game-over sheet offers a
        // locked button that opens the paywall.
        let locked = app.buttons["Game Review (Premium)"]
        XCTAssertTrue(locked.waitForExistence(timeout: 10))
        locked.tap()
        XCTAssertTrue(app.staticTexts["Choose your coach"].waitForExistence(timeout: 5),
                      "paywall should appear for free users")
    }

    /// A premium user can launch retry-training from the worst moment.
    @MainActor
    func testPremiumRetryTrainingLaunchesFromCriticalPosition() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-game", "--premium", "--uitest"]
        app.launch()
        try Self.playScholarsMate(app)

        // Insights auto-present after the game with analysis pre-computed.
        XCTAssertTrue(app.navigationBars["Analysis"].waitForExistence(timeout: 60))

        // 3...Nf6?? guarantees at least one critical ply.
        let retry = app.buttons["Replay from here"]
        XCTAssertTrue(retry.waitForExistence(timeout: 60))
        if !retry.isHittable { app.swipeUp() }
        retry.tap()

        // Training board opens from the stored FEN with an engine opponent.
        XCTAssertTrue(app.buttons["End training"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.images.matching(
            NSPredicate(format: "label BEGINSWITH 'White Queen'")).firstMatch.exists)
        app.buttons["End training"].tap()
    }

    /// Plays 1.e4 e5 2.Qh5 Nc6 3.Bc4 Nf6 4.Qxf7# by tapping squares.
    @MainActor
    static func playScholarsMate(_ app: XCUIApplication) throws {
        let pawn = app.images["White Pawn on e2"]
        XCTAssertTrue(pawn.waitForExistence(timeout: 5))
        let square = pawn.frame.height

        func tapSquare(from element: XCUIElement, files: CGFloat, ranks: CGFloat) {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                .withOffset(CGVector(dx: files * square, dy: -ranks * square))
                .tap()
        }
        func move(_ label: String, files: CGFloat, ranks: CGFloat, expect: String) {
            let piece = app.images[label]
            XCTAssertTrue(piece.waitForExistence(timeout: 3), "\(label) should exist")
            piece.tap()
            tapSquare(from: piece, files: files, ranks: ranks)
            XCTAssertTrue(app.images[expect].waitForExistence(timeout: 3),
                          "\(expect) after moving \(label)")
        }

        move("White Pawn on e2", files: 0, ranks: 2, expect: "White Pawn on e4")
        move("Black Pawn on e7", files: 0, ranks: -2, expect: "Black Pawn on e5")
        move("White Queen on d1", files: 4, ranks: 4, expect: "White Queen on h5")
        move("Black Knight on b8", files: 1, ranks: -2, expect: "Black Knight on c6")
        move("White Bishop on f1", files: -3, ranks: 3, expect: "White Bishop on c4")
        move("Black Knight on g8", files: -1, ranks: -2, expect: "Black Knight on f6")
        move("White Queen on h5", files: -2, ranks: 2, expect: "White Queen on f7")
    }
}
