import XCTest
final class NextStepTest: XCTestCase {
    /// A free user can step through the game's moves (the "hands") in
    /// analysis — the replay controls are not gated.
    @MainActor func testFreeUserStepsThroughMoves() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--autostart-game", "--uitest"]  // free user
        app.launch()
        try PremiumTests.playScholarsMate(app)
        XCTAssertTrue(app.navigationBars["Analysis"].waitForExistence(timeout: 60))

        let counter = app.staticTexts["plyCounter"]
        XCTAssertTrue(counter.waitForExistence(timeout: 10))
        let forward = app.buttons["replayForward"]

        func ply() -> Int { Int(counter.label.split(separator: "/").first ?? "") ?? -1 }

        // Rewind to the start so forward-stepping is unambiguous.
        let first = app.buttons["replayFirst"]
        if first.isEnabled { first.tap() }
        let start = ply()
        XCTAssertTrue(forward.isEnabled, "free user's forward step must be tappable")
        forward.tap()
        XCTAssertEqual(ply(), start + 1, "forward should advance one move (the hands)")
        forward.tap()
        XCTAssertEqual(ply(), start + 2, "forward should keep advancing")
        NSLog("MOVESTEP_OK from \(start)")
    }
}
