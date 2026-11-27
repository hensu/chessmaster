// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlin.time.Duration

/**
 * The paywall wired to this app's account + backend: purchases carry the
 * Supabase user UUID and register server-side immediately.
 *
 * `source`: which surface raised the paywall ("profile_upgrade", "game_insights",
 * "coaching", "training", "player_review") — funnel attribution.
 * `onClose`: set when shown inline (onboarding) rather than as a sheet;
 * without it the paywall dismisses the sheet it is in.
 */
@Composable
fun AppPaywall(source: String, onClose: (() -> Unit)? = null, closeDelay: Duration = Duration.ZERO) {
    val container = DependencyContainer.shared

    PaywallScreen(
        entitlements = container.entitlements,
        appAccountToken = container.sync.userID,
        priceVariant = container.sync.flagValue("price_test"),
        onPurchaseVerify = { productID, purchaseToken ->
            container.sync.track("purchase_verified", mapOf("source" to source))
            container.sync.verifySubscription(productID, purchaseToken)
        },
        onPurchaseCancelled = {
            container.sync.track("purchase_cancelled", mapOf("source" to source))
        },
        onClose = onClose,
        closeDelay = closeDelay,
    )
    LaunchedEffect(Unit) {
        container.sync.track("paywall_viewed", mapOf("source" to source))
        container.sync.trackScreen("Paywall")
    }
    DisposableEffect(Unit) {
        onDispose {
            // A successful purchase dismisses the sheet too; only count
            // walk-aways.
            if (!container.entitlements.isPremium) {
                container.sync.track("paywall_dismissed", mapOf("source" to source))
            }
        }
    }
}
