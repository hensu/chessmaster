// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val profileGold = rgb(0.961, 0.769, 0.318)

@Composable
fun ProfileScreen() {
    val container = DependencyContainer.shared
    var category by rememberSaveable { mutableStateOf(TimeControl.Category.blitz) }
    var pickedDefaultCategory by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<RatingHistoryRecord>>(emptyList()) }
    var stats by remember { mutableStateOf(Triple(0, 0, 0)) }   // (wins, draws, losses)
    var showPaywall by remember { mutableStateOf(false) }
    var paywallSource by remember { mutableStateOf("profile_upgrade") }
    var showAccountSheet by remember { mutableStateOf(false) }
    var accountEmail by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var deleteAccountFailed by remember { mutableStateOf(false) }
    var playerReview by remember { mutableStateOf<PlayerReview?>(null) }
    var reviewLoading by remember { mutableStateOf(false) }
    var reviewMessage by remember { mutableStateOf<String?>(null) }
    var engineLevel by appStorageInt("play.engineLevel", 3)
    var levelMode by appStorageString("play.levelMode", "auto")
    var colorChoice by appStorageString("play.colorChoice", "random")
    var appearance by appStorageString("appearance", "dark")
    val nav = LocalNav.current
    val audio = container.audio
    val voice = container.coachVoice

    /** Cross-game review: recurring flaws, strengths, focus plan (premium). */
    fun requestPlayerReview() {
        if (container.entitlements.plan != Plan.diamond) {
            paywallSource = "player_review"
            showPaywall = true
            return
        }
        reviewLoading = true
        reviewMessage = null
        container.sync.track("player_review_requested")
        MainScope().launch {
            try {
                val response: PlayerReviewResponse = container.sync.invokeFunction(
                    "generate-player-review", emptyMap<String, String>()
                )
                val report = response.review.report
                if (report != null) {
                    playerReview = report
                } else {
                    reviewMessage = "The coach couldn't build your overview yet."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reviewMessage = "The coach is taking a moment — try again shortly. (Needs at least 2 coached games.)"
            } finally {
                reviewLoading = false
            }
        }
    }

    fun reload() {
        // Land on the player's own category: most frequent recently,
        // most recent as tie-break. Only once — manual picks stick.
        if (!pickedDefaultCategory) {
            pickedDefaultCategory = true
            val recent = (runCatching { container.games.recentGames(30) }.getOrNull() ?: emptyList())
                .mapNotNull { it.ratingCategory }
                .mapNotNull { TimeControl.Category.fromRaw(it) }
            val counts = mutableMapOf<TimeControl.Category, Int>()
            for (cat in recent) counts[cat] = (counts[cat] ?: 0) + 1
            val maxCount = counts.values.maxOrNull() ?: 0
            // Most frequent wins; ties go to the most recently played
            // (recent is newest-first, so the first hit is the tiebreak).
            recent.firstOrNull { counts[it] == maxCount }?.let { category = it }
        }
        history = runCatching { container.ratingHistory.history(category.rawValue) }.getOrNull() ?: emptyList()
        stats = runCatching { container.games.stats() }.getOrNull() ?: Triple(0, 0, 0)
    }

    // .onAppear(perform: reload) + .onChange(of: category) { reload() }
    LaunchedEffect(category) { reload() }
    LaunchedEffect(Unit) { accountEmail = container.sync.accountEmail() }

    ScreenScaffold("Profile", modifier = Modifier.trackScreen("Profile"), large = true) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            ProfileSection("Rating") {
                row {
                    ProfileSegmented(
                        options = TimeControl.Category.entries.map { it to it.rawValue.replaceFirstChar { c -> c.uppercase() } },
                        selection = category,
                        onSelect = { category = it },
                    )
                }

                if (history.isEmpty()) {
                    row {
                        Text("Play rated games against Chess AI to build your rating history.",
                             style = Sys.footnote, color = Sys.secondary)
                    }
                } else {
                    row {
                        ProfileRatingChart(history.map { it.rating },
                                           Modifier.padding(vertical = 6.dp).fillMaxWidth().height(180.dp))
                    }

                    history.lastOrNull()?.let { last ->
                        row {
                            Text("Current", style = Sys.body, color = Sys.primary)
                            Spacer(Modifier.weight(1f))
                            Text("${last.rating.roundToInt()} ± ${last.deviation.roundToInt()}",
                                 style = Sys.headline, color = Sys.primary)
                        }
                    }
                }
            }

            ProfileSection("Coach's overview") {
                val review = playerReview
                if (review != null) {
                    row { Text(review.headline, style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary) }
                    for (flaw in review.recurringFlaws) {
                        row {
                            Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(flaw.theme.replace("_", " "), style = Sys.caption.bold(), color = Sys.red)
                                Text(flaw.evidence, style = Sys.footnote, color = Sys.primary)
                                ProfileLabel(flaw.fix, Icons.Outlined.Lightbulb, tint = Sys.green, textColor = Sys.green,
                                             style = Sys.footnote)
                            }
                        }
                    }
                    review.focusPlan.forEachIndexed { i, item ->
                        row {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProfileNumberCircle(i + 1)
                                Text(item, style = Sys.footnote, color = Sys.primary)
                            }
                        }
                    }
                    row { Text(review.encouragement, style = Sys.footnote, color = Sys.secondary) }
                } else if (reviewLoading) {
                    row {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Sys.secondary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Text("Your coach is reviewing your recent games…", style = Sys.footnote, color = Sys.secondary)
                        }
                    }
                } else {
                    reviewMessage?.let { message ->
                        row { Text(message, style = Sys.footnote, color = Sys.secondary) }
                    }
                    row(onClick = { requestPlayerReview() }) {
                        ProfileLabel(
                            if (container.entitlements.plan == Plan.diamond) "How can I improve?"
                            else "How can I improve? (Diamond)",
                            Icons.AutoMirrored.Rounded.TrendingUp, tint = Sys.green, textColor = Sys.green,
                        )
                    }
                }
            }

            ProfileSection("Record vs Chess AI") {
                row {
                    Row(Modifier.padding(vertical = 4.dp)) {
                        ProfileStatView(count = stats.first, label = "Wins", color = Sys.green)
                        ProfileStatView(count = stats.second, label = "Draws", color = Sys.secondary)
                        ProfileStatView(count = stats.third, label = "Losses", color = Sys.red)
                    }
                }
            }

            ProfileSection("Membership") {
                when (container.entitlements.plan) {
                    Plan.diamond ->
                        row { ProfileLabel("Diamond — advanced AI coaching", CrownFill, tint = profileGold, textColor = profileGold) }
                    Plan.platinum -> {
                        row { ProfileLabel("Platinum active", CrownFill, tint = Sys.secondary, textColor = Sys.secondary) }
                        row(onClick = {
                            paywallSource = "profile_upgrade"
                            showPaywall = true
                        }) {
                            ProfileLabel("Upgrade to Diamond", CrownFill, tint = profileGold, textColor = profileGold)
                        }
                    }
                    Plan.free ->
                        row(onClick = {
                            paywallSource = "profile_upgrade"
                            showPaywall = true
                        }) {
                            ProfileLabel("Upgrade — Platinum or Diamond", CrownFill, tint = profileGold, textColor = profileGold)
                        }
                }
            }

            ProfileSection("Game") {
                row {
                    ProfileMenuPicker(
                        title = "Chess AI strength",
                        selection = if (levelMode == "auto") 0 else engineLevel,
                        options = listOf(0 to "Auto — adapts to you") +
                            (1..10).map { n -> n to "${StrengthLevel.level(n).displayName} · Level $n" },
                        onSelect = { newValue ->
                            if (newValue == 0) {
                                levelMode = "auto"
                            } else {
                                engineLevel = newValue
                                levelMode = "manual"
                            }
                            container.sync.track("strength_changed", mapOf(
                                "mode" to levelMode,
                                "level" to if (levelMode == "auto") "auto" else engineLevel.toString(),
                            ))
                        },
                    )
                }

                row {
                    ProfileMenuPicker(
                        title = "Your color",
                        selection = colorChoice,
                        options = listOf("random" to "Random", "white" to "White", "black" to "Black"),
                        onSelect = { colorChoice = it },
                    )
                }
            }

            ProfileSection("Sound") {
                row { ProfileToggle("Sound effects", audio.soundsEnabled) { audio.soundsEnabled = it } }
                row { ProfileToggle("Background music", audio.musicEnabled) { audio.musicEnabled = it } }
                row { ProfileToggle("Coach voice in Game Review", voice.enabled) { voice.enabled = it } }
            }

            ProfileSection("Appearance") {
                row {
                    ProfileMenuPicker(
                        title = "Theme",
                        selection = appearance,
                        options = listOf("dark" to "Dark", "light" to "Light", "system" to "System"),
                        onSelect = { appearance = it },
                    )
                }
            }

            ProfileSection("About") {
                row(onClick = {
                    container.sync.track("rate_app_tapped", mapOf("source" to "profile"))
                    Platform.openURL(Platform.storeURL)
                }) {
                    ProfileLabel("Rate Chess AI", Icons.Rounded.Star, tint = profileGold, textColor = Sys.primary)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ArrowOutward, contentDescription = null, tint = Sys.tertiary, modifier = Modifier.size(15.dp))
                }
                row(onClick = { nav.push { LicensesScreen() } }) {
                    Text("Licenses & attributions", style = Sys.body, color = Sys.primary)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Sys.tertiary, modifier = Modifier.size(20.dp))
                }
                row {
                    Text("Version", style = Sys.body, color = Sys.primary)
                    Spacer(Modifier.weight(1f))
                    Text(Platform.version.ifEmpty { "—" }, style = Sys.body, color = Sys.secondary)
                }
            }
            ProfileSection("Account") {
                when (val state = container.sync.state) {
                    is SyncService.SyncState.NotConfigured -> {}
                    is SyncService.SyncState.SignedOut ->
                        row(onClick = { showAccountSheet = true }) {
                            ProfileLabel("Sign in or create account", Icons.Rounded.PersonAdd, tint = Sys.accent, textColor = Sys.accent)
                        }
                    is SyncService.SyncState.Idle, is SyncService.SyncState.Syncing -> {
                        row {
                            ProfileLabel(accountEmail ?: "Your account", Icons.Rounded.HowToReg, tint = Sys.accent, textColor = Sys.primary)
                        }
                        row(onClick = { MainScope().launch { container.sync.signOut() } }) {
                            Text("Sign out", style = Sys.body, color = Sys.red)
                        }
                        row(onClick = { confirmDeleteAccount = true }, enabled = !deletingAccount, testTag = "deleteAccount") {
                            if (deletingAccount) {
                                Text("Deleting account…", style = Sys.body, color = Sys.red.opacity(0.5))
                                Spacer(Modifier.weight(1f))
                                CircularProgressIndicator(color = Sys.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Text("Delete account", style = Sys.body, color = Sys.red)
                            }
                        }
                    }
                    is SyncService.SyncState.Error -> {
                        row {
                            ProfileLabel(state.message, Icons.Rounded.CloudOff, tint = Sys.red, textColor = Sys.red, style = Sys.footnote)
                        }
                        row(onClick = { MainScope().launch { container.sync.pushPending() } }) {
                            Text("Retry", style = Sys.body, color = Sys.accent)
                        }
                    }
                }
            }
        }
    }

    if (showPaywall) Sheet(onDismiss = { showPaywall = false }) {
        AppPaywall(source = paywallSource, onClose = { showPaywall = false })
    }
    if (showAccountSheet) Sheet(onDismiss = { showAccountSheet = false }) {
        AccountSheet(sync = container.sync, onDismiss = { showAccountSheet = false })
    }
    if (confirmDeleteAccount) Alert(
        "Delete your account?",
        "This permanently deletes your account and all synced data — games, ratings, and coaching reports. Local games on this device are kept. This can't be undone.",
        confirm = "Delete", destructive = true,
        onConfirm = {
            MainScope().launch {
                deletingAccount = true
                val ok = container.sync.deleteAccount()
                deletingAccount = false
                if (ok) accountEmail = null else deleteAccountFailed = true
            }
        },
        onDismiss = { confirmDeleteAccount = false },
    )
    if (deleteAccountFailed) Alert(
        "Couldn't delete account",
        "Please check your connection and try again.",
        confirm = "OK", cancel = null,
        onDismiss = { deleteAccountFailed = false },
    )
}

