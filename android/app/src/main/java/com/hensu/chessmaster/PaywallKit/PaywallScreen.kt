// PaywallKit — Chessmaster
// GPL-3.0-or-later
package com.hensu.chessmaster

import android.app.Activity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.ProductDetails
import java.text.NumberFormat
import java.util.Currency
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** SF Symbol `crown.fill` (Material has no crown): three points over a base band. */
val CrownFill: ImageVector by lazy {
    ImageVector.Builder(name = "CrownFill", defaultWidth = 24.dp, defaultHeight = 24.dp,
                        viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.6f, 16.6f)
            lineTo(2.1f, 7.6f)
            curveTo(2.0f, 6.9f, 2.7f, 6.5f, 3.3f, 6.9f)
            lineTo(7.7f, 10.0f)
            lineTo(11.3f, 4.4f)
            curveTo(11.6f, 3.9f, 12.4f, 3.9f, 12.7f, 4.4f)
            lineTo(16.3f, 10.0f)
            lineTo(20.7f, 6.9f)
            curveTo(21.3f, 6.5f, 22.0f, 6.9f, 21.9f, 7.6f)
            lineTo(20.4f, 16.6f)
            close()
            moveTo(4.6f, 18.2f)
            lineTo(19.4f, 18.2f)
            curveTo(20.0f, 18.2f, 20.4f, 18.6f, 20.4f, 19.2f)
            curveTo(20.4f, 19.8f, 20.0f, 20.2f, 19.4f, 20.2f)
            lineTo(4.6f, 20.2f)
            curveTo(4.0f, 20.2f, 3.6f, 19.8f, 3.6f, 19.2f)
            curveTo(3.6f, 18.6f, 4.0f, 18.2f, 4.6f, 18.2f)
            close()
        }
    }.build()
}

private val paywallDiamondBlue = rgb(0.24, 0.55, 0.99)
private val paywallCrownGold = rgb(0.961, 0.769, 0.318)
private const val paywallTermsURL = "https://getchessai.com/terms.html"
private const val paywallPrivacyURL = "https://getchessai.com/privacy.html"


/** `(price / 12).formatted(priceFormatStyle)`: the yearly price per month, in the product's currency. */
private fun paywallMonthlyEquivalent(product: ProductDetails): String {
    val phase = product.recurringPhase ?: return ""
    val format = NumberFormat.getCurrencyInstance()
    runCatching { format.currency = Currency.getInstance(phase.priceCurrencyCode) }
    return format.format(phase.priceAmountMicros / 1_000_000.0 / 12.0)
}

/**
 * Upgrade sheet: crown, Yearly/Monthly toggle (Yearly default), Diamond and
 * Platinum cards with their features (Diamond pre-selected), one CTA.
 * Everything fits above the fold. The billed amount is always the dominant
 * price; the per-month equivalent stays subordinate (App Store 3.1.2(c)).
 *
 * `onPurchaseVerify`: called with the product id and Play purchase token after a
 * successful purchase so the app can register the entitlement server-side.
 * `onPurchaseCancelled`: called when the player backs out of the Google Play purchase sheet.
 * `onClose`: closes the paywall when it isn't presented as a sheet (onboarding).
 * `closeDelay`: hides the close button this long before fading it in (onboarding).
 */
