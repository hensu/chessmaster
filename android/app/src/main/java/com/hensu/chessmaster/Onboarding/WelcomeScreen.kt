// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

private enum class WelcomeStep {
    landing,
    goal, experience, rating, opening, midgame, endgame, hero, rivals, source,
    computing, score, promise, signup, askName, paywall;   // askName is Swift's `.name` (an enum entry cannot be called `name`)

    val rawValue: Int get() = ordinal
    /** String(describing: step): the Swift case name. */
    val label: String get() = if (this == askName) "name" else name
}

/** Color(.systemGray3) / Color(.systemGray4) */
private val welcomeGray3: Color get() = if (Sys.dark) Color(0xFF48484A) else Color(0xFFC7C7CC)
private val welcomeGray4: Color get() = if (Sys.dark) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)

/** One quiz option: (icon, label, value). */
private data class WelcomeOption(val icon: String, val label: String, val value: String)

/**
 * Cal AI-style onboarding: one question per screen (back arrow + progress
 * bar, big left-aligned headline, option cards, pinned capsule CTA), a
 * computed "chess score" reveal, a promise, then account creation. The
 * quiz runs once; sign-in greets every signed-out launch after that.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val container = DependencyContainer.shared
    val scope = rememberCoroutineScope()

    var quizDone by appStorageBool("onboarding.quizDone")
    var selfRating by appStorageInt("onboarding.selfRating")
    val goal = appStorageString("onboarding.goal")

    var step by remember {
        // UI tests: always run the full quiz, even on a reused device.
        if (App.arguments.contains("--reset-welcome")) {
            quizDone = false
        }
        var initial = if (quizDone) WelcomeStep.signup else WelcomeStep.landing
        if (BuildConfig.DEBUG) {
            // Screenshots: jump to a quiz step, e.g. --onboarding-step=source
            val arg = App.arguments.firstOrNull { it.startsWith("--onboarding-step=") }
            if (arg != null) {
                WelcomeStep.entries.firstOrNull { "--onboarding-step=${it.label}" == arg }?.let { initial = it }
            }
        }
        mutableStateOf(initial)
    }

    // Quiz answers.
    val experience = remember { mutableStateOf("") }
    var sliderRating by remember { mutableDoubleStateOf(800.0) }
    var notSure by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf("") }
    val midgame = remember { mutableStateOf("") }
    val endgame = remember { mutableStateOf("") }
    val hero = remember { mutableStateOf("") }
    val rivals = remember { mutableStateOf("") }
    val source = remember { mutableStateOf("") }

    // Computing animation.
    var computeProgress by remember { mutableDoubleStateOf(0.0) }
    var computedScore by remember { mutableIntStateOf(0) }
    /** The number on the score reveal while it counts up (see `revealScore`). */
    var shownScore by remember { mutableIntStateOf(0) }

    // Cal AI tone: quiz is always light (.preferredColorScheme(.light)). `Sys.dark` is owned by
    // RootView, which rewrites it on every recomposition, so RootView has to keep it false
    // while this cover is up (`Sys.dark = dark && !welcomePresented`).

    /**
     * Sign-up is required; after it (and the name), show the soft paywall
     * unless they already have a subscription.
     */
    fun afterName() {
        if (container.entitlements.isPremium) {
            onDone()
        } else {
            step = WelcomeStep.paywall
        }
    }

    fun advance(next: WelcomeStep) {
        container.sync.track("quiz_step", mapOf("step" to step.label))
        step = next
    }

    // MARK: - Computing + score reveal

    fun startComputing() {
        container.sync.track("quiz_step", mapOf("step" to "source"))
        container.sync.track("acquisition_source", mapOf("source" to source.value))
        computedScore = welcomeEstimateScore(
            notSure = notSure, slider = sliderRating.toInt(), experience = experience.value,
            opening = opening, midgame = midgame.value, endgame = endgame.value,
        )
        selfRating = computedScore
        step = WelcomeStep.computing
        container.sync.track("quiz_completed", mapOf(
            "goal" to goal.value, "experience" to experience.value,
            "rating_known" to if (notSure) "no" else "yes",
            "self_rating" to if (notSure) "" else sliderRating.toInt().toString(),
            "opening" to opening, "midgame" to midgame.value, "endgame" to endgame.value,
            "hero" to hero.value, "rivals" to rivals.value, "source" to source.value,
            "computed_score" to computedScore.toString(),
        ))
        scope.launch {
            // ~5s so the % counter and checklist read as real work.
            for (tick in 1..50) {
                delay(100)
                computeProgress = tick / 50.0
            }
            delay(500)
            step = WelcomeStep.score
        }
    }

    /**
     * Tease the reveal: climb slowly and stall ~200 below, then rush past
     * the real score and settle back onto it.
     */
    suspend fun revealScore() {
        val target = computedScore
        val stall = max(target - 200, 100)
        shownScore = max(stall - 500, 0)
        suspend fun run(end: Int, steps: Int, ms: Int, easeOut: Boolean) {
            val start = shownScore
            for (i in 1..steps) {
                delay(ms.toLong())
                var t = i.toDouble() / steps.toDouble()
                if (easeOut) t = 1 - (1 - t) * (1 - t)
                shownScore = start + ((end - start).toDouble() * t).roundToInt()
            }
        }
        run(stall, steps = 30, ms = 60, easeOut = true)            // slow climb
        delay(700)                                                 // "is that it?"
        run(target + 40, steps = 10, ms = 35, easeOut = false)     // rush past
        delay(200)
        run(target, steps = 6, ms = 60, easeOut = true)            // settle
    }

    // MARK: - Quiz chrome

    @Composable
    fun quiz(
        current: WelcomeStep,
        title: String,
        subtitle: String? = null,
        selection: MutableState<String>,
        options: List<WelcomeOption>,
        onContinue: () -> Unit,
    ) {
        QuizScaffold(
            progress = current.rawValue / 10.0,
            title = title,
            subtitle = subtitle,
            canContinue = selection.value.isNotEmpty(),
            onBack = {
                container.sync.track("quiz_back", mapOf("from" to step.label))
                step = WelcomeStep.entries.getOrNull(step.rawValue - 1) ?: WelcomeStep.landing
            },
            onContinue = onContinue,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (option in options) {
                    OptionCard(
                        icon = option.icon,
                        label = option.label,
                        selected = selection.value == option.value,
                    ) {
                        selection.value = option.value
                    }
                }
            }
        }
    }

    AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize().background(Sys.background),
        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(350)) },
        label = "welcomeStep",
    ) { shown ->
        // The name and paywall steps handle the system bars themselves.
        val inset = if (shown == WelcomeStep.askName || shown == WelcomeStep.paywall) Modifier else Modifier.systemBarsPadding()
        Box(Modifier.fillMaxSize().then(inset)) {
            when (shown) {
                WelcomeStep.landing ->
                    LandingScreen(
                        onGetStarted = { advance(WelcomeStep.goal) },
                        onSignedIn = {
                            // Existing account — no quiz, no onboarding paywall.
                            quizDone = true
                            onDone()
                        },
                    )
                WelcomeStep.goal ->
                    quiz(shown, title = "Do you want to get better at chess?", selection = goal, options = listOf(
                        WelcomeOption("flame.fill", "Yes — that's why I'm here", "improve"),
                        WelcomeOption("gamecontroller.fill", "I just want to play", "play"),
                    )) { advance(WelcomeStep.experience) }
                WelcomeStep.experience ->
                    quiz(shown, title = "How long have you been playing?", selection = experience, options = listOf(
                        WelcomeOption("leaf.fill", "I'm new to chess", "new"),
                        WelcomeOption("clock.fill", "A year or two", "some"),
                        WelcomeOption("crown.fill", "Many years", "years"),
                    )) { advance(WelcomeStep.rating) }
                WelcomeStep.rating ->
                    // ratingPage
                    QuizScaffold(
                        progress = shown.rawValue / 10.0,
                        title = "Do you know your rating?",
                        subtitle = "From chess.com, lichess, or a club.",
                        canContinue = true,
                        onBack = {
                            container.sync.track("quiz_back", mapOf("from" to "rating"))
                            step = WelcomeStep.experience
                        },
                        onContinue = { advance(WelcomeStep.opening) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            OptionCard(icon = "questionmark.circle", label = "I've never had a rating",
                                       selected = notSure) { notSure = true }
                            OptionCard(icon = "number.circle.fill", label = "Yes — let me set it",
                                       selected = !notSure) { notSure = false }
                            if (!notSure) {
                                Text("${sliderRating.toInt()}", style = Sys.font(44, FontWeight.Bold), color = Sys.primary)
                                WelcomeSlider(sliderRating) { sliderRating = it }
                            }
                        }
                    }
                WelcomeStep.opening ->
                    // openingPage. First move, pictured: each choice shows the move on a mini board.
                    // Nothing is pre-selected.
                    QuizScaffold(
                        progress = shown.rawValue / 10.0,
                        title = "What's your go-to first move?",
                        subtitle = "This helps calibrate your chess score.",
                        canContinue = opening.isNotEmpty(),
                        onBack = {
                            container.sync.track("quiz_back", mapOf("from" to "opening"))
                            step = WelcomeStep.rating
                        },
                        onContinue = { advance(WelcomeStep.midgame) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MoveOptionCard(title = "e4", subtitle = "King's pawn, two squares",
                                           move = "e2" to "e4", selected = opening == "e4") { opening = "e4" }
                            MoveOptionCard(title = "d4", subtitle = "Queen's pawn, two squares",
                                           move = "d2" to "d4", selected = opening == "d4") { opening = "d4" }
                            MoveOptionCard(title = "Nf3 / c4", subtitle = "Knight out first, or something spicy",
                                           move = "g1" to "f3", selected = opening == "hypermodern") { opening = "hypermodern" }
                            MoveOptionCard(title = "No idea what those mean", subtitle = "Totally fine, we'll teach you",
                                           move = null, selected = opening == "none") { opening = "none" }
                        }
                    }
                WelcomeStep.midgame ->
                    quiz(shown, title = "How does your middlegame feel?", selection = midgame, options = listOf(
                        WelcomeOption("exclamationmark.triangle.fill", "I keep losing pieces", "loses"),
                        WelcomeOption("arrow.left.arrow.right", "I trade evenly, then drift", "trades"),
                        WelcomeOption("target", "I make plans and attack", "plans"),
                    )) { advance(WelcomeStep.endgame) }
                WelcomeStep.endgame ->
                    quiz(shown, title = "And endgames?", selection = endgame, options = listOf(
                        WelcomeOption("questionmark.circle", "What's an endgame?", "what"),
                        WelcomeOption("checkmark.circle", "I can mate with a queen", "basic"),
                        WelcomeOption("trophy.fill", "I convert winning positions", "converts"),
                    )) { advance(WelcomeStep.hero) }
                WelcomeStep.hero ->
                    quiz(shown, title = "Which player do you vibe with?",
                         subtitle = "We'll shape your coach's advice around your style.",
                         selection = hero, options = listOf(
                        WelcomeOption("globe", "Magnus — universal, plays everything", "magnus"),
                        WelcomeOption("bolt.fill", "Tal — attack, always attack", "tal"),
                        WelcomeOption("scalemass", "Capablanca — clean and simple", "capablanca"),
                        WelcomeOption("person.fill", "My own style", "own"),
                    )) { advance(WelcomeStep.rivals) }
                WelcomeStep.rivals ->
                    quiz(shown, title = "Who do you want to beat?", selection = rivals, options = listOf(
                        WelcomeOption("person.2.fill", "My friends", "friends"),
                        WelcomeOption("network", "Everyone online", "online"),
                        WelcomeOption("figure.mind.and.body", "Myself — I just want to improve", "self"),
                    )) { advance(WelcomeStep.source) }
                WelcomeStep.source ->
                    // Android: the store option is Google Play (iOS: "App Store search" / "app_store").
                    quiz(shown, title = "How did you hear about us?", selection = source, options = listOf(
                        WelcomeOption("brand-appstore", "Google Play search", "google_play"),
                        WelcomeOption("person.2.fill", "A friend", "friend"),
                        WelcomeOption("brand-tiktok", "TikTok", "tiktok"),
                        WelcomeOption("brand-instagram", "Instagram", "instagram"),
                        WelcomeOption("brand-youtube", "YouTube", "youtube"),
                        WelcomeOption("brand-google", "Google", "google"),
                        WelcomeOption("brand-reddit", "Reddit", "reddit"),
                        WelcomeOption("ellipsis.circle", "Something else", "other"),
                    )) { startComputing() }
                WelcomeStep.computing ->
                    // computingPage
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        Text("${(computeProgress * 100).toInt()}%", style = Sys.font(64, FontWeight.Bold), color = Sys.primary)
                        Text("Calculating your chess score", style = Sys.title2.bold(), color = Sys.primary,
                             modifier = Modifier.padding(top = 4.dp))
                        WelcomeProgressBar(computeProgress, Modifier.padding(horizontal = 40.dp).padding(top = 22.dp))
                        Column(
                            Modifier.padding(horizontal = 28.dp).padding(top = 30.dp).fillMaxWidth()
                                .background(Sys.secondaryBackground, RoundedCornerShape(18.dp)).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ComputeRow("Openings", done = computeProgress > 0.25)
                            ComputeRow("Middlegame", done = computeProgress > 0.5)
                            ComputeRow("Endgame", done = computeProgress > 0.75)
                            ComputeRow("Playing style", done = computeProgress >= 1)
                        }
                        Spacer(Modifier.weight(2f))
                    }
                WelcomeStep.score ->
                    // scorePage
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        Text("Your chess score", style = Sys.title2.bold(), color = Sys.primary)
                        LaunchedEffect(Unit) { revealScore() }
                        Text("$shownScore", style = Sys.font(84, FontWeight.Bold), color = Sys.primary,
                             modifier = Modifier.padding(top = 4.dp))
                        Text("We'll start Chess AI at your level — hard enough to learn from, easy enough to beat. It adapts every game.",
                             style = Sys.callout, color = Sys.secondary, textAlign = TextAlign.Center,
                             modifier = Modifier.padding(horizontal = 36.dp).padding(top = 14.dp))
                        Spacer(Modifier.weight(1f))
                        CapsuleCTA(label = "Continue", enabled = true) {
                            step = WelcomeStep.promise
                        }
                    }
                WelcomeStep.promise ->
                    // promisePage
                    Column(Modifier.fillMaxSize()) {
                        Text("Our promise", style = Sys.font(34, FontWeight.Bold), color = Sys.primary,
                             modifier = Modifier.padding(horizontal = 28.dp).padding(top = 60.dp))
                        Text("Play 10 games with your AI coach and your score will be measurably higher.",
                             style = Sys.title3, color = Sys.secondary,
                             modifier = Modifier.padding(horizontal = 28.dp).padding(top = 10.dp))
                        Column(
                            Modifier.padding(horizontal = 28.dp).padding(top = 28.dp).fillMaxWidth()
                                .background(Sys.secondaryBackground, RoundedCornerShape(18.dp)).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PromiseRow("magnifyingglass", "Every mistake found and shown on the board")
                            PromiseRow("arrow.counterclockwise", "Replay your mistakes until you get them right")
                            PromiseRow("chart.line.uptrend.xyaxis", "Your score tracks every game")
                        }
                        Spacer(Modifier.weight(1f))
                        CapsuleCTA(label = "Let's go", enabled = true) {
                            quizDone = true
                            step = WelcomeStep.signup
                        }
                    }
                WelcomeStep.signup -> {
                    LaunchedEffect(Unit) { container.sync.track("signup_viewed") }
                    SignupPage(
                        onSignedIn = {
                            if (container.sync.displayName == null) {
                                step = WelcomeStep.askName
                            } else {
                                afterName()
                            }
                        },
                        // Existing account — straight into the app, no paywall.
                        onExistingSignIn = onDone,
                    )
                }
                WelcomeStep.askName ->
                    NameScreen(source = "onboarding", onDone = { afterName() })
                WelcomeStep.paywall ->
                    AppPaywall(source = "onboarding", onClose = onDone, closeDelay = 6.seconds)
            }
        }
    }
}

