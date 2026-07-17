// Chessmaster — GPL-3.0-or-later
import Foundation
import PersistenceKit
import UserNotifications

/// Local streak notifications: an evening "streak on the line" reminder and
/// a next-morning milestone nudge. Permission is requested only once the
/// player has a streak worth protecting.
@MainActor
enum StreakNotifier {
    private static let reminderID = "streak-reminder"
    private static let milestoneID = "streak-milestone"
    private static let winMilestones: Set<Int> = [3, 5, 10, 20]
    private static let dayMilestones: Set<Int> = [3, 7, 14, 30]

    /// Recomputes streaks and (re)schedules notifications. Call after every
    /// finished game and once at launch.
    static func refresh(games: [GameRecord]) {
        guard !ProcessInfo.processInfo.arguments.contains("--uitest") else { return }
        let streaks = Streaks.compute(games: games)
        let playedToday = games.contains {
            Calendar.current.isDateInToday($0.endedAt) && $0.termination != "aborted"
        }
        let hour = usualPlayHour(games: games)
        Task { await schedule(streaks: streaks, playedToday: playedToday, usualHour: hour) }
    }

    /// The hour of day the player most often finishes games (median of the
    /// last 30), so nudges land when they actually play. Defaults to 7pm.
    private static func usualPlayHour(games: [GameRecord]) -> Int {
        let hours = games
            .filter { $0.opponentType == "engine" && $0.termination != "aborted" }
            .prefix(30)
            .map { Calendar.current.component(.hour, from: $0.endedAt) }
            .sorted()
        guard !hours.isEmpty else { return 19 }
        return hours[hours.count / 2]
    }

    private static func schedule(streaks: Streaks, playedToday: Bool, usualHour: Int) async {
        // Nothing worth protecting yet: never prompt, never schedule.
        guard streaks.playDays >= 2 || streaks.wins >= 2 else { return }

        let center = UNUserNotificationCenter.current()
        var status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
            status = await center.notificationSettings().authorizationStatus
        }
        guard status == .authorized || status == .provisional else { return }

        center.removePendingNotificationRequests(withIdentifiers: [reminderID, milestoneID])
        let calendar = Calendar.current
        let now = Date()

        if streaks.playDays >= 2 {
            let content = UNMutableNotificationContent()
            var fireDate: Date?
            if playedToday {
                // Streak extended today: celebrate tomorrow, at the hour
                // they usually play.
                let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now))!
                fireDate = calendar.date(bySettingHour: usualHour, minute: 0, second: 0, of: tomorrow)
                content.title = "🔥 \(streaks.playDays)-day streak and counting"
                content.body = "You usually play around now — one game keeps it rolling."
            } else {
                // At risk today: remind at their usual hour; last call 9pm.
                let today = calendar.startOfDay(for: now)
                var candidate = calendar.date(bySettingHour: usualHour, minute: 0, second: 0, of: today)!
                if candidate <= now {
                    candidate = calendar.date(bySettingHour: 21, minute: 0, second: 0, of: today)!
                }
                fireDate = candidate
                content.title = "🔥 \(streaks.playDays)-day streak on the line"
                content.body = "One quick game keeps it alive."
            }
            if let fireDate, fireDate > now {
                content.sound = .default
                let trigger = UNCalendarNotificationTrigger(
                    dateMatching: calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate),
                    repeats: false
                )
                try? await center.add(UNNotificationRequest(
                    identifier: reminderID, content: content, trigger: trigger))
            }
        }

        // Milestone nudge: next morning after hitting a notable number.
        let winHit = winMilestones.contains(streaks.wins)
        let dayHit = dayMilestones.contains(streaks.playDays)
        if winHit || dayHit {
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: now))!
            let fireDate = calendar.date(bySettingHour: 10, minute: 0, second: 0, of: tomorrow)!
            let content = UNMutableNotificationContent()
            content.title = winHit
                ? "🏆 \(streaks.wins) wins in a row!"
                : "🔥 \(streaks.playDays) days straight!"
            content.body = "You're on a roll — a quick game keeps it going."
            content.sound = .default
            let trigger = UNCalendarNotificationTrigger(
                dateMatching: calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate),
                repeats: false
            )
            try? await center.add(UNNotificationRequest(
                identifier: milestoneID, content: content, trigger: trigger))
        }
    }
}
