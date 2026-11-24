// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppTab { play, learn, history, profile }

/**
 * Tab selection plus one navigation stack per tab. The Swift `playPath` (a NavigationPath) is
 * `playNav` here: `router.playNav.push { GameScreen(config) }` is `router.playPath.append(config)`,
 * and `popToRoot()` is `playPath = NavigationPath()`.
 */
class AppRouter {
    var selectedTab: AppTab by mutableStateOf(AppTab.play)
    val playNav = Navigator()
    val learnNav = Navigator()
    val historyNav = Navigator()
    val profileNav = Navigator()

    companion object { val shared by lazy { AppRouter() } }
}
