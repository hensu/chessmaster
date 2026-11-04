// Chessmaster — GPL-3.0-or-later
import SwiftUI

/// Gold crown in the top-right of each tab for players without a plan;
/// tapping it opens the paywall.
private struct UpgradeCrown: ViewModifier {
    let source: String
    @Environment(DependencyContainer.self) private var container
    @State private var showPaywall = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                if !container.entitlements.isPremium {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            container.sync.track("upgrade_crown_tapped", ["source": source])
                            showPaywall = true
                        } label: {
                            Image(systemName: "crown.fill")
                                .foregroundStyle(Color(red: 0.961, green: 0.769, blue: 0.318))
                        }
                        .accessibilityLabel("Upgrade")
                    }
                }
            }
            .sheet(isPresented: $showPaywall) {
                AppPaywall(source: source)
            }
    }
}

extension View {
    func upgradeCrown(source: String) -> some View {
        modifier(UpgradeCrown(source: source))
    }
}