@Composable
fun PaywallScreen(
    entitlements: EntitlementStore,
    appAccountToken: UUID? = null,
    /** Price-test product suffix for this install (null = today's prices). */
    priceVariant: String? = null,
    onPurchaseVerify: (suspend (String, String) -> Unit)? = null,
    onPurchaseCancelled: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeDelay: Duration = Duration.ZERO,
) {
    val scope = rememberCoroutineScope()
    var showClose by remember { mutableStateOf(false) }
    var animateClose by remember { mutableStateOf(false) }

    var products by remember { mutableStateOf<Map<String, ProductDetails>>(emptyMap()) }
    var trialEligible by remember { mutableStateOf(false) }
    var isAnnual by remember { mutableStateOf(true) }
    var tier by remember { mutableStateOf(Plan.diamond) }
    var purchasing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // @Environment(\.dismiss): without `onClose` the paywall is in a sheet or cover, whose
    // dialog closes on "back" (never sent to the activity, which would leave the screen below).
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    fun close() {
        if (onClose != null) onClose()
        else if (backOwner !is Activity) backOwner?.onBackPressedDispatcher?.onBackPressed()
    }

    /** The product to sell for a tier: this install's price-test variant
     *  when the store has it, else the base product (a variant that isn't
     *  live yet, or was removed, must never leave the sheet empty). */
    fun product(tier: Plan, annual: Boolean): ProductDetails? {
        val base = EntitlementStore.baseProductID(tier, annual)
        return products[EntitlementStore.productID(base, priceVariant)] ?: products[base]
    }
    val selectedProduct = product(tier, isAnnual)
    val hasTrial = trialEligible && selectedProduct?.freeTrialOffer != null

    // MARK: - Actions

    suspend fun loadProducts() {
        if (products.isNotEmpty()) return
        val loaded = try {
            entitlements.products(
                (EntitlementStore.productIDs +
                    EntitlementStore.productIDs.map { EntitlementStore.productID(it, priceVariant) }).distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        if (loaded.isNotEmpty()) {
            products = loaded
            // Returning subscribers aren't eligible — don't promise a trial.
            trialEligible = loaded["com.chessmaster.diamond.yearly"]?.freeTrialOffer != null
        }
    }

    fun buy() {
        val product = selectedProduct ?: return
        if (purchasing) return
        purchasing = true
        errorMessage = null
        scope.launch {
            try {
                if (entitlements.purchase(product, appAccountToken)) {
                    val token = entitlements.latestPurchaseToken
                    if (token != null) {
                        onPurchaseVerify?.invoke(entitlements.latestProductID ?: product.productId, token)
                    }
                    close()
                } else {
                    onPurchaseCancelled?.invoke()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Purchase couldn't be completed. Please try again."
            } finally {
                purchasing = false
            }
        }
    }

    fun restore() {
        scope.launch {
            entitlements.restore()
            if (entitlements.isPremium) close()
        }
    }

    val ctaTitle = when {
        hasTrial -> "Start 7-day free trial"
        tier == Plan.diamond -> "Continue with Diamond"
        else -> "Continue with Platinum"
    }

    val finePrint = selectedProduct.let { p ->
        if (p == null) "Payment through Google Play."
        else {
            val billed = "${p.displayPrice} billed ${if (isAnnual) "yearly" else "monthly"}"
            if (hasTrial) "7 days free, then $billed. Cancel anytime."
            else "$billed. Auto-renews, cancel anytime in Google Play."
        }
    }

    LaunchedEffect(Unit) { loadProducts() }
    LaunchedEffect(Unit) {
        if (closeDelay <= Duration.ZERO) { showClose = true; return@LaunchedEffect }
        delay(closeDelay)
        animateClose = !reduceMotion()
        showClose = true
    }
    val closeAlpha by animateFloatAsState(
        if (showClose) 1f else 0f,
        if (animateClose) tween(400, easing = EaseIn) else snap(),
        label = "close",
    )

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.alpha(closeAlpha).size(32.dp).clip(CircleShape).background(Sys.secondaryBackground)
                    .clickable(enabled = showClose) { close() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Sys.secondary,
                     modifier = Modifier.size(18.dp))
            }
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(CrownFill, contentDescription = null, tint = paywallCrownGold, modifier = Modifier.size(48.dp))
            Text("Upgrade your coach", style = Sys.title.bold(), color = Sys.primary)
            PaywallPeriodToggle(isAnnual) { isAnnual = it }
            PaywallTierCard(
                selected = tier == Plan.diamond, product = product(Plan.diamond, isAnnual),
                isAnnual = isAnnual, title = "Diamond", badge = "MOST POPULAR",
                features = listOf(
                    "Every review by our most advanced AI",
                    "Coach's Overview across all your games",
                    "Highest limits, new features first",
                ),
                modifier = Modifier.padding(top = 6.dp),
            ) { tier = Plan.diamond }
            PaywallTierCard(
                selected = tier == Plan.platinum, product = product(Plan.platinum, isAnnual),
                isAnnual = isAnnual, title = "Platinum", badge = null,
                features = listOf(
                    "Unlimited AI game reviews",
                    "Retry training + auto insights",
                    "5 advanced deep reviews a month",
                ),
            ) { tier = Plan.platinum }
            errorMessage?.let { Text(it, style = Sys.footnote, color = Sys.red) }
        }

        // MARK: - Purchase bar

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().alpha(if (selectedProduct == null) 0.5f else 1f)
                    .clip(CircleShape).background(paywallDiamondBlue)
                    .testTag("paywallPurchase")
                    .clickable(enabled = !purchasing && selectedProduct != null) { buy() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (purchasing) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    Text(ctaTitle, style = Sys.headline, color = Color.White)
                }
            }

            Text(finePrint, style = Sys.caption2, color = Sys.secondary, textAlign = TextAlign.Center)

            // Required in the purchase flow: functional Terms of Use (EULA)
            // + Privacy Policy links.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PaywallLink("Restore") { restore() }
                Text("·", style = Sys.caption, color = Sys.tertiary)
                PaywallLink("Terms") { Platform.openURL(paywallTermsURL) }
                Text("·", style = Sys.caption, color = Sys.tertiary)
                PaywallLink("Privacy") { Platform.openURL(paywallPrivacyURL) }
            }
        }
    }
}

