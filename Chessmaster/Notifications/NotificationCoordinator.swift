// Chessmaster — GPL-3.0-or-later
import UserNotifications
import SupabaseSync

/// Handles taps on our local notifications: attributes re-engagement opens to
/// analytics and routes the user to the Play tab, ready to start a game.
final class NotificationCoordinator: NSObject, UNUserNotificationCenterDelegate {
    private let router: AppRouter
    private let sync: SyncService

    @MainActor
    init(router: AppRouter, sync: SyncService) {
        self.router = router
        self.sync = sync
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        // Only our re-engagement notifications carry a "reason".
        let reason = info["reason"] as? String
        let tier = (info["tier"] as? Int).map(String.init) ?? "0"
        // Capture the (Sendable, @MainActor) collaborators — not self.
        let router = router
        let sync = sync
        Task { @MainActor in
            if let reason {
                sync.track("reengage_opened", ["reason": reason, "tier": tier])
            }
            router.selectedTab = .play
        }
        completionHandler()
    }
}