/** Rating estimate from the quiz signals (the slider wins when given). `WelcomeScreen.estimateScore` in Swift. */
fun welcomeEstimateScore(
    notSure: Boolean, slider: Int, experience: String,
    opening: String, midgame: String, endgame: String,
): Int {
    if (!notSure) return slider
    var score = when (experience) {
        "some" -> 750
        "years" -> 1000
        else -> 450
    }
    val openingBonus = when (opening) {
        "e4", "d4" -> 80
        "hypermodern" -> 140
        else -> -60
    }
    val midgameBonus = when (midgame) {
        "trades" -> 40
        "plans" -> 140
        else -> -80
    }
    val endgameBonus = when (endgame) {
        "basic" -> 40
        "converts" -> 140
        else -> -80
    }
    score += openingBonus + midgameBonus + endgameBonus
    return min(2200, max(400, score))
}

@Composable
private fun ComputeRow(label: String, done: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Sys.subheadline, color = Sys.primary)
        Spacer(Modifier.weight(1f))
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
             tint = if (done) Sys.primary else Sys.tertiary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PromiseRow(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            Icon(welcomeIcon(icon), contentDescription = null, tint = Sys.primary, modifier = Modifier.size(22.dp))
        }
        Text(text, style = Sys.subheadline, color = Sys.primary)
    }
}

