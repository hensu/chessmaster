package com.hensu.chessmaster

import android.app.Application
import android.content.Context

/** Process-wide entry point. `App.context` is the application context for stores and audio. */
class ChessAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.context = this
        Push.configure(this)
    }
}

object App {
    lateinit var context: Context
    /**
     * Launch flags, the Android twin of the iOS launch arguments (`--uitest`, `--premium`, …):
     * `adb shell am start -n com.hensu.chessmaster/.MainActivity --es args "--premium --preview-paywall"`.
     */
    var arguments: List<String> = emptyList()
}
