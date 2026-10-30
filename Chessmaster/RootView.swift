// Chessmaster — GPL-3.0-or-later
import SwiftUI
import PaywallKit
import SupabaseSync

struct RootView: View {
    @State private var router = AppRouter()
    @Environment(DependencyContainer.self) private var container
    @AppStorage("appearance") private var appearance = "dark"
    /// Whether the onboarding cover is up. Decided once, when the session
    /// restore settles, so it survives sign-in mid-flow (signup → paywall)
    /// and only closes when onboarding actually finishes.
    @State private var welcomePresented = false
    @State private var welcomeDecided = false
    /// Snapshot at launch — the welcome only greets a user who hasn't finished
    /// the quiz; sign-in afterwards lives in Profile → Account.
    @State private var onboarded = UserDefaults.standard.bool(forKey: "onboarding.quizDone")
    /// Handles taps on local notifications (attribution + routing).
    @State private var notifications: NotificationCoordinator?
    /// Invite links: sign-in prompt, the host's own waiting invite, errors.
    @State private var inviteSignIn = false
    @State private var lobbyGame: FriendGameState?
    @State private var inviteError: String?

    /// Decide once (when the session restore settles) whether to show the
    /// onboarding cover. After that the cover's lifetime is owned by
    /// `welcomePresented`, so signing in mid-flow doesn't dismiss it.
    private func decideWelcome() {
        guard !welcomeDecided, container.sync.sessionRestored else { return }
        welcomeDecided = true
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--uitest") || args.contains(where: { $0.hasPrefix("--autostart") }) { return }
        if args.contains("--reset-welcome") { welcomePresented = true; return }
        // Arrived through a friend's invite: straight to the game, no quiz.
        if container.pendingInviteCode != nil { return }
        welcomePresented = !onboarded && !container.sync.isSignedIn && container.sync.isConfigured
    }

    /// Joins the game behind an invite link once the session is known;
    /// asks for sign-in first when needed.
    private func handleInvite() {
        guard let code = container.pendingInviteCode, container.sync.sessionRestored else { return }
        guard container.sync.isSignedIn else {
            welcomePresented = false
            inviteSignIn = true
            return
        }
        container.pendingInviteCode = nil
        inviteSignIn = false
        Task {
            do {
                let state = try await container.sync.joinFriendGame(code: code)
                container.sync.track("friend_invite_opened", ["status": state.status])
                if state.status == "active" {
                    container.startFriendGame(state)
                } else if state.status == "waiting" {
                    lobbyGame = state          // the host tapped their own link
                } else {
                    inviteError = "That game has already ended."
                }
            } catch let error as FriendGameError {
                inviteError = error.message
            } catch {
                inviteError = FriendGameError.other("").message
            }
        }
    }

    private var friendGamePresented: Binding<Bool> {
        Binding(
            get: { container.activeFriendGame != nil },
            set: { if !$0 { container.activeFriendGame = nil } })
    }

    private var colorScheme: ColorScheme? {
        switch appearance {
        case "dark": .dark
        case "light": .light
        default: nil
        }
    }

