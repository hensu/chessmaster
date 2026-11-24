// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Sends a GA screen view each time this screen appears — feeds the
 * "Pages and screens" report (see SyncService.trackScreen).
 */
fun Modifier.trackScreen(name: String): Modifier = composed {
    LaunchedEffect(name) { DependencyContainer.shared.sync.trackScreen(name) }
    this
}
