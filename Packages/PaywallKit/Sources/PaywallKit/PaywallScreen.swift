// PaywallKit — Chessmaster
// GPL-3.0-or-later

import StoreKit
import SwiftUI

/// Premium marketing + purchase sheet (SubscriptionStoreView, iOS 17).
public struct PaywallScreen: View {
    let entitlements: EntitlementStore
    let appAccountToken: UUID?
    /// Called with the signed transaction after a successful purchase so the
    /// app can register the entitlement server-side.
    let onPurchaseVerify: ((String) async -> Void)?
    /// Called when the player backs out of the App Store purchase sheet.
    let onPurchaseCancelled: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    public init(
        entitlements: EntitlementStore,
        appAccountToken: UUID? = nil,
        onPurchaseVerify: ((String) async -> Void)? = nil,
        onPurchaseCancelled: (() -> Void)? = nil
    ) {
        self.entitlements = entitlements
        self.appAccountToken = appAccountToken
        self.onPurchaseVerify = onPurchaseVerify
        self.onPurchaseCancelled = onPurchaseCancelled
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Header renders unconditionally (SubscriptionStoreView's own
            // marketing content only appears once products load).
            VStack(spacing: 10) {
                Image(systemName: "crown.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(.yellow)
                Text("Choose your coach")
                    .font(.title2.bold())
                VStack(alignment: .leading, spacing: 6) {
                    tierRow("PLATINUM", color: .gray, [
                        "Unlimited Game Review with coach notes",
                        "Retry training + auto insights",
                        "5 advanced AI deep reviews every month",
                    ])
                    tierRow("DIAMOND", color: .blue, [
                        "Every review by our most advanced AI",
                        "Coach's Overview: your cross-game assessment",
                        "Highest limits, first access to new features",
                    ])
                }
                .padding(.top, 4)
            }
            .padding()

            SubscriptionStoreView(productIDs: EntitlementStore.productIDs)
                .storeButton(.visible, for: .restorePurchases)
                .subscriptionStoreControlStyle(.prominentPicker)
                .inAppPurchaseOptions { _ in
                    appAccountToken.map { [.appAccountToken($0)] } ?? []
                }
                .onInAppPurchaseCompletion { _, result in
                    if case .success(.success(let verification)) = result {
                        await entitlements.refresh()
                        await onPurchaseVerify?(verification.jwsRepresentation)
                        dismiss()
                    } else if case .success(.userCancelled) = result {
                        onPurchaseCancelled?()
                    }
                }
        }
    }

    private func feature(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .frame(width: 22)
                .foregroundStyle(.tint)
            Text(text)
                .font(.subheadline)
        }
    }

    private func tierRow(_ name: String, color: Color, _ features: [String]) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(name)
                .font(.caption2.bold())
                .kerning(0.8)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(color.opacity(0.18), in: Capsule())
                .foregroundStyle(color)
            ForEach(features, id: \.self) { text in
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "checkmark")
                        .font(.caption2.bold())
                        .foregroundStyle(color)
                        .padding(.top, 3)
                    Text(text)
                        .font(.footnote)
                }
            }
        }
        .padding(.vertical, 3)
    }
}
