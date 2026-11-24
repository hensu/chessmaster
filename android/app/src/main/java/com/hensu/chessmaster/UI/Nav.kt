package com.hensu.chessmaster

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * NavigationStack on Android. SwiftUI code pushes a destination view from a NavigationLink;
 * here a screen calls `LocalNav.current.push { Destination() }` and `pop()` is `dismiss()`.
 * The system back button pops. Each NavStack keeps its own stack (one per tab, like iOS).
 */
class Navigator {
    internal val stack = mutableStateListOf<Entry>()
    internal class Entry(val id: Int, val content: @Composable () -> Unit)
    private var nextId = 1

    val depth: Int get() = stack.size
    fun push(content: @Composable () -> Unit) { stack.add(Entry(nextId++, content)) }
    /** @Environment(\.dismiss): leaves the top screen. */
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun popToRoot() { stack.clear() }
    /** Replaces the top screen. */
    fun replace(content: @Composable () -> Unit) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        push(content)
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No NavStack above this screen") }
/** The enclosing NavStack's navigator, or null in a sheet/cover that has no NavStack. */
val LocalNavOrNull = staticCompositionLocalOf<Navigator?> { null }

/** NavigationStack { root }. */
@Composable
fun NavStack(navigator: Navigator = remember { Navigator() }, root: @Composable () -> Unit) {
    val holder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalNav provides navigator, LocalNavOrNull provides navigator) {
        BackHandler(enabled = navigator.stack.isNotEmpty()) { navigator.pop() }
        val top = navigator.stack.lastOrNull()
        AnimatedContent(
            targetState = top?.id ?: 0,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it } + fadeOut())
            },
            label = "nav",
        ) { id ->
            Box(Modifier.fillMaxSize()) {
                holder.SaveableStateProvider(id) {
                    val entry = navigator.stack.firstOrNull { it.id == id }
                    if (id == 0 || entry == null) root() else entry.content()
                }
            }
        }
    }
}
