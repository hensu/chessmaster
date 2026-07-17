// Chessmaster — GPL-3.0-or-later
import SwiftUI
import EngineKit
import GoogleSignIn

@main
struct ChessmasterApp: App {
    @State private var container = DependencyContainer()
    @Environment(\.scenePhase) private var scenePhase

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
            // Stockfish burns CPU; never leave a search running in background.
            if phase == .background {
                Task { await UCIEngine.shared.stopSearch() }
            }
        }
    }
}
