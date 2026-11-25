// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@Composable
fun RootView() {
    val router = AppRouter.shared
    val container = DependencyContainer.shared
    val scope = rememberCoroutineScope()
    val appearance by appStorageString("appearance", "dark")
    /**
     * Whether the onboarding cover is up. Decided once, when the session
     * restore settles, so it survives sign-in mid-flow (signup → paywall)
     * and only closes when onboarding actually finishes.
     */
    var welcomePresented by remember { mutableStateOf(false) }
    var welcomeDecided by remember { mutableStateOf(false) }
    /**
     * Snapshot at launch — the welcome only greets a user who hasn't finished
     * the quiz; sign-in afterwards lives in Profile → Account.
     */
    val onboarded = remember { Defaults.standard.bool("onboarding.quizDone") }
    /** Invite links: sign-in prompt, the host's own waiting invite, errors. */
    var inviteSignIn by remember { mutableStateOf(false) }
    var lobbyGame by remember { mutableStateOf<FriendGameState?>(null) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    /** Invitee has no name yet: ask before joining so the host sees it. */
    var inviteNamePrompt by remember { mutableStateOf(false) }
    var inviteNameAsked by remember { mutableStateOf(false) }
    /** Screenshots: --preview-paywall opens the upgrade sheet at launch. */
    var previewPaywall by remember { mutableStateOf(BuildConfig.DEBUG && App.arguments.contains("--preview-paywall")) }
    /**
     * --preview-daily opens today's daily puzzle; --preview-review opens the
     * latest analyzed game (share card button).
     */
    var previewDaily by remember { mutableStateOf(BuildConfig.DEBUG && App.arguments.contains("--preview-daily")) }
    var previewReview by remember { mutableStateOf(BuildConfig.DEBUG && App.arguments.contains("--preview-review")) }

    // .preferredColorScheme(colorScheme)
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    // The onboarding cover is always light (.preferredColorScheme(.light) on WelcomeScreen).
    val effectiveDark = dark && !welcomePresented
    Sys.dark = effectiveDark
    val view = LocalView.current
    SideEffect {
        val window = MainActivity.current?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !effectiveDark
        controller.isAppearanceLightNavigationBars = !effectiveDark
    }

    /**
     * Decide once (when the session restore settles) whether to show the
     * onboarding cover. After that the cover's lifetime is owned by
     * `welcomePresented`, so signing in mid-flow doesn't dismiss it.
     */
    fun decideWelcome() {
        if (welcomeDecided || !container.sync.sessionRestored) return
        welcomeDecided = true
        val args = App.arguments
        if (args.contains("--uitest") || args.any { it.startsWith("--autostart") }) return
        if (args.contains("--reset-welcome")) { welcomePresented = true; return }
        // Arrived through a friend's invite: straight to the game, no quiz.
        if (container.pendingInviteCode != null) return
        welcomePresented = !onboarded && !container.sync.isSignedIn && container.sync.isConfigured
    }

    /**
     * Joins the game behind an invite link once the session is known;
     * asks for sign-in first when needed.
     */
    fun handleInvite() {
        val code = container.pendingInviteCode ?: return
        if (!container.sync.sessionRestored) return
        if (!container.sync.isSignedIn) {
            welcomePresented = false
            if (!inviteSignIn) container.sync.track("friend_signup_prompted")
            inviteSignIn = true
            return
        }
        if (container.sync.displayName == null && !inviteNameAsked) {
            inviteNameAsked = true
            val fromSignIn = inviteSignIn
            inviteSignIn = false
            scope.launch {
                // Let the sign-in sheet finish dismissing first.
                if (fromSignIn) delay(600)
                inviteNamePrompt = true
            }
            return
        }
        container.pendingInviteCode = null
        inviteSignIn = false
        scope.launch {
            // Friend games tell you when it's time to play (pushes).
            NotificationPermission.requestFullIfNeeded()
            try {
                val state = container.sync.joinFriendGame(code)
                container.sync.track("friend_invite_opened", mapOf("status" to state.status))
                when (state.status) {
                    "active" -> container.startFriendGame(state)
                    "waiting" -> lobbyGame = state          // the host tapped their own link
                    else -> inviteError = "That game has already ended."
                }
            } catch (error: FriendGameError) {
                inviteError = error.message
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                inviteError = FriendGameError.Other("").message
            }
        }
    }

    fun syncPlanProperties() {
        container.sync.userProperties["premium"] = if (container.entitlements.isPremium) "true" else "false"
        container.sync.userProperties["plan"] = container.entitlements.plan.rawValue
    }

    Column(Modifier.fillMaxSize().background(Sys.groupedBackground)) {
        val holder = rememberSaveableStateHolder()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            holder.SaveableStateProvider(router.selectedTab.name) {
                when (router.selectedTab) {
                    AppTab.play -> RootTab(router.playNav, "crown_play") { HomeScreen() }
                    AppTab.learn -> RootTab(router.learnNav, "crown_learn") { LearnScreen() }
                    AppTab.history -> RootTab(router.historyNav, "crown_history") { HistoryScreen() }
                    AppTab.profile -> RootTab(router.profileNav, "crown_profile") { ProfileScreen() }
                }
            }
        }
        RootTabBar(router)
    }

    LaunchedEffect(Unit) {
        syncPlanProperties()
        container.sync.track("app_open")
        // Remote feature flags / A/B variants; cached values gate until
        // the fetch lands.
        container.sync.refreshFlags()
        // Streak reminders stay accurate even if the app wasn't opened
        // via a game (e.g. reinstall, day change).
        StreakNotifier.refresh(runCatching { container.games.recentGames() }.getOrNull() ?: emptyList())
        // Forward the push token to the backend, and register signed-in
        // users so friend-game and re-engagement pushes can reach them.
        PushAppDelegate.onToken = { token ->
            MainScope().launch {
                container.sync.registerDeviceToken(
                    token, Push.environment,
                    java.util.TimeZone.getDefault().id, java.util.Locale.getDefault().toString())
            }
        }
        Push.registerIfSignedIn(container.sync)
        // (Re)arm re-engagement nudges — a launch means the user is back, so
        // the cascade resets from now. Notification taps are routed from
        // ChessmasterApp.onOpenURL.
        ReengagementNotifier.refresh(container)
        // UI tests: establish a session without the sign-in UI.
        if (App.arguments.contains("--uitest-signin-anon") && !container.sync.isSignedIn) {
            container.sync.signInAnonymously()
        }
        // Reconcile a locally-known subscription with the backend, so a
        // purchase that never reached the server (offline, crash, old
        // build) heals on launch. (EntitlementStore.refresh re-verifies owned purchases.)
        container.entitlements.refresh()
        syncPlanProperties()
        decideWelcome()
    }
    LaunchedEffect(container.sync.sessionRestored) {
        decideWelcome()
        handleInvite()
    }
    LaunchedEffect(container.pendingInviteCode) { handleInvite() }
    // A purchase made before signing in (or under a previous account)
    // must attach to the account the moment it exists — launch-time
    // reconciliation alone misses sign-ins that happen mid-session.
    LaunchedEffect(container.entitlements.plan) { syncPlanProperties() }
    LaunchedEffect(container.sync.isSignedIn) {
        if (!container.sync.isSignedIn) return@LaunchedEffect
        // Signed up / in to accept a friend's invite.
        if (container.pendingInviteCode != null) container.sync.track("friend_signup_completed")
        handleInvite()
        Push.registerIfSignedIn(container.sync)   // get a push token for the new session
        container.entitlements.refresh()
    }

    if (welcomePresented) FullScreenCover(onDismiss = { welcomePresented = false }) {
        WelcomeScreen { welcomePresented = false }
    }
    container.activeFriendGame?.let { link ->
        FullScreenCover(onDismiss = { container.activeFriendGame = null }) {
            NavStack { GameScreen(friend = link) }
        }
    }
    if (inviteSignIn) Sheet(onDismiss = { inviteSignIn = false }) {
        AccountSheet(container.sync,
                     subtitle = "A friend invited you to a game of chess. Sign in to join — it takes one tap.",
                     onDismiss = { inviteSignIn = false })
    }
    if (previewPaywall) Sheet(onDismiss = { previewPaywall = false }) {
        AppPaywall(source = "debug_preview", onClose = { previewPaywall = false })
    }
    if (previewDaily) {
        val puzzle = remember { DailyPuzzle.puzzle() }
        if (puzzle != null) Sheet(onDismiss = { previewDaily = false }) {
            NavStack { PuzzleScreen(daily = puzzle) }
        }
    }
    if (previewReview) {
        val game = remember {
            runCatching { container.games.recentGames(50) }.getOrNull()?.firstOrNull { it.analysisJSON != null }
        }
        if (game != null) Sheet(onDismiss = { previewReview = false }) {
            NavStack { AnalysisScreen(game, source = "debug_preview") }
        }
    }
    if (inviteNamePrompt) Sheet(onDismiss = {}, dismissable = false) {
        NameScreen(source = "invite") {
            inviteNamePrompt = false
            handleInvite()
        }
    }
    lobbyGame?.let { game ->
        Sheet(onDismiss = { lobbyGame = null }) {
            FriendLobbySheet(initial = game, onDismiss = { lobbyGame = null })
        }
    }
    if (inviteError != null) {
        Alert("Couldn't join", inviteError ?: "", confirm = "OK", cancel = null, onDismiss = { inviteError = null })
    }
}

