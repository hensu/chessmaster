// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Composable
fun ClockView(clock: ChessClock, side: ClockSide, modifier: Modifier = Modifier) {
    val isActive = clock.running == side

    // 0.1s cadence normally; 0.05s under 10s to render tenths smoothly.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(clock, side) {
        while (true) {
            delay(if (clock.remaining(side) < 10.seconds) 50 else 100)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick

    val remaining = clock.remaining(side)
    val low = remaining < 10.seconds
    Text(
        ClockView.format(remaining),
        style = Sys.title3.bold().copy(fontFeatureSettings = "tnum"),
        color = if (low && isActive) Color.White else Sys.primary,
        modifier = modifier
            .alpha(if (isActive || clock.isStopped) 1f else 0.6f)
            .background(
                if (low && isActive) Sys.red else (if (isActive) Sys.green.opacity(0.25) else Sys.secondary.opacity(0.12)),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** `ClockView.format(_:)`, the Swift view's static. */
object ClockView {
    fun format(duration: Duration): String {
        val totalMs = duration.inWholeMilliseconds
        val totalSeconds = totalMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (totalSeconds < 10) {
            val tenths = (totalMs % 1000) / 100
            return String.format(java.util.Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
        }
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
