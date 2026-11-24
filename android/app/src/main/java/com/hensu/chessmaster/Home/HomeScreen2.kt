// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// HomeScreen.swift, second half: the pieces of the Home layout and the custom time sheet.

/** `Label(title, systemImage:).font(.headline)`. */
@Composable
internal fun HomeLabel(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = Sys.primary, modifier = Modifier.size(22.dp))
        Text(title, style = Sys.headline, color = Sys.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The "get ready" card over Home: knight, time control, level and color. */
@Composable
internal fun HomeMatchCardOverlay(card: HomeMatchCard?) {
    // Keep the last card while it fades out.
    var last by remember { mutableStateOf(card) }
    if (card != null) last = card
    AnimatedVisibility(
        visible = card != null,
        enter = if (reduceMotion()) fadeIn(tween(0)) else fadeIn(tween(250)) + scaleIn(tween(250), initialScale = 0.92f),
        exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.92f),
    ) {
        val shown = last ?: return@AnimatedVisibility
        Box(
            // .ultraThinMaterial has no twin: a translucent wash of the page background.
            Modifier.fillMaxSize().background(Sys.groupedBackground.opacity(0.82))
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.shadow(24.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Sys.secondaryGroupedBackground)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(hex(0xB58863)).padding(16.dp)) {
                    PieceImage(Piece.Kind.knight, Piece.Color.white, Modifier.size(64.dp))
                }
                Text(shown.title,
                     style = Sys.font(34, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), color = Sys.primary)
                Text(shown.subtitle, style = Sys.subheadline, color = Sys.secondary, textAlign = TextAlign.Center)
                shown.note?.let { note ->
                    val up = note.startsWith("Level up")
                    val tint = if (up) Sys.green else Sys.orange
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (up) Icons.Rounded.ArrowCircleUp else Icons.Rounded.ArrowCircleDown,
                             contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Text(note, style = Sys.footnote.weight(FontWeight.SemiBold), color = tint)
                    }
                }
            }
        }
    }
}

/** Level + color for THIS game, pinned under the tiles. */
@Composable
internal fun HomeGameOptionsBar(state: HomeScreenState) {
    // Re-read on every appear: the defaults are changed in Profile.
    val levelLabel = remember(state.levelOverride, state.appearCount) { state.effectiveLevelLabel }
    val colorLabel = remember(state.colorOverride, state.appearCount) { state.effectiveColorLabel }
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                var open by remember { mutableStateOf(false) }
                HomeOptionChip(Icons.Rounded.Speed, levelLabel) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false },
                             containerColor = Sys.secondaryGroupedBackground) {
                    HomeMenuItem("Auto (recommended)",
                                 checked = state.levelOverride == null && state.levelMode == "auto") {
                        state.levelOverride = null
                        open = false
                    }
                    val selection = state.levelOverride ?: (if (state.levelMode == "manual") state.engineLevel else -1)
                    for (n in 1..10) {
                        HomeMenuItem("${StrengthLevel.level(n).displayName} · Level $n", checked = selection == n) {
                            state.levelOverride = n
                            open = false
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Box {
                var open by remember { mutableStateOf(false) }
                HomeOptionChip(Icons.Rounded.Contrast, colorLabel) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false },
                             containerColor = Sys.secondaryGroupedBackground) {
                    val selection = state.colorOverride ?: state.colorChoice
                    for ((label, tag) in listOf("Random" to "random", "White" to "white", "Black" to "black")) {
                        HomeMenuItem(label, checked = selection == tag) {
                            state.colorOverride = tag
                            open = false
                        }
                    }
                }
            }
        }
        Text("For this game — change defaults in Profile.", style = Sys.caption2, color = Sys.tertiary)
    }
}

/** One row of a Menu / Picker: a checkmark in front of the current choice. */
@Composable
private fun HomeMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Sys.body, color = Sys.primary) },
        onClick = onClick,
        leadingIcon = {
            Box(Modifier.size(20.dp)) {
                if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = Sys.primary)
            }
        },
    )
}

// MARK: - Friends

@Composable
internal fun HomeFriendGameCard(game: FriendGameState, state: HomeScreenState) {
    val container = DependencyContainer.shared
    val me = container.sync.userID ?: game.hostID
    val challenge = game.isChallenge(me)
    val waiting = game.status == "waiting"
    val title = if (challenge) "${game.hostName} challenged you · ${game.timeControlLabel}"
        else if (waiting) "Invite waiting · ${game.code}"
        else "Your game vs ${game.opponentName(me)}"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (challenge) Sys.green.opacity(0.15) else Sys.secondaryGroupedBackground)
            .clickable {
                if (challenge) {
                    state.accept(game)
                } else if (waiting) {
                    state.waitingInvite = game
                } else {
                    container.startFriendGame(game)
                }
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeLabel(title,
                  if (challenge) Icons.Rounded.SportsScore else if (waiting) Icons.Rounded.HourglassEmpty else Icons.Rounded.Group,
                  Modifier.weight(1f))
        Text(if (challenge) "Accept" else if (waiting) "Open" else "Resume",
             style = Sys.footnote.weight(FontWeight.SemiBold), color = Sys.green)
    }
}

@Composable
internal fun HomeRewardCard(reward: ReferralReward, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sys.green.opacity(0.15))
            .clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🎁", style = Sys.title2)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("You earned a free month of Diamond", style = Sys.headline, color = Sys.primary)
            // .formatted(.dateTime.month(.abbreviated).day()): "Nov 24"
            val claimBy = reward.claimBy.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
            Text("For playing a friend. Claim by $claimBy.", style = Sys.caption, color = Sys.secondary)
        }
    }
}