/** One tab: its own navigation stack, with the upgrade crown on the root screen's toolbar. */
@Composable
private fun RootTab(navigator: Navigator, crownSource: String, root: @Composable () -> Unit) {
    CompositionLocalProvider(LocalToolbarExtras provides { UpgradeCrown(crownSource) }) {
        NavStack(navigator) { root() }
    }
}

/** The TabView's bar: Play, Learn, History, Profile. Tapping the selected tab pops it to its root. */
@Composable
private fun RootTabBar(router: AppRouter) {
    Column(Modifier.fillMaxWidth().background(Sys.secondaryGroupedBackground)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Sys.separator))
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(56.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
        ) {
            RootTabItem(router, AppTab.play, "Play", Icons.Rounded.GridOn, router.playNav)
            RootTabItem(router, AppTab.learn, "Learn", Icons.Rounded.Extension, router.learnNav)
            RootTabItem(router, AppTab.history, "History", Icons.Rounded.History, router.historyNav)
            RootTabItem(router, AppTab.profile, "Profile", Icons.Rounded.AccountCircle, router.profileNav)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RootTabItem(
    router: AppRouter, tab: AppTab, label: String, icon: ImageVector, navigator: Navigator,
) {
    val selected = router.selectedTab == tab
    val tint = if (selected) Sys.green else Sys.gray
    Column(
        Modifier.weight(1f).testTag("tab_$label")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (selected) navigator.popToRoot() else router.selectedTab = tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, style = Sys.caption2, color = tint)
    }
}
