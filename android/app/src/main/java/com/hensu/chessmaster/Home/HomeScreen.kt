// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The brief "get ready" card between tapping a tile and the board (the Swift tuple). */
internal class HomeMatchCard(val config: GameConfig, val title: String, val subtitle: String, val note: String?)

/**
 * HomeScreen's `@State`. Home is the Play tab's root and stays alive for the whole run on
 * iOS; here it leaves the composition whenever a game is pushed over it, so its state lives
 * in one process-wide holder (session-only level/color overrides, a report still generating,
 * the match card's timers all have to outlive that).
 */
internal class HomeScreenState {
    var pendingConfig by mutableStateOf<GameConfig?>(null)
    var resumeRecord by mutableStateOf<InProgressGameRecord?>(null)
    var pendingResume by mutableStateOf<InProgressGameRecord?>(null)
    var showCustom by mutableStateOf(false)
    var streaks by mutableStateOf(Streaks(playDays = 0, wins = 0))
    /** The brief "get ready" card between tapping a tile and the board. */
    var matchCard by mutableStateOf<HomeMatchCard?>(null)
    // @AppStorage: Profile holds the defaults.
    val engineLevel: Int get() = Defaults.standard.intOrNull("play.engineLevel") ?: 3
    val levelMode: String get() = Defaults.standard.string("play.levelMode") ?: "auto"
    val colorChoice: String get() = Defaults.standard.string("play.colorChoice") ?: "random"
    // Session-only overrides for "this game"; Profile holds the defaults.
    var levelOverride by mutableStateOf<Int?>(null)
    var colorOverride by mutableStateOf<String?>(null)
    var signupPrompt by mutableStateOf<SignupNudge.Prompt?>(null)
    var nudgeAccepted by mutableStateOf(false)
    var showNudgeAccountSheet by mutableStateOf(false)
    /**
     * Proactive first AI report (see AutoCoach): generated in the
     * background, presented only while Home is on screen.
     */
    var autoCoachRunning by mutableStateOf(false)
    var autoCoachPending by mutableStateOf<AutoCoach.Ready?>(null)
    var autoCoachShown by mutableStateOf<AutoCoach.Ready?>(null)
    var autoCoachAccepted by mutableStateOf(false)
    var isVisible by mutableStateOf(false)
    /**
     * Friend games: the invite sheet, games still open on the server, and
     * free months earned by inviting (Google Play offer codes).
     */
    var showFriendLobby by mutableStateOf(false)
    var openFriendGames by mutableStateOf<List<FriendGameState>>(emptyList())
    var waitingInvite by mutableStateOf<FriendGameState?>(null)
    var rewards by mutableStateOf<List<ReferralReward>>(emptyList())
    /** Bumped on every appear, so labels read from Defaults and the database are re-read. */
    var appearCount by mutableIntStateOf(0)

    /** The Play tab's stack (set by HomeScreen from `LocalNav`). */
    var nav: Navigator = AppRouter.shared.playNav

    private val container: DependencyContainer get() = DependencyContainer.shared

    private val autoStartConfig: GameConfig?
        get() {
            val arguments = App.arguments
            if (arguments.contains("--autostart-engine-game")) {
                return GameConfig(opponent = GameConfig.Opponent.Engine(level = 1))
            }
            if (arguments.contains("--autostart-game")) {
                return GameConfig(opponent = GameConfig.Opponent.HumanLocal)
            }
            return null
        }

    private fun recentGames(limit: Int? = null): List<GameRecord> =
        runCatching { if (limit == null) container.games.recentGames() else container.games.recentGames(limit) }
            .getOrNull() ?: emptyList()

    // .navigationDestination(item:): setting the item pushes its screen.

    fun open(config: GameConfig) {
        pendingConfig = config
        nav.push { GameScreen(config = config) }
    }

    fun open(record: InProgressGameRecord) {
        pendingResume = record
        nav.push { GameScreen(resume = record) }
    }

