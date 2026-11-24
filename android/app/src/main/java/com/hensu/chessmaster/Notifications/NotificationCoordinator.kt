// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.content.Intent

/**
 * Handles taps on our notifications: attributes re-engagement opens to
 * analytics and routes the user to the Play tab, ready to start a game.
 * Friend-game pushes ("challenged you" / "joined your game") carry the
 * invite code and open that game.
 *
 * On iOS this is the UNUserNotificationCenterDelegate that RootView creates with the router,
 * the sync service and two closures. Here a tap arrives as the intent that opens MainActivity,
 * so the app's entry point calls `handle(intent)` from onCreate / onNewIntent, and the
 * collaborators are the singletons (`AppRouter.shared`, `DependencyContainer.shared`).
 */
object NotificationCoordinator {
    private val router: AppRouter get() = AppRouter.shared
    private val sync: SyncService get() = DependencyContainer.shared.sync

    private fun onFriendGame(code: String) {
        DependencyContainer.shared.pendingInviteCode = code
    }

    /** The friend game on screen, so its own pushes don't banner over it. */
    private fun activeFriendCode(): String? = DependencyContainer.shared.activeFriendGame?.state?.code

    /** userNotificationCenter(_:didReceive:): does nothing for an intent that is not a notification tap. */
    fun handle(intent: Intent?) {
        val info = LocalNotifications.userInfo(intent) ?: return
        // One tap is handled once, even if the activity is recreated with the same intent.
        intent?.removeExtra("notification.identifier")
        // Only our re-engagement notifications carry a "reason".
        val reason = info["reason"]
        val tier = info["tier"] ?: "0"
        val friendCode = info["friend_game"]
        val friendKind = info["kind"] ?: ""
        if (friendCode != null) {
            sync.track("friend_push_opened", mapOf("kind" to friendKind))
            onFriendGame(friendCode)
            return
        }
        if (reason != null) {
            sync.track("reengage_opened", mapOf("reason" to reason, "tier" to tier))
        }
        router.selectedTab = AppTab.play
    }

    /**
     * userNotificationCenter(_:willPresent:): whether a notification arriving while the app is
     * in the foreground should show. Friend-game pushes still show (a challenge is time-
     * sensitive), except for the game already on screen; nothing else does.
     */
    fun willPresent(userInfo: Map<String, String>): Boolean {
        val code = userInfo["friend_game"] ?: return false
        return activeFriendCode() != code
    }
}
