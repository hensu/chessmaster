// PaywallKit — Chessmaster
// GPL-3.0-or-later

import StoreKit
import SwiftUI

/// Upgrade sheet: crown, Yearly/Monthly toggle (Yearly default), Diamond and
/// Platinum cards with their features (Diamond pre-selected), one CTA.
/// Everything fits above the fold. The billed amount is always the dominant
/// price; the per-month equivalent stays subordinate (App Store 3.1.2(c)).
public struct PaywallScreen: View {
    let entitlements: EntitlementStore
    let appAccountToken: UUID?
    /// Called with the signed transaction after a successful purchase so the
    /// app can register the entitlement server-side.
    let onPurchaseVerify: ((String) async -> Void)?
    /// Called when the player backs out of the App Store purchase sheet.
    let onPurchaseCancelled: (() -> Void)?
    @Environment(\.dismiss) private var dismiss

    @State private var products: [String: Product] = [:]
    @State private var trialEligible = false
    @State private var isAnnual = true
    @State private var tier: Plan = .diamond
    @State private var purchasing = false
    @State private var errorMessage: String?

    private static let diamondBlue = Color(red: 0.24, green: 0.55, blue: 0.99)
    private static let crownGold = Color(red: 0.961, green: 0.769, blue: 0.318)
    private let termsURL = URL(string: "https://getchessai.com/terms.html")!
    private let privacyURL = URL(string: "https://getchessai.com/privacy.html")!

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