@Composable
private fun HomeOptionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Sys.secondaryGroupedBackground).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Sys.primary, modifier = Modifier.size(18.dp))
        Text(label, style = Sys.subheadline.weight(FontWeight.Medium), color = Sys.primary)
    }
}

// MARK: - Pieces

/** Wordle-style daily puzzle: same position for everyone today. */
@Composable
internal fun HomeDailyPuzzleRow(state: HomeScreenState) {
    val container = DependencyContainer.shared
    val puzzle = DailyPuzzle.puzzle() ?: return
    val number = DailyPuzzle.number()
    val solved = remember(number, state.appearCount) { DailyPuzzle.result(number) != null }
    val streak = remember(number, state.appearCount) { DailyPuzzle.streak(number) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sys.secondaryGroupedBackground)
            .clickable {
                container.sync.track("daily_puzzle_opened",
                                     mapOf("source" to "home", "solved" to if (solved) "true" else "false"))
                state.nav.push { PuzzleScreen(daily = puzzle) }
            }
            .testTag("dailyPuzzle")
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (solved) Icons.Rounded.Verified else Icons.Rounded.Extension, contentDescription = null,
             tint = if (solved) Sys.green else Sys.orange, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Daily Puzzle #$number", style = Sys.headline, color = Sys.primary)
            Text(if (solved) "Solved · share your result" else "Same puzzle for everyone today",
                 style = Sys.subheadline, color = Sys.secondary)
        }
        if (streak > 0) {
            Text("🔥 $streak", style = Sys.subheadline.weight(FontWeight.Bold), color = Sys.primary)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Sys.tertiary,
             modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun HomeStreakChip(emoji: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(Sys.secondaryGroupedBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Text(emoji, style = Sys.body)
        Text(label, style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary, maxLines = 1)
    }
}

@Composable
internal fun HomeTile(top: String, bottom: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Column(
        modifier.heightIn(min = 78.dp).clip(RoundedCornerShape(12.dp)).background(Sys.secondaryGroupedBackground)
            .clickable { action() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        // One weight/size for every tile; the few longer labels
        // (15+10, 30+20, Custom) shrink slightly instead of
        // switching to a smaller, lighter font.
        var size by remember(top) { mutableFloatStateOf(22f) }
        Text(top,
             style = Sys.title2.bold().copy(fontSize = size.sp, fontFeatureSettings = "tnum"),
             color = Sys.primary, maxLines = 1, softWrap = false,
             modifier = Modifier.padding(horizontal = 6.dp),
             // .minimumScaleFactor(0.6)
             onTextLayout = { if (it.hasVisualOverflow && size > 22f * 0.6f) size -= 1f })
        Text(bottom, style = Sys.caption, color = Sys.secondary, maxLines = 1)
    }
}

@Composable
internal fun HomeRatingsRow() {
    val container = DependencyContainer.shared
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (category in TimeControl.Category.entries) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Sys.secondaryGroupedBackground)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val rating = container.ratings.ratings[category]
                if (rating != null) {
                    Text("${rating.rating.roundToInt()}",
                         style = Sys.headline.copy(fontFeatureSettings = "tnum"), color = Sys.primary)
                } else {
                    Text("—", style = Sys.headline, color = Sys.tertiary)
                }
                Text(category.rawValue.replaceFirstChar { it.uppercase() }, style = Sys.caption2, color = Sys.secondary)
            }
        }
    }
}

/**
 * Minimal sheet for a custom time control — everything else has defaults.
 * `onDismiss` is the Swift `dismiss()` (Cancel).
 */
@Composable
fun CustomTimeSheet(onStart: (TimeControl) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableIntStateOf(5) }
    var increment by remember { mutableIntStateOf(3) }

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        // The sheet's own bar: Cancel leading, inline title centered.
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { ToolbarButton("Cancel") { onDismiss() } }
            Text("Custom game", style = Sys.headline, color = Sys.primary, modifier = Modifier.align(Alignment.Center))
        }
        // Form
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)).background(Sys.secondaryGroupedBackground),
        ) {
            HomeStepper("Minutes: $minutes", minutes, 1..120) { minutes = it }
            HomeFormSeparator()
            HomeStepper("Increment: ${increment}s", increment, 0..60) { increment = it }
            HomeFormSeparator()
            Box(
                Modifier.fillMaxWidth()
                    .clickable { onStart(TimeControl(minutes = minutes, incrementSeconds = increment)) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Start game", style = Sys.headline, color = Sys.accent)
            }
        }
    }
}

@Composable
private fun HomeFormSeparator() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Sys.separator))
}

/** `Stepper(label, value:, in:)`: the label and a − / + pair. */
@Composable
private fun HomeStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Sys.body, color = Sys.primary, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Sys.tertiaryFill),
            verticalAlignment = Alignment.CenterVertically) {
            val canDecrease = value > range.first
            val canIncrease = value < range.last
            Box(Modifier.size(width = 47.dp, height = 32.dp).clickable(enabled = canDecrease) { onChange(value - 1) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrement",
                     tint = if (canDecrease) Sys.primary else Sys.tertiary, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.width(1.dp).height(18.dp).background(Sys.separator))
            Box(Modifier.size(width = 47.dp, height = 32.dp).clickable(enabled = canIncrease) { onChange(value + 1) },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, contentDescription = "Increment",
                     tint = if (canIncrease) Sys.primary else Sys.tertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
