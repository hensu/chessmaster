package com.hensu.chessmaster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * NotificationCenter.default on Android: named app-wide events, delivered on the caller's
 * thread (always the main thread here). Names are the Swift Notification.Name strings
 * ("kidProfileChanged", "showKidPicker", "firstLessonClosed").
 */
object AppEvents {
    private val observers = mutableMapOf<String, MutableList<(Any?) -> Unit>>()

    fun post(name: String, payload: Any? = null) {
        observers[name]?.toList()?.forEach { it(payload) }
    }

    /** Returns a function that removes the observer. */
    fun observe(name: String, block: (Any?) -> Unit): () -> Unit {
        observers.getOrPut(name) { mutableListOf() }.add(block)
        return { observers[name]?.remove(block) }
    }
}

/** .onReceive(NotificationCenter.default.publisher(for: .name)) { … } */
@Composable
fun OnAppEvent(name: String, block: (Any?) -> Unit) {
    val current = rememberUpdatedState(block)
    DisposableEffect(name) {
        val remove = AppEvents.observe(name) { current.value(it) }
        onDispose { remove() }
    }
}