/** SF Symbol name → the nearest Material icon. */
private fun welcomeIcon(name: String): ImageVector = when (name) {
    "flame.fill" -> Icons.Rounded.LocalFireDepartment
    "gamecontroller.fill" -> Icons.Rounded.SportsEsports
    "leaf.fill" -> Icons.Rounded.Eco
    "clock.fill" -> Icons.Rounded.Schedule
    "crown.fill" -> CrownFill
    "exclamationmark.triangle.fill" -> Icons.Rounded.Warning
    "arrow.left.arrow.right" -> Icons.Rounded.SwapHoriz
    "target" -> Icons.Rounded.TrackChanges
    "questionmark.circle" -> Icons.AutoMirrored.Rounded.HelpOutline
    "checkmark.circle" -> Icons.Rounded.CheckCircleOutline
    "trophy.fill" -> Icons.Rounded.EmojiEvents
    "globe" -> Icons.Rounded.Language
    "bolt.fill" -> Icons.Rounded.Bolt
    "scalemass" -> Icons.Rounded.Balance
    "person.fill" -> Icons.Rounded.Person
    "person.2.fill" -> Icons.Rounded.Group
    "network" -> Icons.Rounded.Hub
    "figure.mind.and.body" -> Icons.Rounded.SelfImprovement
    "number.circle.fill" -> Icons.Rounded.Tag
    "magnifyingglass" -> Icons.Rounded.Search
    "arrow.counterclockwise" -> Icons.Rounded.Replay
    "chart.line.uptrend.xyaxis" -> Icons.AutoMirrored.Rounded.TrendingUp
    "envelope" -> Icons.Rounded.MailOutline
    else -> Icons.Rounded.MoreHoriz   // ellipsis.circle
}