    var body: some View {
        TabView(selection: $router.selectedTab) {
            NavigationStack(path: $router.playPath) {
                HomeScreen()
            }
            .tabItem { Label("Play", systemImage: "checkerboard.rectangle") }
            .tag(AppTab.play)

            NavigationStack(path: $router.learnPath) {
                LearnScreen()
            }
            .tabItem { Label("Learn", systemImage: "puzzlepiece.extension") }
            .tag(AppTab.learn)

            NavigationStack(path: $router.historyPath) {
                HistoryScreen()
            }
            .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
            .tag(AppTab.history)

            NavigationStack(path: $router.profilePath) {
                ProfileScreen()
            }
            .tabItem { Label("Profile", systemImage: "person.crop.circle") }
            .tag(AppTab.profile)
        }
        .environment(router)
        .task {
            container.sync.userProperties["premium"] =
                container.entitlements.isPremium ? "true" : "false"
            container.sync.userProperties["plan"] = container.entitlements.plan.rawValue
            container.sync.track("app_open")
            // Remote feature flags / A/B variants; cached values gate until
            // the fetch lands.
            await container.sync.refreshFlags()
            // Streak reminders stay accurate even if the app wasn't opened
            // via a game (e.g. reinstall, day change).
            StreakNotifier.refresh(games: (try? container.games.recentGames()) ?? [])
            // Route notification taps, and (re)arm re-engagement nudges — a
            // launch means the user is back, so the cascade resets from now.
            if notifications == nil {
                notifications = NotificationCoordinator(router: router, sync: container.sync)
            }
            // Forward the APNs token to the backend, and register signed-in
            // users so the server re-engagement push can reach them.
            PushAppDelegate.onToken = { hex in
                Task {
                    await container.sync.registerDeviceToken(
                        hex, environment: Push.environment,
                        tz: TimeZone.current.identifier, locale: Locale.current.identifier)
                }
            }
            Push.registerIfSignedIn(container.sync)
            ReengagementNotifier.refresh(container: container)
            // UI tests: establish a session without the sign-in UI.
            // The fixed account is pre-provisioned (and pre-entitled) in the
            // backend, so live tests never depend on keychain persistence.
            // Live-test fixture sign-in: credentials come from the test
            // runner's launch environment, never from source (public repo).
            if ProcessInfo.processInfo.arguments.contains("--uitest-signin-test"),
               let email = ProcessInfo.processInfo.environment["UITEST_EMAIL"],
               let password = ProcessInfo.processInfo.environment["UITEST_PASSWORD"] {
                // A stale keychain session may belong to some other test
                // user; the fixture account must always win.
                await container.sync.signOut()
                await container.sync.signInWithPassword(email: email, password: password)
            }
            if ProcessInfo.processInfo.arguments.contains("--uitest-signin-anon"),
               !container.sync.isSignedIn {
                await container.sync.signInAnonymously()
            }
            // Reconcile a locally-known subscription with the backend, so a
            // purchase that never reached the server (offline, crash, old
            // build) heals on launch.
            await container.entitlements.refresh()
            container.sync.userProperties["premium"] =
                container.entitlements.isPremium ? "true" : "false"
            container.sync.userProperties["plan"] = container.entitlements.plan.rawValue
            if container.sync.isSignedIn,
               let jws = container.entitlements.latestTransactionJWS {
                await container.sync.verifySubscription(jws: jws)
            }
            decideWelcome()
        }
        .onChange(of: container.sync.sessionRestored) { _, _ in
            decideWelcome()
            handleInvite()
        }
        .onChange(of: container.pendingInviteCode) { _, _ in handleInvite() }
        // A purchase made before signing in (or under a previous account)
        // must attach to the account the moment it exists — launch-time
        // reconciliation alone misses sign-ins that happen mid-session.
        .onChange(of: container.entitlements.plan) { _, plan in
            container.sync.userProperties["premium"] = plan != .free ? "true" : "false"
            container.sync.userProperties["plan"] = plan.rawValue
        }
        .onChange(of: container.sync.isSignedIn) { _, signedIn in
            guard signedIn else { return }
            handleInvite()
            Push.registerIfSignedIn(container.sync)   // get a push token for the new session
            Task {
                await container.entitlements.refresh()
                if let jws = container.entitlements.latestTransactionJWS {
                    await container.sync.verifySubscription(jws: jws)
                }
            }
        }
        .fullScreenCover(isPresented: $welcomePresented) {
            WelcomeScreen { welcomePresented = false }
                .environment(container)
        }
        .fullScreenCover(isPresented: friendGamePresented) {
            if let link = container.activeFriendGame {
                NavigationStack {
                    GameScreen(friend: link)
                }
                .environment(container)
                .environment(container.learnProgress)
            }
        }
        .sheet(isPresented: $inviteSignIn) {
            AccountSheet(sync: container.sync,
                         subtitle: "A friend invited you to a game of chess. Sign in to join — it takes one tap.")
        }
        .sheet(item: $lobbyGame) { game in
            FriendLobbySheet(initial: game)
                .environment(container)
        }
        .alert("Couldn't join", isPresented: Binding(
            get: { inviteError != nil }, set: { if !$0 { inviteError = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(inviteError ?? "")
        }
        .preferredColorScheme(colorScheme)
    }
}