    fun onAppear() {
        // Back on Home: whatever was pushed has been popped (the item bindings are nil again).
        pendingConfig = null
        pendingResume = null
        appearCount += 1
        resumeRecord = runCatching { container.inProgress.load() }.getOrNull()
        val games = recentGames()
        streaks = Streaks.compute(games)
        val config = autoStartConfig
        if (config != null && pendingConfig == null) {
            open(config)
        }
        // Screenshots / UI tests: open the Play a Friend sheet directly.
        if (App.arguments.contains("--open-friend-lobby")) {
            showFriendLobby = true
        }
        if (BuildConfig.DEBUG) {
            FriendPreview.reward?.let { rewards = listOf(it) }
            FriendPreview.waitingGame?.let { waitingInvite = it }
        }
        evaluateSignupNudge()
        isVisible = true
        evaluateAutoCoach()
    }

    /**
     * Save-your-progress nudge: only once the session restore has settled,
     * and only when signed out AND there's something on this phone worth
     * keeping (see SignupNudge). Gating on `sessionRestored` is what stops
     * the nudge from flashing at an already-signed-in user at launch.
     */
    fun evaluateSignupNudge() {
        if (!(container.sync.sessionRestored && pendingConfig == null && signupPrompt == null)) return
        val games = recentGames()
        val prompt = SignupNudge.evaluate(
            games = games,
            isSignedIn = container.sync.isSignedIn,
            isConfigured = container.sync.isConfigured,
            rating = establishedRating(),
        ) ?: return
        signupPrompt = prompt
        container.sync.track("signup_nudge_shown", mapOf("trigger" to prompt.trigger))
    }

    /** `.sheet(item: $signupPrompt, onDismiss:)`. */
    fun signupPromptDismissed() {
        signupPrompt = null
        if (nudgeAccepted) {
            nudgeAccepted = false
            showNudgeAccountSheet = true
        } else {
            container.sync.track("signup_nudge_skipped")
        }
    }

    /**
     * Kicks off the proactive first report when due, and presents a
     * finished one once Home is visible with nothing else on top.
     */
    fun evaluateAutoCoach() {
        presentAutoCoachIfReady()
        if (container.sync.sessionRestored && !autoCoachRunning && autoCoachPending == null) {
            val due = CoachHabit.dueGame(container)
            if (due != null) {
                autoCoachRunning = true
                MainScope().launch {
                    autoCoachPending = CoachHabit.run(due, container)
                    autoCoachRunning = false
                    presentAutoCoachIfReady()
                }
                return
            }
        }
        if (!(container.sync.sessionRestored && !autoCoachRunning && autoCoachPending == null)) return
        val game = AutoCoach.candidate(games = recentGames(), container = container) ?: return
        autoCoachRunning = true
        MainScope().launch {
            autoCoachPending = AutoCoach.run(game, container)
            autoCoachRunning = false
            presentAutoCoachIfReady()
        }
    }

    private fun presentAutoCoachIfReady() {
        val ready = autoCoachPending
        if (!(ready != null && isVisible &&
                pendingConfig == null && pendingResume == null && signupPrompt == null &&
                autoCoachShown == null)) return
        autoCoachPending = null
        autoCoachShown = ready
        container.sync.track("auto_coach_shown")
    }

    /** `.sheet(item: $autoCoachShown, onDismiss:)`. */
    fun autoCoachDismissed() {
        autoCoachShown = null
        if (autoCoachAccepted) {
            autoCoachAccepted = false
        } else {
            container.sync.track("auto_coach_later")
        }
    }

    fun autoCoachOpened(ready: AutoCoach.Ready) {
        container.sync.track("auto_coach_opened")
        autoCoachAccepted = true
        autoCoachDismissed()
        val game = ready.game
        nav.push { AnalysisScreen(game = game, source = "auto_coach", scrollToCoaching = true) }
    }

    // MARK: - Per-game overrides (defaults live in Profile)

    val effectiveLevelLabel: String
        get() {
            levelOverride?.let { return StrengthLevel.level(it).displayName }
            if (levelMode == "manual") {
                return StrengthLevel.level(engineLevel).displayName
            }
            return "Auto · ${StrengthLevel.level(resolvedLevel(null).level).displayName}"
        }

