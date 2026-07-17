// Chessmaster — GPL-3.0-or-later
//
// Live end-to-end check against the real Supabase project: a signed-in
// player finishes a game and it syncs up automatically.
// Requires SUPABASE_URL/ANON_KEY to be configured in the build.

import XCTest

final class LiveBackendTests: XCTestCase {
    @MainActor
    func testSignedInGameSyncs() throws {
        guard let email = ProcessInfo.processInfo.environment["UITEST_EMAIL"],
              let password = ProcessInfo.processInfo.environment["UITEST_PASSWORD"] else {
            throw XCTSkip("Set TEST_RUNNER_UITEST_EMAIL / TEST_RUNNER_UITEST_PASSWORD to run live tests")
        }
        let app = XCUIApplication()
        app.launchEnvironment["UITEST_EMAIL"] = email
        app.launchEnvironment["UITEST_PASSWORD"] = password
        // Hot-seat autostart: the deterministic scholar's-mate helper plays
        // both sides, which an engine opponent would fight.
        app.launchArguments = ["--autostart-game", "--premium", "--uitest", "--uitest-signin-test"]
        app.launch()

        try PremiumTests.playScholarsMate(app)

        // Auto-insights confirm the game finished and was persisted.
        XCTAssertTrue(app.navigationBars["Analysis"].waitForExistence(timeout: 60))
        app.buttons["Close"].tap()

        // Real network round-trip: profile must show the live session.
        app.tabBars.buttons["Profile"].tap()
        app.swipeUp()   // account section lives at the bottom
        let signedIn = app.staticTexts.matching(NSPredicate(format: "label CONTAINS '@' OR label == 'Your account'")).firstMatch
        XCTAssertTrue(signedIn.waitForExistence(timeout: 20),
                      "sign-in against the live project should succeed")

        // Give the push a moment before the host-side row check.
        sleep(5)
    }
}
