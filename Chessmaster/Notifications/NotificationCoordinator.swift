// Chessmaster — GPL-3.0-or-later
import UserNotifications
import SupabaseSync

/// Handles taps on our notifications: attributes re-engagement opens to
/// analytics and routes the user to the Play tab, ready to start a game.
/// Friend-game pushes ("challenged you" / "joined your game") carry the
/// invite code and open that game.
final class NotificationCoordinator: NSObject, UNUserNotificationCenterDelegate {
    private let router: AppRouter
    private let sync: SyncService
    private let onFriendGame: @MainActor (String) -> Void
    /// The friend game on screen, so its own pushes don't banner over it.
    private let activeFriendCode: @MainActor () -> String?

    @MainActor
    init(router: AppRouter, sync: SyncService,
         onFriendGame: @escaping @MainActor (String) -> Void,
         activeFriendCode: @escaping @MainActor () -> String?) {
        self.router = router
        self.sync = sync
        self.onFriendGame = onFriendGame
        self.activeFriendCode = activeFriendCode
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
        let friendCode = info["friend_game"] as? String
        let friendKind = info["kind"] as? String ?? ""
        // Capture the (Sendable, @MainActor) collaborators — not self.
        let router = router
        let sync = sync
        let onFriendGame = onFriendGame
        Task { @MainActor in
            if let friendCode {
                sync.track("friend_push_opened", ["kind": friendKind])
                onFriendGame(friendCode)
                return
            }
            if let reason {
                sync.track("reengage_opened", ["reason": reason, "tier": tier])
            }
            router.selectedTab = .play
        }
        completionHandler()
    }

    /// Foreground: friend-game pushes still show (a challenge is time-
    /// sensitive), except for the game already on screen.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let code = notification.request.content.userInfo["friend_game"] as? String
        let activeFriendCode = activeFriendCode
        let complete = UncheckedSendable(completionHandler)
        Task { @MainActor in
            guard let code else { complete.value([]); return }
            complete.value(activeFriendCode() == code ? [] : [.banner, .sound])
        }
    }
}

/// Lets a non-Sendable completion handler hop to the main actor once.
private struct UncheckedSendable<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
