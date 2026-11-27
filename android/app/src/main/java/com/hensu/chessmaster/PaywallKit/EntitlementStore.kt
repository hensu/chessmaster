// PaywallKit — Chessmaster
// GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import java.time.Instant
import java.time.Period
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The subscription ladder. Platinum keeps the historical "premium"
 * product IDs so existing subscribers grandfather in unchanged.
 * Cases are declared in rank order, so `<` / `>` compare tiers.
 */
enum class Plan(val rawValue: String) {
    free("free"), platinum("platinum"), diamond("diamond");

    companion object {
        fun fromRaw(raw: String): Plan? = entries.firstOrNull { it.rawValue == raw }

        /** Matches by prefix, so a price-test product (`<base id>.<suffix>`,
         *  see `EntitlementStore.productID`) is the same plan as its base. */
        fun plan(forProductID: String): Plan {
            fun matches(ids: List<String>) = ids.any { forProductID == it || forProductID.startsWith("$it.") }
            if (matches(EntitlementStore.diamondIDs)) return diamond
            if (matches(EntitlementStore.platinumIDs)) return platinum
            return free
        }
    }
}

/** A purchase that could not be started or completed (StoreKit's thrown purchase errors). */
class PurchaseException(message: String) : Exception(message)

/** The base plan of a Play subscription (the offer without an offer id). */
val ProductDetails.basePlanOffer: ProductDetails.SubscriptionOfferDetails?
    get() = subscriptionOfferDetails?.firstOrNull { it.offerId == null }

/**
 * The `free-trial` offer, present only when Play considers this account eligible
 * (StoreKit: `introductoryOffer.paymentMode == .freeTrial` + `isEligibleForIntroOffer`).
 */
val ProductDetails.freeTrialOffer: ProductDetails.SubscriptionOfferDetails?
    get() = subscriptionOfferDetails?.firstOrNull { offer ->
        offer.offerId == EntitlementStore.trialOfferID &&
            offer.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros == 0L
    }

/** The recurring (full price) phase of the base plan. */
val ProductDetails.recurringPhase: ProductDetails.PricingPhase?
    get() = (basePlanOffer ?: subscriptionOfferDetails?.firstOrNull())
        ?.pricingPhases?.pricingPhaseList?.lastOrNull()

/** StoreKit `displayPrice`: the localized price billed each period. */
val ProductDetails.displayPrice: String
    get() = recurringPhase?.formattedPrice ?: "—"

/**
 * Single source of truth for subscription status. All gating in the app
 * reads `plan`/`isPremium`; server features additionally verify server-side.
 *
 * Android: one Google Play Billing client. The plan derives from the owned
 * subscription's product id; every owned purchase is acknowledged and
 * registered with the backend (`sync.verifySubscription`).
 */
class EntitlementStore {
    companion object {
        val platinumIDs = listOf(
            "com.chessmaster.premium.monthly", "com.chessmaster.premium.yearly",
        )
        val diamondIDs = listOf(
            "com.chessmaster.diamond.monthly", "com.chessmaster.diamond.yearly",
        )
        val productIDs = diamondIDs + platinumIDs

        /** The base product for a tier and billing period. */
        fun baseProductID(tier: Plan, annual: Boolean): String = when (tier) {
            Plan.diamond -> if (annual) "com.chessmaster.diamond.yearly" else "com.chessmaster.diamond.monthly"
            else -> if (annual) "com.chessmaster.premium.yearly" else "com.chessmaster.premium.monthly"
        }

        /** A price-test variant of a base product: the same id with the
         *  variant's suffix appended (`…diamond.yearly.b`). The suffix comes
         *  from the server (app_config `price_test`), so a new price needs a
         *  new store product but no new build. */
        fun productID(base: String, variant: String?): String =
            if (variant.isNullOrEmpty()) base else "$base.$variant"

        /** The Play offer id of the 7-day free trial on each base plan. */
        const val trialOfferID = "free-trial"

        // Play does not say whether the current period is a free trial, so
        // the purchase that bought the trial offer is remembered locally.
        private const val trialTokenKey = "entitlements.trialToken"
        private const val trialEndKey = "entitlements.trialEndsAt"
    }

