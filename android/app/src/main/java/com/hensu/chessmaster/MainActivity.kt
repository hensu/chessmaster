package com.hensu.chessmaster

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // UISupportedInterfaceOrientations: portrait only.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        current = this
        App.arguments = intent?.getStringExtra("args")?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        ChessmasterApp.launched()
        ChessmasterApp.onOpenURL(intent)
        setContent {
            // Sizes are designed in points like the iOS app and do not follow the system font
            // size, so 1.sp == 1.dp everywhere.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                ChessmasterApp.Content()
            }
        }
    }

    /** Invite links, OAuth callbacks and notification taps arriving while the app is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ChessmasterApp.onOpenURL(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        /** The foreground activity, for things that need one (billing, permission prompts, share sheets). */
        var current: MainActivity? = null
    }
}
