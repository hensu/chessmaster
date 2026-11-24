// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** The app entry point (ChessmasterApp.swift): launch flags, incoming links, foreground/background. */
object ChessmasterApp {
    private var observing = false
    private val scope = MainScope()

    /** `init()`: runs once per activity creation, before the first frame. */
    fun launched() {
        if (App.arguments.contains("--reset-welcome")) {
            for (key in listOf("welcome.completed", "onboarding.quizDone", "onboarding.selfRating", "onboarding.goal")) {
                Defaults.standard.remove(key)
            }
        }
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // GA engagement time / session boundaries follow the foreground.
            override fun onStart(owner: LifecycleOwner) {
                DependencyContainer.shared.sync.appBecameActive()
            }

            override fun onStop(owner: LifecycleOwner) {
                val container = DependencyContainer.shared
                container.sync.appResignedActive()
                // Stockfish burns CPU; never leave a search running in background.
                scope.launch { UCIEngine.shared.stopSearch() }
                // Leaving the app is the moment to (re)arm "come back" nudges,
                // measured from now, so they only fire if the user stays away.
                ReengagementNotifier.refresh(container)
            }
        })
    }

    /**
     * `.onOpenURL` / `.onContinueUserActivity` and notification taps: friend invite links
     * (https://getchessai.com/play/?g=CODE or chessmaster://play?g=CODE), the OAuth callback
     * (chessmaster://auth-callback), or a local notification's tap intent.
     */
    fun onOpenURL(intent: Intent?) {
        val container = DependencyContainer.shared
        NotificationCoordinator.handle(intent)
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (container.sync.handleOAuthCallback(url)) return
        val code = FriendInvite.code(url) ?: return
        if (container.pendingInviteCode == code) return
        container.sync.track("friend_link_opened", mapOf(
            "via" to if (url.startsWith("chessmaster:")) "web_page" else "link",
            "signed_in" to if (container.sync.isSignedIn) "true" else "false",
        ))
        container.pendingInviteCode = code
    }

    @Composable
    fun Content() {
        RootView()
    }
}