@Composable
private fun PaywallLink(text: String, onClick: () -> Unit) {
    Text(text, style = Sys.caption, color = Sys.accent,
         modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() },
                                       indication = null) { onClick() })
}

// MARK: - Toggle

@Composable
private fun PaywallPeriodToggle(isAnnual: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sys.secondaryBackground).padding(4.dp)
    ) {
        PaywallPeriodSegment(annual = true, isAnnual = isAnnual, onSelect = onSelect) { color ->
            Text("Yearly", style = Sys.subheadline.weight(FontWeight.SemiBold), color = color)
            Text("Save 49%", style = Sys.caption.weight(FontWeight.SemiBold), color = Sys.green)
        }
        PaywallPeriodSegment(annual = false, isAnnual = isAnnual, onSelect = onSelect) { color ->
            Text("Monthly", style = Sys.subheadline.weight(FontWeight.SemiBold), color = color)
        }
    }
}

@Composable
private fun RowScope.PaywallPeriodSegment(
    annual: Boolean,
    isAnnual: Boolean,
    onSelect: (Boolean) -> Unit,
    label: @Composable RowScope.(Color) -> Unit,
) {
    val selected = isAnnual == annual
    val fill by animateColorAsState(
        if (selected) Sys.tertiaryFill else Sys.tertiaryFill.copy(alpha = 0f),
        tween(150, easing = EaseInOut), label = "segment",
    )
    Row(
        Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(fill)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onSelect(annual)
            }
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label(if (selected) Sys.primary else Sys.secondary)
    }
}

// MARK: - Plan card

@Composable
private fun PaywallTierCard(
    selected: Boolean,
    product: ProductDetails?,
    isAnnual: Boolean,
    title: String,
    badge: String?,
    features: List<String>,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val fill by animateColorAsState(
        if (selected) paywallDiamondBlue.opacity(0.12) else Sys.secondaryBackground,
        tween(150, easing = EaseInOut), label = "cardFill",
    )
    val stroke by animateColorAsState(
        if (selected) paywallDiamondBlue else Sys.separator, tween(150, easing = EaseInOut), label = "cardStroke",
    )
    val strokeWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(150, easing = EaseInOut), label = "cardWidth")
    val checkColor by animateColorAsState(
        if (selected) paywallDiamondBlue else Sys.secondary, tween(150, easing = EaseInOut), label = "check",
    )
    val featureColor by animateColorAsState(
        if (selected) Sys.primary else Sys.secondary, tween(150, easing = EaseInOut), label = "feature",
    )

    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(fill).border(strokeWidth, stroke, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect() }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row {
                Text(title, style = Sys.title3.weight(FontWeight.ExtraBold), color = Sys.primary,
                     modifier = Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                Column(Modifier.alignByBaseline(), horizontalAlignment = Alignment.End,
                       verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        product?.let { "${it.displayPrice}/${if (isAnnual) "year" else "month"}" } ?: "—",
                        style = Sys.title3.weight(FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum"),
                        color = Sys.primary,
                    )
                    Text(
                        if (isAnnual) product?.let { "${paywallMonthlyEquivalent(it)}/mo" } ?: ""
                        else "billed monthly",
                        style = Sys.caption, color = Sys.secondary,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (text in features) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = checkColor,
                             modifier = Modifier.padding(top = 2.dp).size(14.dp))
                        Text(text, style = Sys.subheadline, color = featureColor)
                    }
                }
            }
        }
        if (badge != null) {
            Text(
                badge, style = Sys.caption2.bold(), color = Color.White,
                modifier = Modifier.offset(x = 16.dp, y = (-10).dp)
                    .clip(RoundedCornerShape(7.dp)).background(paywallDiamondBlue)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}