/** Full-color brand logo for an option icon named "brand-…" (res/drawable-nodpi). */
private fun welcomeBrand(name: String): Int = when (name) {
    "brand-appstore" -> R.drawable.brand_appstore
    "brand-tiktok" -> R.drawable.brand_tiktok
    "brand-instagram" -> R.drawable.brand_instagram
    "brand-youtube" -> R.drawable.brand_youtube
    "brand-reddit" -> R.drawable.brand_reddit
    else -> R.drawable.brand_google
}

/** ProgressView(value:).tint(.primary): a thin capsule bar. */
@Composable
private fun WelcomeProgressBar(progress: Double, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.toFloat().coerceIn(0f, 1f), tween(350), label = "progress")
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Sys.primary.opacity(0.12))) {
        Box(Modifier.fillMaxWidth(shown).height(4.dp).clip(CircleShape).background(Sys.primary))
    }
}

/** Slider(value:, in: 400...2200, step: 50).tint(.primary) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeSlider(value: Double, onChange: (Double) -> Unit) {
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(((it / 50f).roundToInt() * 50).toDouble()) },
        valueRange = 400f..2200f,
        steps = (2200 - 400) / 50 - 1,
        colors = SliderDefaults.colors(
            thumbColor = Sys.primary,
            activeTrackColor = Sys.primary,
            inactiveTrackColor = Sys.primary.opacity(0.12),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

// MARK: - Cal AI-style building blocks

/** Back arrow + progress bar, left headline, content, pinned capsule CTA. */
@Composable
private fun QuizScaffold(
    progress: Double,
    title: String,
    subtitle: String? = null,
    canContinue: Boolean,
    onBack: (() -> Unit)? = null,
    onContinue: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The system back button is the back arrow.
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 20.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Sys.secondaryBackground).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Sys.primary,
                         modifier = Modifier.size(22.dp))
                }
            } else {
                Spacer(Modifier.size(44.dp))
            }
            WelcomeProgressBar(progress, Modifier.weight(1f))
        }

        Text(title, style = Sys.font(34, FontWeight.Bold), color = Sys.primary,
             modifier = Modifier.padding(horizontal = 28.dp).padding(top = 24.dp))
        if (subtitle != null) {
            Text(subtitle, style = Sys.callout, color = Sys.secondary,
                 modifier = Modifier.padding(horizontal = 28.dp).padding(top = 6.dp))
        }

        // Spacer(minLength: 20): next to a ScrollView the spacer stays at its minimum.
        Spacer(Modifier.height(20.dp))

        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(horizontal = 24.dp)) { content() }
        }

        CapsuleCTA(label = "Continue", enabled = canContinue, action = onContinue)
    }
}