    private func productID(_ tier: Plan, annual: Bool) -> String {
        switch tier {
        case .diamond: annual ? "com.chessmaster.diamond.yearly" : "com.chessmaster.diamond.monthly"
        default:       annual ? "com.chessmaster.premium.yearly" : "com.chessmaster.premium.monthly"
        }
    }
    private var selectedProduct: Product? { products[productID(tier, annual: isAnnual)] }
    private var hasTrial: Bool {
        trialEligible && selectedProduct?.subscription?.introductoryOffer?.paymentMode == .freeTrial
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .background(Color(.secondarySystemBackground), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)

            ScrollView {
                VStack(spacing: 16) {
                    Image(systemName: "crown.fill")
                        .font(.system(size: 40))
                        .foregroundStyle(Self.crownGold)
                    Text("Upgrade your coach")
                        .font(.title.bold())
                    periodToggle
                    tierCard(.diamond, title: "Diamond", badge: "MOST POPULAR", features: [
                        "Every review by our most advanced AI",
                        "Coach's Overview across all your games",
                        "Highest limits, new features first",
                    ])
                    .padding(.top, 6)
                    tierCard(.platinum, title: "Platinum", badge: nil, features: [
                        "Unlimited AI game reviews",
                        "Retry training + auto insights",
                        "5 advanced deep reviews a month",
                    ])
                    if let errorMessage {
                        Text(errorMessage).font(.footnote).foregroundStyle(.red)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
            }
            .scrollBounceBehavior(.basedOnSize)

            purchaseBar
        }
        .task { await loadProducts() }
    }

    // MARK: - Toggle

    private var periodToggle: some View {
        HStack(spacing: 0) {
            periodSegment(annual: true) {
                HStack(spacing: 4) {
                    Text("Yearly")
                    Text("Save 49%").font(.caption.weight(.semibold)).foregroundStyle(.green)
                }
            }
            periodSegment(annual: false) { Text("Monthly") }
        }
        .padding(4)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func periodSegment<Label: View>(annual: Bool, @ViewBuilder label: () -> Label) -> some View {
        let selected = isAnnual == annual
        return Button {
            withAnimation(.easeInOut(duration: 0.15)) { isAnnual = annual }
        } label: {
            label()
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(selected ? .primary : .secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .background(selected ? Color(.tertiarySystemFill) : .clear,
                            in: RoundedRectangle(cornerRadius: 9))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Plan card

    private func tierCard(_ cardTier: Plan, title: String, badge: String?, features: [String]) -> some View {
        let selected = tier == cardTier
        let product = products[productID(cardTier, annual: isAnnual)]
        return Button {
            withAnimation(.easeInOut(duration: 0.15)) { tier = cardTier }
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title).font(.title3.weight(.heavy))
                    Spacer()
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(product.map { "\($0.displayPrice)/\(isAnnual ? "year" : "month")" } ?? "—")
                            .font(.title3.weight(.heavy).monospacedDigit())
                        Text(isAnnual ? product.map { "\(($0.price / 12).formatted($0.priceFormatStyle))/mo" } ?? ""
                                      : "billed monthly")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(features, id: \.self) { text in
                        HStack(alignment: .top, spacing: 6) {
                            Image(systemName: "checkmark")
                                .font(.caption.bold())
                                .foregroundStyle(selected ? Self.diamondBlue : .secondary)
                                .padding(.top, 2)
                            Text(text).font(.subheadline)
                        }
                    }
                }
                .foregroundStyle(selected ? .primary : .secondary)
            }
            .multilineTextAlignment(.leading)
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 18)
                    .fill(selected ? Self.diamondBlue.opacity(0.12) : Color(.secondarySystemBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .strokeBorder(selected ? Self.diamondBlue : Color(.separator), lineWidth: selected ? 2 : 1)
            )
            .overlay(alignment: .topLeading) {
                if let badge {
                    Text(badge)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(Self.diamondBlue, in: RoundedRectangle(cornerRadius: 7))
                        .offset(x: 16, y: -10)
                }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - Purchase bar

    private var purchaseBar: some View {
        VStack(spacing: 8) {
            Button(action: buy) {
                Group {
                    if purchasing { ProgressView().tint(.white) }
                    else { Text(ctaTitle).font(.headline) }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(Self.diamondBlue, in: Capsule())
                .foregroundStyle(.white)
            }
            .buttonStyle(.plain)
            .disabled(purchasing || selectedProduct == nil)
            .opacity(selectedProduct == nil ? 0.5 : 1)

            Text(finePrint)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            // Required in the purchase flow (App Store Guideline 3.1.2):
            // functional Terms of Use (EULA) + Privacy Policy links.
            HStack(spacing: 6) {
                Button("Restore", action: restore)
                Text("·").foregroundStyle(.tertiary)
                Link("Terms", destination: termsURL)
                Text("·").foregroundStyle(.tertiary)
                Link("Privacy", destination: privacyURL)
            }
            .font(.caption)
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private var ctaTitle: String {
        if hasTrial { return "Start 7-day free trial" }
        return tier == .diamond ? "Continue with Diamond" : "Continue with Platinum"
    }

    private var finePrint: String {
        guard let p = selectedProduct else { return "Payment through the App Store." }
        let billed = "\(p.displayPrice) billed \(isAnnual ? "yearly" : "monthly")"
        return hasTrial
            ? "7 days free, then \(billed). Cancel anytime."
            : "\(billed). Auto-renews, cancel anytime in Settings."
    }

    // MARK: - Actions

    private func loadProducts() async {
        guard products.isEmpty else { return }
        if let loaded = try? await Product.products(for: EntitlementStore.productIDs) {
            products = Dictionary(uniqueKeysWithValues: loaded.map { ($0.id, $0) })
            // Returning subscribers aren't eligible — don't promise a trial.
            if let sub = products["com.chessmaster.diamond.yearly"]?.subscription {
                trialEligible = await sub.isEligibleForIntroOffer
            }
        }
    }

    private func buy() {
        guard let product = selectedProduct, !purchasing else { return }
        purchasing = true
        errorMessage = nil
        Task { @MainActor in
            defer { purchasing = false }
            do {
                if try await entitlements.purchase(product, appAccountToken: appAccountToken) {
                    if let jws = entitlements.latestTransactionJWS {
                        await onPurchaseVerify?(jws)
                    }
                    dismiss()
                } else {
                    onPurchaseCancelled?()
                }
            } catch {
                errorMessage = "Purchase couldn't be completed. Please try again."
            }
        }
    }

    private func restore() {
        Task { @MainActor in
            await entitlements.restore()
            if entitlements.isPremium { dismiss() }
        }
    }
}
