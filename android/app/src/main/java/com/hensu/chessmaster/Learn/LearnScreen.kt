// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Learn tab: themed puzzle categories on top (tied to the coaching
 * taxonomy), guided lessons below.
 */
@Composable
fun LearnScreen() {
    val progress = DependencyContainer.shared.learnProgress
    val nav = LocalNav.current

    ScreenScaffold("Learn", modifier = Modifier.trackScreen("Learn"), large = true, background = Sys.groupedBackground) {
        // The content is parsed in the background at launch; in the rare case the tab is opened
        // before that finishes, wait here instead of freezing the tab switch.
        if (!LearnContent.loaded) {
            LaunchedEffect(Unit) { LearnContent.awaitLoaded() }
            return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val daily = DailyPuzzle.puzzle()
            if (daily != null) {
                Box(
                    Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
                        .clickable { nav.push { PuzzleScreen(daily = daily) } }
                ) { DailyCard() }
            }

            Text("Puzzles", style = Sys.title3.bold(), color = Sys.primary, modifier = Modifier.padding(horizontal = 16.dp))

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in PuzzleCategory.entries.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (category in row) {
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                    .clickable { nav.push { PuzzleScreen(category = category) } }
                            ) { CategoryCard(category, progress) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Text("Lessons", style = Sys.title3.bold(), color = Sys.primary,
                 modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp))

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lesson in LearnContent.lessons) {
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .clickable { nav.push { LessonScreen(lesson) } }
                    ) { LessonRow(lesson, progress) }
                }
            }
        }
    }
}

@Composable
private fun DailyCard() {
    val number = DailyPuzzle.number()
    val solved = DailyPuzzle.result(number) != null
    val streak = DailyPuzzle.streak(today = number)
    Row(
        Modifier.fillMaxWidth().background(Sys.secondaryGroupedBackground, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (solved) Icons.Rounded.Verified else Icons.Rounded.CalendarToday, contentDescription = null,
             tint = if (solved) Sys.green else Sys.orange, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Daily Puzzle #$number", style = Sys.headline, color = Sys.primary)
            Text(if (solved) "Solved today · come back tomorrow" else "Keep your streak going",
                 style = Sys.caption, color = Sys.secondary)
        }
        if (streak > 0) {
            Text("🔥 $streak", style = Sys.headline, color = Sys.primary)
        }
    }
}

@Composable
private fun CategoryCard(category: PuzzleCategory, progress: LearnProgress) {
    val total = LearnContent.puzzles(category).size
    val solved = progress.solvedCount(category)
    Column(
        Modifier.fillMaxWidth().background(Sys.secondaryGroupedBackground, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(category.icon, contentDescription = null,
             tint = if (solved == total && total > 0) Sys.green else Sys.accent, modifier = Modifier.size(24.dp))
        Text(category.title, style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary, maxLines = 1)
        Text("$solved/$total solved", style = Sys.caption, color = Sys.secondary)
    }
}

@Composable
private fun LessonRow(lesson: Lesson, progress: LearnProgress) {
    val done = progress.completedLessons.contains(lesson.id)
    Row(
        Modifier.fillMaxWidth().background(Sys.secondaryGroupedBackground, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Outlined.School, contentDescription = null,
             tint = if (done) Sys.green else Sys.secondary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(lesson.title, style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary)
            Text(lesson.subtitle, style = Sys.caption, color = Sys.secondary)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Sys.tertiary, modifier = Modifier.size(16.dp))
    }
}

// MARK: - Shared by the puzzle and lesson screens

/** SwiftUI `Label(text, systemImage:)`: an icon and a text on one line. */
@Composable
internal fun LearnLabel(text: String, icon: ImageVector, style: TextStyle, color: Color, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
        Text(text, style = style, color = color)
    }
}

/** `.buttonStyle(.borderedProminent).tint(.green).controlSize(.large)` with a full-width label. */
@Composable
internal fun LearnProminentButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(Sys.green).clickable { onClick() },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, style = Sys.headline, color = Color.White)
    }
}

/** `.buttonStyle(.bordered)` with a full-width label; dimmed and inert when disabled. */
@Composable
internal fun LearnBorderedButton(text: String, icon: ImageVector, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (enabled) Sys.accent else Sys.tertiary
    Row(
        modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Sys.accent.opacity(0.15) else Sys.tertiaryFill)
            .clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = Sys.body, color = tint)
    }
}