/**
 * White option card: icon in a soft circle, label, trailing radio;
 * selected = bold border + filled radio.
 */
@Composable
private fun OptionCard(icon: String, label: String, selected: Boolean, action: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Sys.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) Sys.primary else welcomeGray4, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { action() }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon.startsWith("brand-")) {
            // Full-color logo from the drawables (iOS: asset catalog Brands/).
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(welcomeBrand(icon)), contentDescription = null,
                      contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp))
            }
        } else {
            Box(Modifier.size(44.dp).background(Sys.secondaryBackground, CircleShape), contentAlignment = Alignment.Center) {
                Icon(welcomeIcon(icon), contentDescription = null, tint = Sys.primary, modifier = Modifier.size(22.dp))
            }
        }
        Text(label, style = Sys.body.weight(FontWeight.Medium), color = Sys.primary, modifier = Modifier.weight(1f))
        Icon(if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
             tint = if (selected) Sys.primary else Sys.tertiary, modifier = Modifier.size(24.dp))
    }
}

/** Quiz card for a first move: name + plain-English line, mini board right. */
@Composable
private fun MoveOptionCard(
    title: String,
    subtitle: String,
    move: Pair<String, String>?,
    selected: Boolean,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Sys.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) Sys.primary else welcomeGray4, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { action() }
            .padding(vertical = if (move == null) 18.dp else 12.dp)
            .padding(start = 18.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Sys.primary,
                 style = if (move == null) Sys.body.weight(FontWeight.SemiBold) else Sys.title3.weight(FontWeight.Bold))
            Text(subtitle, style = Sys.subheadline, color = Sys.secondary)
        }
        if (move != null) {
            MiniMoveBoard(from = move.first, to = move.second, square = 9f)
        }
    }
}

