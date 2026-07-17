// Chessmaster — GPL-3.0-or-later
import SwiftUI
import PaywallKit

struct RootView: View {
    @State private var router = AppRouter()
    @Environment(DependencyContainer.self) private var container
    @AppStorage("appearance") private var appearance = "dark"
    /// Dismisses the welcome flow for this session once completed or skipped.
    @State private var skippedSignIn = false
    /// Decided once at launch so mid-flow writes to `onboarding.quizDone`
    /// can't dismiss the cover early. The welcome flow only ever greets a
    /// user who hasn't finished the quiz; sign-in afterwards lives in
    /// Profile → Account.
    @State private var onboarded = UserDefaults.standard.bool(forKey: "onboarding.quizDone")

    private var showWelcome: Bool {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--reset-welcome") { return !skippedSignIn }
        if arguments.contains("--uitest") { return false }
        if arguments.contains(where: { $0.hasPrefix("--autostart") }) { return false }
        return !onboarded && !skippedSignIn && !container.sync.isSignedIn && container.sync.isConfigured
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
        }
        // A purchase made before signing in (or under a previous account)
        // must attach to the account the moment it exists — launch-time
        // reconciliation alone misses sign-ins that happen mid-session.
        .onChange(of: container.entitlements.plan) { _, plan in
            container.sync.userProperties["premium"] = plan != .free ? "true" : "false"
            container.sync.userProperties["plan"] = plan.rawValue
        }
        .onChange(of: container.sync.isSignedIn) { _, signedIn in
            guard signedIn else { return }
            Task {
                await container.entitlements.refresh()
                if let jws = container.entitlements.latestTransactionJWS {
                    await container.sync.verifySubscription(jws: jws)
                }
            }
        }
        .fullScreenCover(isPresented: .constant(showWelcome)) {
            WelcomeScreen {
                skippedSignIn = true
            }
            .environment(container)
        }
        .preferredColorScheme(colorScheme)
    }
}
