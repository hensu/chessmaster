// Chessmaster — GPL-3.0-or-later
import SwiftUI
import PaywallKit

/// The paywall wired to this app's account + backend: purchases carry the
/// Supabase user UUID and register server-side immediately.
struct AppPaywall: View {
    /// Which surface raised the paywall ("profile_upgrade", "game_insights",
    /// "coaching", "training", "player_review") — funnel attribution.
    let source: String
    @Environment(DependencyContainer.self) private var container

    var body: some View {
        PaywallScreen(
            entitlements: container.entitlements,
            appAccountToken: container.sync.userID,
            onPurchaseVerify: { jws in
                container.sync.track("purchase_verified", ["source": source])
                await container.sync.verifySubscription(jws: jws)
            },
            onPurchaseCancelled: {
                container.sync.track("purchase_cancelled", ["source": source])
            }
        )
        .onAppear { container.sync.track("paywall_viewed", ["source": source]) }
        .onDisappear {
            // A successful purchase dismisses the sheet too; only count
            // walk-aways.
            if !container.entitlements.isPremium {
                container.sync.track("paywall_dismissed", ["source": source])
            }
        }
    }
}
