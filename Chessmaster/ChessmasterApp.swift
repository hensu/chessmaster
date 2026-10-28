// Chessmaster — GPL-3.0-or-later
import SwiftUI
import EngineKit
import GoogleSignIn

@main
struct ChessmasterApp: App {
    @State private var container = DependencyContainer()
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate

    init() {
        if ProcessInfo.processInfo.arguments.contains("--reset-welcome") {
            for key in ["welcome.completed", "onboarding.quizDone",
                        "onboarding.selfRating", "onboarding.goal"] {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container)
                .environment(container.learnProgress)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // GA engagement time / session boundaries follow the foreground.
            if phase == .active { container.sync.appBecameActive() }
            if phase == .background { container.sync.appResignedActive() }
            // Stockfish burns CPU; never leave a search running in background.
            if phase == .background {
                Task { await UCIEngine.shared.stopSearch() }
                // Leaving the app is the moment to (re)arm "come back" nudges,
                // measured from now, so they only fire if the user stays away.
                ReengagementNotifier.refresh(container: container)
            }
        }
    }
}