@Composable
private fun RowScope.ProfileStatView(count: Int, label: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$count", style = Sys.title3.bold(), color = color)
        Text(label, style = Sys.caption2, color = Sys.secondary)
    }
}

// MARK: - Grouped list (SwiftUI List, inset grouped)

/** Collects the rows of one section, so separators go between whatever rows the state produces. */
private class ProfileRows {
    class Entry(val onClick: (() -> Unit)?, val enabled: Boolean, val testTag: String?, val content: @Composable RowScope.() -> Unit)
    val entries = mutableListOf<Entry>()

    /** One list row; `onClick` makes it a Button row. */
    fun row(onClick: (() -> Unit)? = null, enabled: Boolean = true, testTag: String? = null,
            content: @Composable RowScope.() -> Unit) {
        entries += Entry(onClick, enabled, testTag, content)
    }
}

/** Section("Header") { rows }: an uppercase header over a rounded card whose rows are split by separators. */
@Composable
private fun ProfileSection(header: String, build: ProfileRows.() -> Unit) {
    val rows = ProfileRows().apply(build).entries
    Text(header.uppercase(), style = Sys.footnote, color = Sys.secondary,
         modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 7.dp))
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Sys.secondaryGroupedBackground)) {
        rows.forEachIndexed { index, entry ->
            var modifier = Modifier.fillMaxWidth()
            if (entry.testTag != null) modifier = modifier.testTag(entry.testTag)
            if (entry.onClick != null) modifier = modifier.clickable(enabled = entry.enabled) { entry.onClick.invoke() }
            Row(
                modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = entry.content,
            )
            if (index < rows.lastIndex) {
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Sys.separator))
            }
        }
    }
}

