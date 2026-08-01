// Chessmaster — GPL-3.0-or-later
import UIKit
import SupabaseSync

/// Receives the APNs device token (SwiftUI has no other hook for it) and hands
/// it to whoever set `onToken`.
final class PushAppDelegate: NSObject, UIApplicationDelegate {
    /// Set once at launch (main thread) to forward the token to SyncService.
    nonisolated(unsafe) static var onToken: ((String) -> Void)?

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        PushAppDelegate.onToken?(hex)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Push just won't be available; local notifications still cover
        // re-engagement, so this is non-fatal.
    }
}

enum Push {
    /// Which APNs environment this build's tokens belong to. Debug builds run
    /// on a development profile (sandbox APNs); TestFlight/App Store builds use
    /// production. The backend sends to the matching Apple host.
    static var environment: String {
        #if DEBUG
        "sandbox"
        #else
        "production"
        #endif
    }

    /// Signed-in users register for remote notifications so the backend can
    /// send re-engagement pushes. The token itself needs no alert permission;
    /// display is governed by the (provisional) notification authorization.
    @MainActor static func registerIfSignedIn(_ sync: SyncService) {
        guard sync.isSignedIn else { return }
        UIApplication.shared.registerForRemoteNotifications()
    }
}
