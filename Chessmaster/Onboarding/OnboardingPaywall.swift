// Chessmaster — GPL-3.0-or-later
import SwiftUI
import StoreKit
import PaywallKit

/// Soft paywall shown once, right after sign-up in onboarding. Both tiers with
/// an Annual/Monthly toggle and a 7-day free trial; App Store payment only
/// (StoreKit). The close (X) fades in top-right after a few seconds so it's
/// dismissible — tapping it (or a successful purchase) continues into the app.
struct OnboardingPaywall: View {
    @Environment(DependencyContainer.self) private var container
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Called on dismiss (X) or a completed purchase — continues into the app.
    let onFinish: () -> Void

    @State private var products: [String: Product] = [:]
    @State private var isAnnual = true
    @State private var tier: Plan = .diamond   // default to the top tier
    @State private var purchasing = false
    @State private var showClose = false
    @State private var errorMessage: String?

    private let termsURL = URL(string: "https://getchessai.com/terms.html")!
    private let privacyURL = URL(string: "https://getchessai.com/privacy.html")!

    private func productID(_ tier: Plan, annual: Bool) -> String {
        switch tier {
        case .diamond: annual ? "com.chessmaster.diamond.yearly" : "com.chessmaster.diamond.monthly"
        default:       annual ? "com.chessmaster.premium.yearly" : "com.chessmaster.premium.monthly"
        }
    }
    private var selectedProduct: Product? { products[productID(tier, annual: isAnnual)] }
    /// Localized per-month equivalent of an annual price (StoreKit format style).
    private func perMonth(_ product: Product) -> String {
        (product.price / 12).formatted(product.priceFormatStyle)
    }
    private var hasTrial: Bool {
        selectedProduct?.subscription?.introductoryOffer?.paymentMode == .freeTrial
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color(.systemBackground).ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    header
                    periodToggle
                    tierCard(.platinum, title: "Platinum", blurb: "AI coaching notes on every game")
                    tierCard(.diamond, title: "Diamond", blurb: "Deep analysis + your cross-game player review", tag: "BEST VALUE")
                    if let errorMessage {
                        Text(errorMessage).font(.footnote).foregroundStyle(.red)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 72)
                .padding(.bottom, 12)
            }

            if showClose {
                Button { onFinish() } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .background(Color(.secondarySystemBackground), in: Circle())
                }
                .padding(.top, 12)
                .padding(.trailing, 16)
                .transition(.opacity)
                .accessibilityIdentifier("paywallClose")
            }
        }
        .safeAreaInset(edge: .bottom) { purchaseBar }
        .preferredColorScheme(.light)   // Cal AI-style, matches the light onboarding
        .interactiveDismissDisabled()
        .task {
            await loadProducts()
            scheduleClose()
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "sparkles")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(.green)
            Text("Unlock your AI chess coach")
                .font(.system(size: 30, weight: .bold))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Period toggle

    private var periodToggle: some View {
        HStack(spacing: 8) {
            periodPill(title: "Annual", subtitle: "7-day free trial", annual: true)
            periodPill(title: "Monthly", subtitle: nil, annual: false)
        }
    }

    private func periodPill(title: String, subtitle: String?, annual: Bool) -> some View {
        let selected = isAnnual == annual
        return Button {
            withAnimation(.easeInOut(duration: 0.15)) { isAnnual = annual }
        } label: {
            VStack(spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                if let subtitle { Text(subtitle).font(.caption2).foregroundStyle(selected ? .white.opacity(0.9) : .secondary) }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(selected ? Color.primary : Color(.secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(selected ? Color(.systemBackground) : .primary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Tier card

    @ViewBuilder
    private func tierCard(_ cardTier: Plan, title: String, blurb: String, tag: String? = nil) -> some View {
        let selected = tier == cardTier
        let product = products[productID(cardTier, annual: isAnnual)]
        Button {
            withAnimation(.easeInOut(duration: 0.15)) { tier = cardTier }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? .green : Color(.tertiaryLabel))
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(title).font(.headline)
                        if let tag {
                            Text(tag).font(.caption2.weight(.bold))
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(.green, in: Capsule()).foregroundStyle(.white)
                        }
                    }
                    Text(blurb).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                // The billed amount is the dominant price (App Store 3.1.2(c));
                // the per-month equivalent stays subordinate in size and color.
                VStack(alignment: .trailing, spacing: 1) {
                    Text(product?.displayPrice ?? "—")
                        .font(.title3.weight(.bold).monospacedDigit())
                    Text(isAnnual ? "per year" : "per month")
                        .font(.caption2).foregroundStyle(.secondary)
                    if isAnnual, let product {
                        Text("≈ \(perMonth(product))/mo")
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            }
            .padding(16)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(selected ? Color.green : .clear, lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Purchase bar

    private var purchaseBar: some View {
        VStack(spacing: 8) {
            if hasTrial {
                Label("No payment due now", systemImage: "checkmark")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
            }
            Button(action: buy) {
                Group {
                    if purchasing { ProgressView().tint(Color(.systemBackground)) }
                    else { Text(hasTrial ? "Start 7-day free trial" : "Subscribe").font(.headline) }
                }
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(Color.primary, in: Capsule())
                .foregroundStyle(Color(.systemBackground))
            }
            .buttonStyle(.plain)
            .disabled(purchasing || selectedProduct == nil)
            .opacity(selectedProduct == nil ? 0.5 : 1)

            Text(finePrint)
                .font(.caption2).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack(spacing: 6) {
                Link("Terms", destination: termsURL)
                Text("·").foregroundStyle(.tertiary)
                Link("Privacy", destination: privacyURL)
                Text("·").foregroundStyle(.tertiary)
                Button("Restore", action: restore)
            }
            .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 24).padding(.top, 12).padding(.bottom, 8)
        .background(Color(.systemBackground))   // solid, not material (material vibrancy greys the CTA)
    }

    private var finePrint: String {
        guard let p = selectedProduct else { return "Payment through the App Store." }
        let period = isAnnual ? "year" : "month"
        let base = "\(p.displayPrice) per \(period), billed \(isAnnual ? "annually" : "monthly"). Auto-renews unless cancelled in the App Store."
        return hasTrial ? "7 days free, then \(base)" : base
    }

    // MARK: - Actions

    private func loadProducts() async {
        guard products.isEmpty else { return }
        if let loaded = try? await Product.products(for: EntitlementStore.productIDs) {
            products = Dictionary(uniqueKeysWithValues: loaded.map { ($0.id, $0) })
        }
    }

    private func scheduleClose() {
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(6))
            if reduceMotion { showClose = true }
            else { withAnimation(.easeIn(duration: 0.4)) { showClose = true } }
        }
    }

    private func buy() {
        guard let product = selectedProduct, !purchasing else { return }
        purchasing = true
        errorMessage = nil
        Task { @MainActor in
            defer { purchasing = false }
            do {
                let ok = try await container.entitlements.purchase(
                    product, appAccountToken: container.sync.userID)
                if ok {
                    container.sync.track("purchase_verified", ["source": "onboarding"])
                    if let jws = container.entitlements.latestTransactionJWS {
                        await container.sync.verifySubscription(jws: jws)
                    }
                    onFinish()
                } else {
                    container.sync.track("purchase_cancelled", ["source": "onboarding"])
                }
            } catch {
                errorMessage = "Purchase couldn't be completed. Please try again."
            }
        }
    }

    private func restore() {
        Task { @MainActor in
            await container.entitlements.restore()
            if container.entitlements.isPremium { onFinish() }
        }
    }
}