/** Label(text, systemImage:) */
@Composable
private fun ProfileLabel(
    text: String,
    icon: ImageVector,
    tint: Color,
    textColor: Color,
    style: androidx.compose.ui.text.TextStyle = Sys.body,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val iconSize = if (style == Sys.footnote) 16.dp else 22.dp
        Box(Modifier.width(if (style == Sys.footnote) 20.dp else 28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        }
        Text(text, style = style, color = textColor)
    }
}

/** Image(systemName: "N.circle"): a number in a thin circle. */
@Composable
private fun ProfileNumberCircle(n: Int) {
    Box(Modifier.size(18.dp).border(1.2.dp, Sys.accent, CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", style = Sys.font(10, FontWeight.SemiBold), color = Sys.accent, textAlign = TextAlign.Center)
    }
}

/** Picker(...).pickerStyle(.segmented) */
@Composable
private fun <T> RowScope.ProfileSegmented(options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(9.dp)).background(Sys.tertiaryFill).padding(2.dp),
    ) {
        for ((value, label) in options) {
            val selected = value == selection
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(7.dp))
                    .background(if (selected) (if (Sys.dark) Color(0xFF636366) else Color.White) else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Sys.footnote.weight(if (selected) FontWeight.SemiBold else FontWeight.Medium), color = Sys.primary)
            }
        }
    }
}

