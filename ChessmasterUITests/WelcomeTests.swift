// Chessmaster — GPL-3.0-or-later
import XCTest

final class WelcomeTests: XCTestCase {
    /// First launch: full quiz → computed score → promise → signup;
    /// skipping shows the missing-out screen, "Play anyway" lands in play.
    @MainActor
    func testOnboardingQuizLeadsToSignupAndSkip() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--reset-welcome"]
        app.launch()

        func answer(_ question: String, with option: String) {
            XCTAssertTrue(app.staticTexts[question].waitForExistence(timeout: 5), question)
            app.buttons[option].tap()
            app.buttons["Continue"].tap()
        }

        answer("Do you want to get better at chess?", with: "Yes — that's why I'm here")
        answer("How long have you been playing?", with: "A year or two")

        // Rating page: "never had a rating" is preselected.
        XCTAssertTrue(app.staticTexts["Do you know your rating?"].waitForExistence(timeout: 3))
        app.buttons["Continue"].tap()

        answer("What's your go-to first move?", with: "e4 — king's pawn")
        answer("How does your middlegame feel?", with: "I trade evenly, then drift")
        answer("And endgames?", with: "I can mate with a queen")
        answer("Which player do you vibe with?", with: "Magnus — universal, plays everything")

        // Last question advances into the computing animation.
        XCTAssertTrue(app.staticTexts["Who do you want to beat?"].waitForExistence(timeout: 3))
        app.buttons["My friends"].tap()
        app.buttons["Continue"].tap()

        // Score reveal after the ~2.5s computation.
        XCTAssertTrue(app.staticTexts["Your chess score"].waitForExistence(timeout: 10))
        app.buttons["Continue"].tap()

        XCTAssertTrue(app.staticTexts["Our promise"].waitForExistence(timeout: 3))
        app.buttons["Let's go"].tap()

        // Signup page.
        XCTAssertTrue(app.staticTexts["Save your progress"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Continue with Apple"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Sign in with Google"].exists)
        XCTAssertTrue(app.buttons["Continue with email"].exists)
        app.buttons["Skip for now"].tap()

        XCTAssertTrue(app.staticTexts["Playing without an account"].waitForExistence(timeout: 3))
        app.buttons["Play anyway"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS '3+2'")).firstMatch.waitForExistence(timeout: 5))
    }
}
