// Chessmaster — GPL-3.0-or-later
//
// Full live money path: play a game, auto-insights open, request AI
// coaching against the real backend (Supabase + Gemini), render the report.
// Requires: deployed backend, GEMINI_API_KEY secret, and an active
// entitlement row for the simulator's signed-in user.

import XCTest

final class LiveCoachingTests: XCTestCase {
    @MainActor
    func testRealCoachingReportRenders() throws {
        guard let email = ProcessInfo.processInfo.environment["UITEST_EMAIL"],
              let password = ProcessInfo.processInfo.environment["UITEST_PASSWORD"] else {
            throw XCTSkip("Set TEST_RUNNER_UITEST_EMAIL / TEST_RUNNER_UITEST_PASSWORD to run live tests")
        }
        let app = XCUIApplication()
        app.launchEnvironment["UITEST_EMAIL"] = email
        app.launchEnvironment["UITEST_PASSWORD"] = password
        app.launchArguments = ["--autostart-game", "--premium", "--uitest", "--uitest-signin-test"]
        app.launch()

        // Deterministic game with a known blunder, then auto-insights.
        try PremiumTests.playScholarsMate(app)
        XCTAssertTrue(app.navigationBars["Analysis"].waitForExistence(timeout: 60))

        let coaching = app.buttons["Ask Chess AI"]
        XCTAssertTrue(coaching.waitForExistence(timeout: 10))
        if !coaching.isHittable { app.swipeUp() }
        coaching.tap()

        // Live round-trip: sync the game+evals up, Gemini writes the report.
        let report = app.staticTexts["Coach's report"]
        XCTAssertTrue(report.waitForExistence(timeout: 120),
                      "a real coaching report should render")

        // The report is grounded: the known blunder ply must be a key moment.
        let moment = app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'Nf6'")).firstMatch
        XCTAssertTrue(moment.exists, "the Nf6 blunder should appear as a key moment")
    }
}
