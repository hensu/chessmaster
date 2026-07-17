// PaywallKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Observation
import StoreKit

/// The subscription ladder. Platinum keeps the historical "premium"
/// product IDs so existing subscribers grandfather in unchanged.
public enum Plan: String, Sendable, Comparable {
    case free, platinum, diamond

    private var rank: Int {
        switch self { case .free: 0; case .platinum: 1; case .diamond: 2 }
    }

    public static func < (lhs: Plan, rhs: Plan) -> Bool { lhs.rank < rhs.rank }

    public static func plan(forProductID id: String) -> Plan {
        if EntitlementStore.diamondIDs.contains(id) { return .diamond }
        if EntitlementStore.platinumIDs.contains(id) { return .platinum }
        return .free
    }
}

/// Single source of truth for subscription status. All gating in the app
/// reads `plan`/`isPremium`; server features additionally verify server-side.
@Observable @MainActor
public final class EntitlementStore {
    public nonisolated static let platinumIDs = [
        "com.chessmaster.premium.monthly", "com.chessmaster.premium.yearly",
    ]
    public nonisolated static let diamondIDs = [
        "com.chessmaster.diamond.monthly", "com.chessmaster.diamond.yearly",
    ]
    public nonisolated static let productIDs = diamondIDs + platinumIDs

    /// The active tier. `isPremium` means "any paid plan".
    public private(set) var plan: Plan = .free
    public var isPremium: Bool { plan != .free }
    /// The latest transaction JWS, for server verification after purchase.
    public private(set) var latestTransactionJWS: String?

    private var updatesTask: Task<Void, Never>?

    public init() {
        applyDebugOverride()
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                await self?.handle(update)
            }
        }
        Task { [weak self] in
            await self?.refresh()
        }
    }

    /// Re-reads current entitlements (offline-friendly: StoreKit caches).
    public func refresh() async {
        var best = Plan.free
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  transaction.revocationDate == nil else { continue }
            let productPlan = Plan.plan(forProductID: transaction.productID)
            if productPlan > best {
                best = productPlan
                latestTransactionJWS = result.jwsRepresentation
            }
        }
        apply(best)
    }

    /// Purchase with the Supabase user UUID as appAccountToken so the backend
    /// can attribute App Store notifications to this user.
    public func purchase(_ product: Product, appAccountToken: UUID?) async throws -> Bool {
        var options: Set<Product.PurchaseOption> = []
        if let appAccountToken {
            options.insert(.appAccountToken(appAccountToken))
        }
        let result = try await product.purchase(options: options)
        switch result {
        case .success(let verification):
            if case .verified(let transaction) = verification {
                latestTransactionJWS = verification.jwsRepresentation
                await transaction.finish()
                apply(Plan.plan(forProductID: transaction.productID))
                return true
            }
            return false
        case .userCancelled, .pending:
            return false
        @unknown default:
            return false
        }
    }

    public func restore() async {
        try? await AppStore.sync()
        await refresh()
    }

    private func handle(_ update: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = update else { return }
        if Self.productIDs.contains(transaction.productID) {
            latestTransactionJWS = update.jwsRepresentation
            await transaction.finish()
            await refresh()
        }
    }

    private func apply(_ value: Plan) {
        plan = value
        applyDebugOverride()   // debug flags can only raise, never revoke
    }

    private func applyDebugOverride() {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--diamond") {
            plan = .diamond
        } else if arguments.contains("--premium"), plan == .free {
            plan = .platinum
        }
    }
}