    /** The active tier. `isPremium` means "any paid plan". */
    var plan: Plan by mutableStateOf(Plan.free)
        private set
    val isPremium: Boolean get() = plan != Plan.free
    /** The latest purchase token (+ its product id), for server verification after purchase. */
    var latestPurchaseToken: String? by mutableStateOf(null)
        private set
    var latestProductID: String? by mutableStateOf(null)
        private set
    /** The Swift name; on Android it holds the Play purchase token (there is no JWS). */
    val latestTransactionJWS: String? get() = latestPurchaseToken
    /** When the active plan's free trial ends; nil when not in a trial. */
    var trialEndsAt: Instant? by mutableStateOf(null)
        private set

    /**
     * True once a current subscription came from a Google Play promo code
     * (friend-game free month): the app then retires its reward card.
     * The billing library does not say how a subscription was bought, so
     * this comes from the backend's verification (`serverFacts`).
     */
    var redeemedOfferCode: Boolean by mutableStateOf(false)
        private set

    private val scope = MainScope()
    private val connectMutex = Mutex()
    /** The billing sheet that is on screen, waiting for Play's result. */
    private var pendingFlow: CompletableDeferred<Pair<BillingResult, List<Purchase>>>? = null
    private var productCache: Map<String, ProductDetails> = emptyMap()
    /** What the backend learned from Google about each owned purchase token. */
    private val serverFacts = mutableMapOf<String, SyncService.VerifiedSubscription>()
    /** "user|token" pairs already sent to the backend this launch. */
    private val verified = mutableSetOf<String>()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val waiting = pendingFlow
        if (waiting != null && !waiting.isCompleted) {
            waiting.complete(result to (purchases ?: emptyList()))
        } else if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            // Transaction.updates: a purchase finished outside our flow
            // (redeemed code, pending payment cleared, bought on another device).
            scope.launch { handle(purchases ?: emptyList()) }
        }
    }

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(App.context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    init {
        applyDebugOverride()
        scope.launch { refresh() }
    }

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return connectMutex.withLock {
            if (client.isReady) return@withLock true
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { continuation ->
                    client.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (continuation.isActive) {
                                continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                            }
                        }
                        override fun onBillingServiceDisconnected() {}
                    })
                }
            } ?: false
        }
    }

    /** Our subscriptions this Play account owns right now; null when Play could not be asked. */
    private suspend fun ownedPurchases(): List<Purchase>? {
        if (!connect()) return null
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return result.purchasesList.filter { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED && productID(purchase) != null
        }
    }

    private fun productID(purchase: Purchase): String? =
        purchase.products.firstOrNull { Plan.plan(forProductID = it) != Plan.free }

    /** Re-reads current entitlements (offline-friendly: Google Play caches). */
    suspend fun refresh() {
        // Play unreachable: keep what we know rather than revoke.
        val owned = ownedPurchases() ?: return
        var best = Plan.free
        var trialEnd: Instant? = null
        for (purchase in owned) {
            val id = productID(purchase) ?: continue
            acknowledge(purchase)
            val productPlan = Plan.plan(id)
            if (productPlan > best) {
                best = productPlan
                latestPurchaseToken = purchase.purchaseToken
                latestProductID = id
                trialEnd = trialEnd(purchase)
            }
        }
        trialEndsAt = trialEnd ?: owned.firstNotNullOfOrNull { serverFacts[it.purchaseToken]?.trialEndsAt }
        redeemedOfferCode = owned.any { serverFacts[it.purchaseToken]?.offerCode == true }
        apply(best)
        for (purchase in owned) registerWithBackend(purchase)
    }

    /** The end of the free trial this purchase started, while it is still running. */
    private fun trialEnd(purchase: Purchase): Instant? {
        val defaults = Defaults.standard
        if (defaults.string(trialTokenKey) != purchase.purchaseToken) return null
        val end = Instant.ofEpochSecond(defaults.double(trialEndKey).toLong())
        return if (end.isAfter(Instant.now())) end else null
    }

    private fun rememberTrial(purchase: Purchase, offer: ProductDetails.SubscriptionOfferDetails) {
        val period = offer.pricingPhases.pricingPhaseList.firstOrNull()?.billingPeriod
        val days = runCatching { Period.parse(period).days }.getOrNull()?.takeIf { it > 0 } ?: 7
        val end = Instant.ofEpochMilli(purchase.purchaseTime).plusSeconds(days * 86_400L)
        Defaults.standard.set(trialTokenKey, purchase.purchaseToken)
        Defaults.standard.set(trialEndKey, end.epochSecond.toDouble())
    }

    /**
     * Tells the backend about an owned purchase (once per account per launch),
     * so a purchase that never reached the server heals on refresh.
     */
    private fun registerWithBackend(purchase: Purchase) {
        val id = productID(purchase) ?: return
        val sync = DependencyContainer.shared.sync
        if (!sync.isSignedIn) return
        if (!verified.add("${sync.userID}|${purchase.purchaseToken}")) return
        scope.launch {
            val facts = sync.verifySubscription(id, purchase.purchaseToken) ?: return@launch
            serverFacts[purchase.purchaseToken] = facts
            if (facts.offerCode) redeemedOfferCode = true
            if (trialEndsAt == null && facts.trialEndsAt?.isAfter(Instant.now()) == true) trialEndsAt = facts.trialEndsAt
        }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        )
    }

    /** The four subscriptions as Play describes them, keyed by product id (`Product.products(for:)`). */
    suspend fun products(ids: List<String> = productIDs): Map<String, ProductDetails> {
        if (ids.all { productCache.containsKey(it) }) return productCache.filterKeys { ids.contains(it) }
        if (!connect()) return emptyMap()
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id).setProductType(BillingClient.ProductType.SUBS).build()
        }).build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return emptyMap()
        val loaded = (result.productDetailsList ?: emptyList()).associateBy { it.productId }
        productCache = productCache + loaded
        return loaded
    }

    /**
     * Purchase with the Supabase user UUID as the obfuscated account id so the
     * backend can attribute Play notifications to this user. Buys the
     * `free-trial` offer when Play returns it, else the base plan; replaces the
     * current subscription when another of ours is already owned.
     */
    suspend fun purchase(product: ProductDetails, appAccountToken: UUID?): Boolean {
        val activity = MainActivity.current ?: throw PurchaseException("No activity")
        if (!connect()) throw PurchaseException("Google Play is unavailable")
        val trial = product.freeTrialOffer
        val offer = trial ?: product.basePlanOffer ?: product.subscriptionOfferDetails?.firstOrNull()
            ?: throw PurchaseException("No offer for ${product.productId}")

        val owned = ownedPurchases() ?: emptyList()
        if (owned.any { productID(it) == product.productId }) {
            // Already theirs (bought elsewhere): just pick it up.
            refresh()
            return plan >= Plan.plan(product.productId)
        }

        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product).setOfferToken(offer.offerToken).build()
        ))
        if (appAccountToken != null) flow.setObfuscatedAccountId(appAccountToken.toString())
        owned.firstOrNull()?.let { current ->
            // Upgrade / downgrade / period change: switch now, crediting the unused time.
            flow.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(current.purchaseToken)
                    .setSubscriptionReplacementMode(
                        BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION)
                    .build()
            )
        }

        val waiter = CompletableDeferred<Pair<BillingResult, List<Purchase>>>()
        pendingFlow = waiter
        val (result, purchases) = try {
            val launched = client.launchBillingFlow(activity, flow.build())
            when (launched.responseCode) {
                BillingClient.BillingResponseCode.OK -> waiter.await()
                BillingClient.BillingResponseCode.USER_CANCELED -> return false
                else -> throw PurchaseException(launched.debugMessage)
            }
        } finally {
            pendingFlow = null
        }

        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases.firstOrNull { it.products.contains(product.productId) }
                    ?: return false
                // Pending (slow payment method): unlocks later through the listener.
                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return false
                latestPurchaseToken = purchase.purchaseToken
                latestProductID = product.productId
                if (trial != null) rememberTrial(purchase, trial)
                trialEndsAt = trialEnd(purchase)
                acknowledge(purchase)
                apply(Plan.plan(product.productId))
                true
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> false
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refresh()
                isPremium
            }
            else -> throw PurchaseException(result.debugMessage)
        }
    }

    /** Play has no "sync" call: the owned purchases are simply read again. */
    suspend fun restore() {
        refresh()
    }

    private suspend fun handle(purchases: List<Purchase>) {
        val ours = purchases.filter {
            it.purchaseState == Purchase.PurchaseState.PURCHASED && productID(it) != null
        }
        if (ours.isEmpty()) return
        for (purchase in ours) {
            latestPurchaseToken = purchase.purchaseToken
            latestProductID = productID(purchase)
            acknowledge(purchase)
        }
        refresh()
    }

    private fun apply(value: Plan) {
        plan = value
        applyDebugOverride()   // debug flags can only raise, never revoke
    }

    private fun applyDebugOverride() {
        val arguments = App.arguments
        if (arguments.contains("--diamond")) {
            plan = Plan.diamond
        } else if (arguments.contains("--premium") && plan == Plan.free) {
            plan = Plan.platinum
        }
    }
}
