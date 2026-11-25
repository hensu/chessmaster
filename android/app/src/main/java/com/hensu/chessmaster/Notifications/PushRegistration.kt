// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** On iOS this receives the APNs device token and hands it to whoever set `onToken`.
 *  On Android the token is the Firebase Cloud Messaging registration token. */
object PushAppDelegate {
    /** Set once at launch (main thread) to forward the token to SyncService. */
    var onToken: ((String) -> Unit)? = null
}

object Push {
    /** Which push environment this build's tokens belong to. Debug builds are
     *  "sandbox"; release builds use production. (FCM has one host; the backend keeps
     *  the column for parity with iOS.) */
    val environment: String
        get() = if (BuildConfig.DEBUG) "sandbox" else "production"

    /** Starts Firebase from the BuildConfig identifiers (there is no google-services.json).
     *  Called from ChessAIApp.onCreate. */
    fun configure(context: Context) {
        if (FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build())
    }

    /** Signed-in users register for remote notifications so the backend can
     *  send friend-game and re-engagement pushes. The token arrives through
     *  `PushAppDelegate.onToken`. */
    fun registerIfSignedIn(sync: SyncService) {
        if (!sync.isSignedIn || App.arguments.contains("--uitest")) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                PushAppDelegate.onToken?.invoke(token)
            }
        }
    }
}

/**
 * Receives server pushes. The backend sends data-only messages (`title`, `body` and the same
 * payload keys as the iOS push: `friend_game` + `kind`, or `reason` + `tier`), so the
 * notification is built here and a tap reaches NotificationCoordinator like a local one.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Handler(Looper.getMainLooper()).post { PushAppDelegate.onToken?.invoke(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: return
        val userInfo = data.filterKeys { it != "title" && it != "body" }
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground && !NotificationCoordinator.willPresent(userInfo)) return
        val identifier = "push." + (userInfo["friend_game"] ?: userInfo["reason"] ?: message.messageId ?: "0")
        LocalNotifications.deliver(identifier, title, data["body"] ?: "", userInfo)
    }
}