    val effectiveColorLabel: String
        get() = (colorOverride ?: colorChoice).replaceFirstChar { it.uppercase() }

    // MARK: - Friends

    suspend fun refreshFriendState() {
        if (!container.sync.isSignedIn) return
        openFriendGames = container.sync.openFriendGames()
        val claimed = (Defaults.standard.stringArray(claimedRewardsKey) ?: emptyList()).toSet()
        val fresh = container.sync.unredeemedReferralRewards().filter { !claimed.contains(it.code) }
        for (reward in fresh) {
            if (!rewards.contains(reward)) {
                container.sync.track("referral_reward_shown", mapOf("role" to reward.role))
            }
        }
        rewards = fresh
        if (BuildConfig.DEBUG) {
            FriendPreview.reward?.let { rewards = listOf(it) }
        }
        retireRedeemedRewards()
        // GA user property: how many people this player has played.
        if (container.sync.userProperties["friends"] == null) {
            val count = container.sync.recentFriends().size.toString()
            container.sync.userProperties["friends"] = count
        }
    }

    fun accept(game: FriendGameState) {
        container.sync.track("friend_challenge_accepted")
        MainScope().launch {
            val state = try {
                container.sync.joinFriendGame(game.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (state != null && state.status == "active") {
                container.startFriendGame(state)
            }
            refreshFriendState()
        }
    }

    /**
     * The card stays until Google Play confirms the code was used (a current
     * subscription from an offer code) — backing out of the redeem page
     * keeps it.
     */
    suspend fun retireRedeemedRewards() {
        if (!(container.entitlements.redeemedOfferCode && rewards.isNotEmpty())) return
        for (reward in rewards) {
            container.sync.markReferralRedeemed(reward.code)
            container.sync.track("referral_redeemed", mapOf("role" to reward.role))
        }
        rewards = emptyList()
    }

    fun redeem(reward: ReferralReward) {
        container.sync.track("referral_redeem_tapped", mapOf("role" to reward.role))
        // Android: offer codes are redeemed on Google Play (iOS opens apps.apple.com/redeem).
        Platform.openURL("https://play.google.com/redeem?code=${reward.code}")
        // Already subscribed: the store holds the code until their next
        // renewal, so billing can't confirm it yet — retire on tap.
        if (container.entitlements.isPremium) {
            val claimed = Defaults.standard.stringArray(claimedRewardsKey) ?: emptyList()
            Defaults.standard.set(claimedRewardsKey, claimed + listOf(reward.code))
            rewards = rewards.filter { it.code != reward.code }
        }
    }

    fun start(timeControl: TimeControl?, haptics: Haptics) {
        // Deterministic color for UI tests; random breaks board assertions.
        val uiTesting = App.arguments.contains("--uitest")
        val choice = if (uiTesting) "white" else (colorOverride ?: colorChoice)
        val color: Piece.Color = when (choice) {
            "white" -> Piece.Color.white
            "black" -> Piece.Color.black
            else -> if (Random.nextBoolean()) Piece.Color.white else Piece.Color.black
        }
        val recommended = resolvedLevel(timeControl?.category)
        val level = levelOverride ?: recommended.level
        val config = GameConfig(
            playerColor = color,
            timeControl = timeControl,
            opponent = GameConfig.Opponent.Engine(level = level),
            // A manual pick means the player wants that exact strength.
            engineBlunderProbability = if (levelOverride == null) recommended.blunderProbability else 0.0,
        )
        if (uiTesting) {
            open(config)
            return
        }

        // The "get ready" beat: haptic, match card, then the board.
        haptics.impact()
        val title = timeControl?.label ?: "Untimed"
        val subtitle = "Level $level ${StrengthLevel.level(level).displayName} · You play ${if (color == Piece.Color.white) "White" else "Black"}"
        // Tell the player when Auto promoted or eased them since their last
        // rated game (training games don't count — their level is separate).
        var note: String? = null
        if (levelOverride == null && levelMode == "auto") {
            val previous = recentGames(limit = 10)
                .firstOrNull { it.opponentType == "engine" && it.termination != "aborted" && it.ratingCategory != null }
                ?.engineLevel
            if (previous != null && previous != level) {
                note = if (level > previous) "Level up! Promoted to ${StrengthLevel.level(level).displayName}"
                       else "Easing off — down to ${StrengthLevel.level(level).displayName}"
            }
        }
        matchCard = HomeMatchCard(config, title, subtitle, note)
        MainScope().launch {
            delay(900.milliseconds)
            open(config)
            delay(400.milliseconds)
            matchCard = null
        }
    }

    /**
     * The player's best established rating — only shown on the nudge when
     * it's a number worth bragging about.
     */
    private fun establishedRating(): Int? =
        container.ratings.ratings.values
            .filter { it.deviation < 250 }
            .map { it.rating.roundToInt() }
            .filter { it >= 400 }
            .maxOrNull()

    private fun resolvedLevel(category: TimeControl.Category?): AdaptiveLevel.Recommendation {
        if (levelMode != "auto") {
            return AdaptiveLevel.Recommendation(level = engineLevel, blunderProbability = 0.0)
        }
        // Untimed games share the classical bucket (that's how they're rated),
        // so Auto level is tracked per category, classical standing in for both.
        val cat = category ?: TimeControl.Category.classical
        val rating = container.ratings.rating(cat)
        val recent = recentGames(limit = 20)
        return AdaptiveLevel.level(cat, rating, recent)
    }

    companion object {
        /** Referral codes a subscriber already tapped to redeem (see HomeRewardCard). */
        private const val claimedRewardsKey = "referral.claimedCodes"
        /** Same gold as the AI coach's premium crown. */
        val offerGold = rgb(0.961, 0.769, 0.318)
    }
}

internal val homeScreenState = HomeScreenState()

/**
 * Lichess-style quick pairing: a grid of time-control tiles. Tapping a
 * tile starts a game against Stockfish immediately — no opponent picker.
 */
@Composable
fun HomeScreen() {
    val state = homeScreenState
    val container = DependencyContainer.shared
    val haptics = rememberHaptics()
    val nav = LocalNav.current
    state.nav = nav

    // onAppear / onDisappear: Home leaves the composition while a screen is pushed over it.
    LaunchedEffect(Unit) { state.onAppear() }
    DisposableEffect(Unit) { onDispose { state.isVisible = false } }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(container.activeFriendGame == null && !state.showFriendLobby, lifecycleOwner) {
        // Challenges arrive without push yet: re-check while Home is up.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                state.refreshFriendState()
                delay(15.seconds)
            }
        }
    }
    HomeOnChange(container.entitlements.redeemedOfferCode) {
        MainScope().launch { state.retireRedeemedRewards() }
    }
    // The nudge waits for the session restore to settle; when it does,
    // re-check (onAppear may have run while auth was still unknown).
    HomeOnChange(container.sync.sessionRestored) {
        state.evaluateSignupNudge()
        state.evaluateAutoCoach()
    }
    // Entitlements resolve asynchronously at launch, and a purchase can
    // land mid-session — either can make the first report due.
    HomeOnChange(container.entitlements.plan) { state.evaluateAutoCoach() }
    HomeOnChange(container.sync.isSignedIn) { state.evaluateAutoCoach() }

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold("Chess AI", modifier = Modifier.trackScreen("Home")) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.resumeRecord?.let { record ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Sys.secondaryGroupedBackground)
                            .clickable { state.open(record) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeLabel("Continue game", Icons.Rounded.PlayCircle)
                        Spacer(Modifier.weight(1f))
                        Text("${record.movesUCI.split(" ").count { it.isNotEmpty() }} moves",
                             style = Sys.footnote, color = Sys.secondary)
                    }
                }

                for (reward in state.rewards) {
                    HomeRewardCard(reward) { state.redeem(reward) }
                }

                for (game in state.openFriendGames) {
                    HomeFriendGameCard(game, state)
                }

                HomeRatingsRow()

                val streaks = state.streaks
                if (streaks.playDays >= 2 || streaks.wins >= 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (streaks.playDays >= 2) {
                            HomeStreakChip("🔥", "${streaks.playDays}-day streak", Modifier.weight(1f))
                        }
                        if (streaks.wins >= 2) {
                            HomeStreakChip("🏆", "${streaks.wins} wins in a row", Modifier.weight(1f))
                        }
                    }
                }

                HomeDailyPuzzleRow(state)

                // LazyVGrid: three flexible columns, 10 apart.
                val tiles = remember {
                    TimeControl.presets.map<TimeControl, Triple<String, String, () -> Unit>> { tc ->
                        Triple(tc.label, tc.category.rawValue.replaceFirstChar { it.uppercase() }) {
                            state.start(tc, haptics)
                        }
                    } + listOf(
                        Triple("∞", "Untimed") { state.start(null, haptics) },
                        Triple("Custom", "Time control") { state.showCustom = true },
                    )
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in tiles.chunked(3)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((top, bottom, action) in row) {
                                HomeTile(top, bottom, Modifier.weight(1f), action)
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                val gold = HomeScreenState.offerGold
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(gold.opacity(0.12))
                        .border(1.dp, gold.opacity(0.5), RoundedCornerShape(12.dp))
                        .clickable {
                            container.sync.track("friend_lobby_opened")
                            state.showFriendLobby = true
                        }
                        .testTag("playFriend")
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.Group, contentDescription = null, tint = Sys.green,
                         modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Play a Friend", style = Sys.headline, color = Sys.primary)
                        Text("Get 1 month of Diamond free",
                             style = Sys.subheadline.weight(FontWeight.SemiBold), color = gold)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Sys.tertiary,
                         modifier = Modifier.size(18.dp))
                }

                HomeGameOptionsBar(state)
            }
        }

        HomeMatchCardOverlay(state.matchCard)
    }

    if (state.showCustom) {
        Sheet(onDismiss = { state.showCustom = false }, large = false) {
            CustomTimeSheet(
                onStart = { tc ->
                    state.showCustom = false
                    state.start(tc, haptics)
                },
                onDismiss = { state.showCustom = false },
            )
        }
    }
    if (state.showFriendLobby) {
        Sheet(onDismiss = { state.showFriendLobby = false }) {
            FriendLobbySheet(onDismiss = { state.showFriendLobby = false })
        }
    }
    state.waitingInvite?.let { game ->
        Sheet(onDismiss = { state.waitingInvite = null }) {
            FriendLobbySheet(initial = game, onDismiss = { state.waitingInvite = null })
        }
    }
    state.autoCoachShown?.let { ready ->
        Sheet(onDismiss = { state.autoCoachDismissed() }) {
            AutoCoachCard(ready, onOpen = { state.autoCoachOpened(ready) }, onDismiss = { state.autoCoachDismissed() })
        }
    }
    state.signupPrompt?.let { prompt ->
        Sheet(onDismiss = { state.signupPromptDismissed() }) {
            SignupNudgeCard(
                prompt,
                onSignUp = {
                    container.sync.track("signup_nudge_accepted", mapOf("trigger" to prompt.trigger))
                    state.nudgeAccepted = true
                    state.signupPromptDismissed()
                },
                onDismiss = { state.signupPromptDismissed() },
            )
        }
    }
    if (state.showNudgeAccountSheet) {
        Sheet(onDismiss = { state.showNudgeAccountSheet = false }) {
            AccountSheet(sync = container.sync, onDismiss = { state.showNudgeAccountSheet = false })
        }
    }
}

/** `.onChange(of: value) { }`: runs when the value changes, not on first appearance. */
@Composable
private fun <T> HomeOnChange(value: T, block: () -> Unit) {
    var previous by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (previous != value) {
            previous = value
            block()
        }
    }
}
