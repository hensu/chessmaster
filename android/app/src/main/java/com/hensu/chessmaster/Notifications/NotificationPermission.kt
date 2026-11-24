// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object NotificationPermission {
    /**
     * Ask once for full (loud) notification permission — banner, sound, badge.
     * Call at a high-intent moment (after a win); it no-ops if the user has
     * already been asked. This is the chess.com-style ask that makes streak
     * and re-engagement notifications actually visible.
     */
    suspend fun requestFullIfNeeded() {
        if (LocalNotifications.status() != LocalNotifications.Status.notDetermined) return
        suspendCancellableCoroutine { continuation ->
            LocalNotifications.requestAuthorization { if (continuation.isActive) continuation.resume(Unit) }
        }
    }
}
