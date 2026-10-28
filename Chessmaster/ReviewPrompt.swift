// Chessmaster — GPL-3.0-or-later
import StoreKit
import UIKit
import UserNotifications

/// Asks for an App Store rating at a happy moment — after a win, after
/// reading an AI coaching report, or after a run of clean puzzle solves.
/// Apple's prompt is unconditional (no "do you like us?" pre-screen, per
/// guideline 5.6.1) and the system shows it at most 3×/year; our own gates
/// keep it to engaged players and space requests out.
@MainActor
enum ReviewPrompt {
    private static let lastRequestKey = "review.lastRequestedAt"
    private static let versionKey = "review.lastRequestedVersion"
    private static let minFinishedGames = 5
    private static let minDaysBetween = 30.0
    private static let puzzleRunTarget = 5
    /// Clean puzzle solves this launch.
    private static var puzzleRun = 0

    /// A puzzle was finished; a run of first-try solves is a happy moment.
    static func puzzleSolved(firstTry: Bool, container: DependencyContainer) {
        puzzleRun = firstTry ? puzzleRun + 1 : 0
        guard puzzleRun >= puzzleRunTarget else { return }
        puzzleRun = 0
        maybeRequest(trigger: "puzzle_run", container: container)
    }

    /// Requests a review if every gate passes. `trigger` is for analytics.
    static func maybeRequest(trigger: String, container: DependencyContainer) {
        guard !ProcessInfo.processInfo.arguments.contains("--uitest") else { return }
        let defaults = UserDefaults.standard
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        // Once per app version, and never within 30 days of the last ask.
        guard defaults.string(forKey: versionKey) != version else { return }
        if let last = defaults.object(forKey: lastRequestKey) as? Date,
           Date().timeIntervalSince(last) < minDaysBetween * 86_400 { return }
        let finished = ((try? container.games.recentGames()) ?? [])
            .filter { $0.opponentType == "engine" && $0.termination != "aborted" }
            .count
        guard finished >= minFinishedGames else { return }

        Task {
            // Never stack on the notification-permission alert, which is
            // also asked after a win.
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            guard status != .notDetermined else { return }
            // Let the result UI / navigation settle first.
            try? await Task.sleep(for: .seconds(1.5))
            guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }) else { return }
            defaults.set(Date(), forKey: lastRequestKey)
            defaults.set(version, forKey: versionKey)
            container.sync.track("review_requested", ["trigger": trigger])
            AppStore.requestReview(in: scene)
        }
    }
}
