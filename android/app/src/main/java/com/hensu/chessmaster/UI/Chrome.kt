package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The navigation bar + content of a screen: `.navigationTitle(title)` and `.toolbar { }`.
 * `large` is `.navigationBarTitleDisplayMode(.large)` (tab roots): the title sits big and
 * left under the bar. A back arrow shows when the screen was pushed (or `onBack` is given,
 * for sheets and covers with a leading Close/Cancel). `trailing` is the trailing toolbar items.
 * The content gets the full width; add your own scrolling. Status and navigation bar insets
 * are applied here, so screens never handle them.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    background: Color = Sys.groupedBackground,
    onBack: (() -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val nav = LocalNavOrNull.current
    val isRoot = LocalIsNavRoot.current
    val back = onBack ?: if (nav != null && !isRoot) ({ nav.pop() }) else null
    val extras = if (isRoot) LocalToolbarExtras.current else null
    Column(
        modifier.fillMaxSize().background(background)
            .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading()
            else if (back != null) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { back() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Sys.primary)
                }
            }
            if (!large) {
                Text(title, style = Sys.headline, color = Sys.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            } else Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                trailing()
                extras?.invoke()
            }
        }
        if (large) {
            Text(title, style = Sys.largeTitle.copy(fontWeight = FontWeight.Bold), color = Sys.primary,
                 modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/**
 * Extra trailing toolbar items a parent adds to the ROOT screen of a NavStack (iOS: a view
 * modifier that appends a ToolbarItem, like `.upgradeCrown(source:)` on each tab root).
 */
val LocalToolbarExtras = androidx.compose.runtime.compositionLocalOf<(@Composable () -> Unit)?> { null }

/** A toolbar text button ("Done", "Cancel", "Close"). */
@Composable
fun ToolbarButton(text: String, color: Color = Sys.green, bold: Boolean = false, onClick: () -> Unit) {
    Text(text, style = if (bold) Sys.headline else Sys.body, color = color,
         modifier = Modifier.clip(CircleShape).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp))
}

/**
 * .alert(title, isPresented:) { buttons } message: { }. Call it where the Swift view attaches
 * the alert: `if (show) Alert("Title", "Message", confirm = "Delete", destructive = true,
 * onConfirm = { … }, onDismiss = { show = false })`. `cancel = null` is a one-button alert.
 */
@Composable
fun Alert(
    title: String,
    message: String? = null,
    confirm: String = "OK",
    destructive: Boolean = false,
    cancel: String? = "Cancel",
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Sys.headline) },
        text = message?.let { { Text(it, style = Sys.subheadline) } },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) Sys.red else Sys.blue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = cancel?.let { { TextButton(onClick = onDismiss) { Text(it, color = Sys.blue) } } },
        containerColor = Sys.secondaryGroupedBackground,
        titleContentColor = Sys.primary,
        textContentColor = Sys.primary,
    )
}