/** Picker(title, selection:) in a List: the title, the current value, and a menu of the options. */
@Composable
private fun <T> RowScope.ProfileMenuPicker(title: String, selection: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(title, style = Sys.body, color = Sys.primary)
    Spacer(Modifier.weight(1f))
    Box {
        Row(
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { open = true },
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selection }?.second ?: "", style = Sys.body, color = Sys.secondary)
            Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = Sys.secondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Sys.secondaryGroupedBackground) {
            for ((value, label) in options) {
                DropdownMenuItem(
                    text = { Text(label, style = Sys.body, color = Sys.primary) },
                    leadingIcon = {
                        if (value == selection) Icon(Icons.Rounded.Check, contentDescription = null, tint = Sys.primary, modifier = Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                    },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/** Toggle(title, isOn:) */
@Composable
private fun RowScope.ProfileToggle(title: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    Text(title, style = Sys.body, color = Sys.primary, modifier = Modifier.weight(1f))
    // No 48dp touch-target padding, so the row keeps the iOS height.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Switch(
            checked = isOn,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Sys.green,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Sys.tertiaryFill,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

// MARK: - Rating chart (Swift Charts: LineMark + AreaMark, monotone, y scale without zero)

/** "Nice" axis step (1, 2 or 5 × 10^k) giving about `target` ticks over `range`. */
private fun profileNiceStep(range: Double, target: Int): Double {
    if (range <= 0) return 1.0
    val raw = range / target
    val magnitude = 10.0.pow(floor(log10(raw)))
    val fraction = raw / magnitude
    val nice = when {
        fraction <= 1 -> 1.0
        fraction <= 2 -> 2.0
        fraction <= 5 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

@Composable
private fun ProfileRatingChart(ratings: List<Double>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val line = Sys.accent
    val grid = Sys.separator
    val labelStyle = Sys.caption2.copy(color = Sys.secondary)
    Canvas(modifier) {
        if (ratings.isEmpty()) return@Canvas
        val count = ratings.size
        val low = ratings.min()
        val high = ratings.max()
        val yStep = profileNiceStep(if (high - low < 1) 20.0 else high - low, 4)
        var yMin = floor(low / yStep) * yStep
        var yMax = ceil(high / yStep) * yStep
        if (yMax - yMin < yStep) { yMin -= yStep; yMax += yStep }

        val right = 36.dp.toPx()    // y labels sit on the trailing edge, like Swift Charts
        val bottom = 16.dp.toPx()   // x labels
        val top = 6.dp.toPx()
        val plotWidth = size.width - right
        val plotHeight = size.height - bottom - top
        fun x(game: Double): Float = if (count == 1) plotWidth / 2 else ((game - 1) / (count - 1) * plotWidth).toFloat()
        fun y(rating: Double): Float = top + ((yMax - rating) / (yMax - yMin) * plotHeight).toFloat()

        // Horizontal grid lines + rating labels.
        var tick = yMin
        while (tick <= yMax + yStep / 1000) {
            val ty = y(tick)
            drawLine(grid, Offset(0f, ty), Offset(plotWidth, ty), strokeWidth = 0.5.dp.toPx())
            val text = measurer.measure(tick.roundToInt().toString(), labelStyle)
            drawText(text, topLeft = Offset(plotWidth + 6.dp.toPx(), ty - text.size.height / 2f))
            tick += yStep
        }
        // Game-number labels.
        if (count > 1) {
            val xStep = maxOf(1.0, profileNiceStep((count - 1).toDouble(), 5))
            var game = ceil(1 / xStep) * xStep
            while (game <= count) {
                val text = measurer.measure(game.roundToInt().toString(), labelStyle)
                val tx = (x(game) - text.size.width / 2f).coerceIn(0f, plotWidth - text.size.width)
                drawText(text, topLeft = Offset(tx, top + plotHeight + 3.dp.toPx()))
                game += xStep
            }
        }

        val points = ratings.mapIndexed { index, rating -> Offset(x(index + 1.0), y(rating)) }
        if (count == 1) {
            drawCircle(line, radius = 3.dp.toPx(), center = points[0])
            return@Canvas
        }

        // .interpolationMethod(.monotone): monotone cubic (Fritsch–Carlson) through the points.
        val slopes = (0 until count - 1).map { (points[it + 1].y - points[it].y) / (points[it + 1].x - points[it].x) }
        val tangents = FloatArray(count)
        tangents[0] = slopes[0]
        tangents[count - 1] = slopes[count - 2]
        for (i in 1 until count - 1) {
            tangents[i] = if (slopes[i - 1] * slopes[i] <= 0f) 0f else (slopes[i - 1] + slopes[i]) / 2
        }
        for (i in 0 until count - 1) {
            if (slopes[i] == 0f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
            } else {
                val a = tangents[i] / slopes[i]
                val b = tangents[i + 1] / slopes[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    tangents[i] = t * a * slopes[i]
                    tangents[i + 1] = t * b * slopes[i]
                }
            }
        }
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 0 until count - 1) {
            val dx = (points[i + 1].x - points[i].x) / 3
            path.cubicTo(
                points[i].x + dx, points[i].y + tangents[i] * dx,
                points[i + 1].x - dx, points[i + 1].y - tangents[i + 1] * dx,
                points[i + 1].x, points[i + 1].y,
            )
        }

        // AreaMark: accent 25% at the top fading to clear at the bottom of the plot.
        val area = Path()
        area.addPath(path)
        area.lineTo(points.last().x, top + plotHeight)
        area.lineTo(points.first().x, top + plotHeight)
        area.close()
        val highest = points.minOf { it.y }
        drawPath(area, Brush.verticalGradient(listOf(line.opacity(0.25), Color.Transparent),
                                              startY = min(highest, top + plotHeight - 1f), endY = top + plotHeight))
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
