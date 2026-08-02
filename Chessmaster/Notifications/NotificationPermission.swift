// Chessmaster — GPL-3.0-or-later
import UserNotifications

enum NotificationPermission {
    /// Ask once for full (loud) notification permission — banner, sound, badge.
    /// Call at a high-intent moment (after a win); it no-ops if the user has
    /// already been asked. This is the chess.com-style ask that makes streak
    /// and re-engagement notifications actually visible.
    @MainActor static func requestFullIfNeeded() async {
        let center = UNUserNotificationCenter.current()
        guard await center.notificationSettings().authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
    }
}
