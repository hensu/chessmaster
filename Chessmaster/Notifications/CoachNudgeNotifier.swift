// Chessmaster — GPL-3.0-or-later
import Foundation
import UserNotifications

/// Local nudges for trial/paid players who have never used the AI coach —
/// the feature they're paying for. Tapping opens Play, where AutoCoach
/// generates their first report and hands it over, so the notification
/// only has to bring them back.
///
/// - "coach-ready": next day at the player's usual play hour.
/// - "coach-trial-end": the day before the free trial ends (trial only).
///
/// Rescheduled on the same hooks as ReengagementNotifier (launch,
/// background, after games); cancelled once the player has a report.
@MainActor
enum CoachNudgeNotifier {
    private static let readyID = "coach-ready"
    private static let trialEndID = "coach-trial-end"

    static func refresh(container: DependencyContainer) {
        guard !ProcessInfo.processInfo.arguments.contains("--uitest") else { return }
        let games = (try? container.games.recentGames()) ?? []
        // Same gate as the in-app report: premium, never coached, a game worth coaching.
        guard AutoCoach.candidate(games: games, container: container) != nil else {
            cancelAll(); return
        }
        let trialEndsAt = container.entitlements.trialEndsAt
        let hour = ReengagementNotifier.usualPlayHour(games: games)
        Task { await schedule(trialEndsAt: trialEndsAt, hour: hour) }
    }

    static func cancelAll() {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [readyID, trialEndID])
    }

    private static func schedule(trialEndsAt: Date?, hour: Int) async {
        let center = UNUserNotificationCenter.current()
        let status = await center.notificationSettings().authorizationStatus
        guard status == .authorized || status == .provisional else { return }
        cancelAll()
        let cal = Calendar.current
        let now = Date()

        if let base = cal.date(byAdding: .day, value: 1, to: now),
           let fire = cal.date(bySettingHour: hour, minute: 0, second: 0, of: base) {
            await add(id: readyID, at: fire, reason: "coach_ready",
                      title: "Your AI coach is ready 🧠",
                      body: trialEndsAt == nil
                        ? "See where your last game turned — and how to play it better next time."
                        : "See where your last game turned — and how to play it better. It's included in your free trial.")
        }

        // The day before the trial ends — their last chance to see the value
        // before being charged (or cancelling).
        if let trialEndsAt,
           let dayBefore = cal.date(byAdding: .day, value: -1, to: trialEndsAt),
           let fire = cal.date(bySettingHour: hour, minute: 0, second: 0, of: dayBefore),
           fire > now.addingTimeInterval(2 * 3600) {
            await add(id: trialEndID, at: fire, reason: "coach_trial_end",
                      title: "Your trial ends tomorrow",
                      body: "You haven't tried your AI coach yet — get a personal review of your last game before it ends.")
        }
    }

    private static func add(id: String, at fire: Date, reason: String, title: String, body: String) async {
        guard fire > Date() else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.userInfo = ["reason": reason, "tier": 0, "action": "play"]
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fire),
            repeats: false)
        try? await UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
}
