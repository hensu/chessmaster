// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

/** On iOS this receives the APNs device token and hands it to whoever set `onToken`.
 *  Android has no server push yet (the push SDK is closed-source and this app is GPL), so
 *  no token is ever delivered: `onToken` is kept for the callers but never invoked. */
object PushAppDelegate {
    /** Set once at launch (main thread) to forward the token to SyncService. */
    var onToken: ((String) -> Unit)? = null
}

object Push {
    /** Which push environment this build's tokens belong to. Debug builds are
     *  "sandbox"; release builds use production. The backend sends to the matching host. */
    val environment: String
        get() = if (BuildConfig.DEBUG) "sandbox" else "production"

    /** Signed-in users register for remote notifications so the backend can
     *  send re-engagement pushes. A no-op on Android: without a push service there is no
     *  token to register, `sync.hasRemoteToken` stays false, and the local re-engagement
     *  notifications keep running. */
    @Suppress("UNUSED_PARAMETER")
    fun registerIfSignedIn(sync: SyncService) {
        if (!sync.isSignedIn) return
    }
}
