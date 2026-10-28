// Chessmaster — GPL-3.0-or-later
import SwiftUI

extension View {
    /// Sends a GA screen view each time this screen appears — feeds the
    /// "Pages and screens" report (see SyncService.trackScreen).
    func trackScreen(_ name: String) -> some View {
        modifier(ScreenTracker(name: name))
    }
}

private struct ScreenTracker: ViewModifier {
    let name: String
    @Environment(DependencyContainer.self) private var container

    func body(content: Content) -> some View {
        content.onAppear { container.sync.trackScreen(name) }
    }
}
