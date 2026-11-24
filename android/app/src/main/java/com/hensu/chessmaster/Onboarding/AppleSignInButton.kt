// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Sign in with Apple. iOS uses the native sheet; Android opens Apple's web sign-in in a
 *  Custom Tab (Supabase OAuth) and the callback becomes a Supabase session. The button keeps
 *  Apple's look: black in light mode, white in dark, logo + "Continue with Apple". */
@Composable
fun AppleSignInButton(sync: SyncService, onSignedIn: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
    val background = if (Sys.dark) Color.White else Color.Black
    val foreground = if (Sys.dark) Color.Black else Color.White
    val logo = remember { PathParser().parsePathString(appleLogoPath).toPath() }

    // Shape (height + corner clip) is owned by each call site.
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(background)
            .clickable {
                sync.track("signup_method_tapped", mapOf("method" to "apple"))
                scope.launch {
                    sync.signInWithApple()
                    // The web flow never shares Apple's name with the app; the Name screen
                    // asks for it when it is missing.
                    if (sync.isSignedIn) onSignedIn()
                }
            }
            .testTag("appleSignInButton"),
        contentAlignment = Alignment.Center,
    ) {
        // Apple sizes the title at 43% of the button's height (19pt in a 44pt button).
        val height = if (maxHeight.value.isFinite()) maxOf(minHeight.value, 44f).coerceAtMost(maxHeight.value) else 44f
        val fontSize = (maxOf(height, 44f) * 0.43f).coerceIn(14f, 24f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Canvas(Modifier.size((fontSize * 0.95f).dp)) {
                // The logo path is drawn on a 24×24 grid.
                scale(size.minDimension / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    drawPath(logo, foreground)
                }
            }
            Spacer(Modifier.width((fontSize * 0.3f).dp))
            BasicText(
                "Continue with Apple",
                style = Sys.font(fontSize, FontWeight.Medium).copy(color = foreground),
                maxLines = 1,
            )
        }
    }
}

/** The Apple logo on a 24×24 grid. */
private const val appleLogoPath =
    "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 " +
    "1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 " +
    "1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857" +
    "-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09z" +
    "M15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 " +
    "3.714 1.338.104 2.715-.688 3.559-1.701"
