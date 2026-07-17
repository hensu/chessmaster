// Chessmaster — GPL-3.0-or-later
import SwiftUI

enum AppTab: Hashable {
    case play, learn, history, profile
}

@Observable @MainActor
final class AppRouter {
    var selectedTab: AppTab = .play
    var playPath = NavigationPath()
    var learnPath = NavigationPath()
    var historyPath = NavigationPath()
    var profilePath = NavigationPath()
}