/** The pinned Cal AI CTA: full-width black capsule. */
@Composable
fun CapsuleCTA(label: String, enabled: Boolean, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(
        modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).fillMaxWidth()
            .clip(CircleShape).background(if (enabled) Sys.primary else welcomeGray3)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { action() }
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Sys.headline, color = Sys.background)
    }
}

/**
 * Account creation, Cal AI-style: left headline, black pill first,
 * outlined pills below. On Android Google comes first and Apple second.
 *
 * `onSignedIn`: sign-up is required to continue — called once a session exists.
 * `onExistingSignIn`: "Already have an account? Sign In" — skips the onboarding paywall.
 */
@Composable
private fun SignupPage(onSignedIn: () -> Unit, onExistingSignIn: () -> Unit) {
    val container = DependencyContainer.shared
    val scope = rememberCoroutineScope()

    var showEmailSheet by remember { mutableStateOf(false) }
    var showSignInSheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Google and Apple finish in the browser and come back later, so the session is observed;
    // whichever path notices it first moves on, once.
    var finished by remember { mutableStateOf(false) }
    val wasSignedIn = remember { container.sync.isSignedIn }
    fun finish(existing: Boolean) {
        if (finished) return
        finished = true
        showEmailSheet = false
        showSignInSheet = false
        if (existing) onExistingSignIn() else onSignedIn()
    }
    val signedIn = container.sync.isSignedIn
    LaunchedEffect(signedIn) {
        if (signedIn && !wasSignedIn) finish(existing = showSignInSheet)
    }

    Column(Modifier.fillMaxSize()) {
        Text("Save your progress", style = Sys.font(34, FontWeight.Bold), color = Sys.primary,
             modifier = Modifier.padding(horizontal = 28.dp).padding(top = 70.dp))
        Text("Every game, your score and coaching reports — synced automatically.",
             style = Sys.callout, color = Sys.secondary,
             modifier = Modifier.padding(horizontal = 28.dp).padding(top = 8.dp))

        Spacer(Modifier.weight(1f))

        Box(Modifier.padding(horizontal = 24.dp).padding(bottom = 34.dp), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SignupPill(label = "Sign up with Google", icon = "globe", enabled = !busy) {
                    container.sync.track("signup_method_tapped", mapOf("method" to "google"))
                    busy = true
                    scope.launch {
                        GoogleSignInHelper.signIn(container.sync)
                        busy = false
                        if (container.sync.isSignedIn) finish(existing = false)
                    }
                }

                AppleSignInButton(sync = container.sync, onSignedIn = { finish(existing = false) },
                                  modifier = Modifier.height(54.dp).clip(CircleShape))

                SignupPill(label = "Sign up with email", icon = "envelope", enabled = !busy) {
                    container.sync.track("signup_method_tapped", mapOf("method" to "email"))
                    showEmailSheet = true
                }

                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Already have an account?", style = Sys.subheadline, color = Sys.secondary)
                    Text("Sign In", style = Sys.subheadline.weight(FontWeight.SemiBold), color = Sys.primary,
                         modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = !busy) {
                             container.sync.track("signup_method_tapped", mapOf("method" to "existing"))
                             showSignInSheet = true
                         })
                }
            }
            if (busy) CircularProgressIndicator(color = Sys.secondary, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
        }
    }
    if (showEmailSheet) {
        val close = {
            showEmailSheet = false
            if (container.sync.isSignedIn) finish(existing = false)
        }
        Sheet(onDismiss = close) { AccountSheet(sync = container.sync, onDismiss = close) }
    }
    if (showSignInSheet) {
        val close = {
            val signedInNow = container.sync.isSignedIn
            if (signedInNow) finish(existing = true)
            showSignInSheet = false
        }
        Sheet(onDismiss = close) { AccountSheet(sync = container.sync, onDismiss = close) }
    }
}

@Composable
private fun SignupPill(label: String, icon: String, enabled: Boolean, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).border(1.dp, welcomeGray3, CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { action() }
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(welcomeIcon(icon), contentDescription = null, tint = Sys.primary, modifier = Modifier.size(20.dp))
        Text(label, style = Sys.headline, color = Sys.primary)
    }
}
