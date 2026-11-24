// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Gold crown in the top-right of each tab for players without a plan;
 * tapping it opens the paywall.
 *
 * The Swift `.upgradeCrown(source:)` modifier appends a toolbar item, which a
 * Compose `Modifier` cannot do: this is the toolbar item itself, and RootView
 * hands it to each tab root's `ScreenScaffold` through `LocalToolbarExtras`.
 */
@Composable
fun UpgradeCrown(source: String) {
    val container = DependencyContainer.shared
    var showPaywall by remember { mutableStateOf(false) }

    if (!container.entitlements.isPremium) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable {
                container.sync.track("upgrade_crown_tapped", mapOf("source" to source))
                showPaywall = true
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(CrownFill, contentDescription = "Upgrade", tint = rgb(0.961, 0.769, 0.318),
                 modifier = Modifier.size(24.dp))
        }
    }
    if (showPaywall) {
        Sheet(onDismiss = { showPaywall = false }) {
            AppPaywall(source, onClose = { showPaywall = false })
        }
    }
}
