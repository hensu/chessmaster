// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen() {
    val container = DependencyContainer.shared
    val nav = LocalNav.current
    var games by remember { mutableStateOf<List<GameRecord>>(emptyList()) }

    // onAppear (also runs again when coming back from a game's analysis).
    LaunchedEffect(Unit) {
        games = runCatching { container.games.recentGames() }.getOrNull() ?: emptyList()
    }

    /** Deletes locally and (best-effort) from the player's account. */
    fun delete(offsets: List<Int>) {
        val doomed = offsets.map { games[it] }
        games = games.filterIndexed { index, _ -> index !in offsets }
        for (game in doomed) {
            container.sync.track("game_deleted", mapOf("time_class" to (game.ratingCategory ?: "untimed")))
            runCatching { container.games.delete(game.id) }
            val id = game.id
            MainScope().launch { container.sync.deleteRemoteGame(id) }
        }
    }

    ScreenScaffold("History", modifier = Modifier.trackScreen("History"), large = true) {
        if (games.isEmpty()) {
            // ContentUnavailableView
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.GridOn, contentDescription = null, tint = Sys.secondary,
                     modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Text("No games yet", style = Sys.title2.bold(), color = Sys.primary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Finished games appear here.", style = Sys.subheadline, color = Sys.secondary,
                     textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) 10.dp else 0.dp,
                        topEnd = if (index == 0) 10.dp else 0.dp,
                        bottomStart = if (index == games.lastIndex) 10.dp else 0.dp,
                        bottomEnd = if (index == games.lastIndex) 10.dp else 0.dp,
                    )
                    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            val at = games.indexOfFirst { it.id == game.id }
                            if (at >= 0) delete(listOf(at))
                            true
                        } else false
                    })
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        modifier = Modifier.animateItem().clip(shape),
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(Sys.red).padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd) {
                                Text("Delete", style = Sys.body, color = Color.White)
                            }
                        },
                    ) {
                        Column(
                            Modifier.fillMaxWidth().background(Sys.secondaryGroupedBackground)
                                .clickable {
                                    // .navigationDestination(for: String.self): look the game up by id.
                                    val gameID = game.id
                                    games.firstOrNull { it.id == gameID }?.let { found ->
                                        nav.push { AnalysisScreen(game = found) }
                                    }
                                },
                        ) {
                            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                GameHistoryRow(game, Modifier.weight(1f))
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Sys.tertiary,
                                     modifier = Modifier.padding(start = 6.dp).size(20.dp))
                            }
                            if (index != games.lastIndex) {
                                Box(Modifier.padding(start = 54.dp).fillMaxWidth().height(0.5.dp).background(Sys.separator))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GameHistoryRow(game: GameRecord, modifier: Modifier = Modifier) {
    val userWon: Boolean? = when {
        game.result == "draw" -> null
        (game.result == "whiteWin" && game.playerColor == "white") ||
            (game.result == "blackWin" && game.playerColor == "black") -> true
        else -> false
    }

    val opponentLabel =
        if (game.opponentType == "engine")
            "Level ${game.engineLevel ?: 1} · ${StrengthLevel.level(game.engineLevel ?: 1).displayName}"
        else if (game.opponentType == "friend") "Friend" else "Two players"

    val initial = game.tcInitialSeconds
    val increment = game.tcIncrementSeconds
    val timeControlLabel = if (initial == null || increment == null) "Casual" else "${initial / 60}+$increment"

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            Icon(
                when (userWon) {
                    true -> Icons.Rounded.EmojiEvents
                    false -> Icons.Rounded.HighlightOff
                    null -> Icons.Rounded.DragHandle
                },
                contentDescription = null,
                tint = when (userWon) {
                    true -> Sys.green
                    false -> Sys.red
                    null -> Sys.secondary
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(opponentLabel, style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(timeControlLabel, style = Sys.caption, color = Sys.secondary)
                // Text(date, style: .date): "November 24, 2026"
                Text(game.endedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                         .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
                     style = Sys.caption, color = Sys.secondary)
            }
        }

        val before = game.ratingBefore
        val after = game.ratingAfter
        if (before != null && after != null) {
            val delta = after.roundToInt() - before.roundToInt()
            Text(if (delta >= 0) "+$delta" else "$delta",
                 style = Sys.subheadline.bold().copy(fontFeatureSettings = "tnum"),
                 color = if (delta >= 0) Sys.green else Sys.red)
        }
    }
}
