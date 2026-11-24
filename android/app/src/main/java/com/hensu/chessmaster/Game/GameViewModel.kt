// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The before/after pair of a rated game (the Swift `(before:, after:)` tuple). */
data class GameRatingChange(val before: Glicko2Rating, val after: Glicko2Rating)

class GameViewModel(
    config: GameConfig,
    /** Pass `resume` to continue a previously saved in-progress game. */
    resume: InProgressGameRecord? = null,
    /** Live friend game: the server link (moves, clocks, endings). */
    val friend: FriendGameLink? = null,
) {
    val session: GameSession = GameSession(config)
    val clock: ChessClock?
    var orientation: Piece.Color by mutableStateOf(config.playerColor)
    var showResignConfirm by mutableStateOf(false)
    var showGameOver by mutableStateOf(false)
    /**
     * Replay-training context: coach's advice pinned during play, and the
     * original game's outcome for the then-vs-now comparison at the end.
     */
    var trainingAdvice: String? by mutableStateOf(null)
    var trainingOriginal: String? by mutableStateOf(null)
    /** Retry-training board arrows (red = played, green = suggested). */
    var trainingArrows: List<BoardArrow> by mutableStateOf(emptyList())
    /** Aborts idle games: no first move from the player within 20 seconds. */
    private var firstMoveAbortTask: Job? = null
    var rematchRequested by mutableStateOf(false)
    /** Set after a rated (engine) game ends. */
    var ratingChange: GameRatingChange? by mutableStateOf(null)
        private set
    /** Set once the finished game has been persisted (drives "Analyze"). */
    var savedGameRecord: GameRecord? by mutableStateOf(null)
        private set
    /**
     * Post-game insights: set once the automatic blunder-check finishes,
     * with the record carrying its fresh analysisJSON.
     */
    var postGameInsights: GameRecord? by mutableStateOf(null)
        private set
    var preparingInsights by mutableStateOf(false)
        private set

    private var container: DependencyContainer? = null
    private var eventTask: Job? = null
    private var lowTimeTask: Job? = null
    /** Warms the eval cache during play so post-game analysis opens fast. */
    private var prefetcher: AnalysisPrefetcher? = null
    private val gameID = UUID.randomUUID().toString()
    /**
     * The ending came from the server (friend resigned / flagged / aborted),
     * so there's nothing to report back.
     */
    private var endedByServer = false
    private val startedAt = Instant.now()
    /**
     * How this game began, for analytics: fresh start, resumed save, or
     * mistake-retry training.
     */
    private val startSource: String =
        if (friend != null) "friend" else if (resume != null) "resume"
        else if (config.startFEN != null) "training" else "new"

    private val scope = MainScope()

    private val audio: ChessAudioPlayer? get() = container?.audio

    init {
        if (resume != null && resume.movesUCI.isNotEmpty()) {
            session.replay(resume.movesUCI.split(" ").filter { it.isNotEmpty() })
        }
        // Rejoining a friend game already under way.
        if (friend != null && friend.state.moves.isNotEmpty()) {
            session.replay(friend.state.moves)
        }

        val tc = config.timeControl
        if (tc != null) {
            val restored = mutableMapOf<ClockSide, Duration>()
            resume?.whiteRemainingMs?.let { restored[ClockSide.white] = it.milliseconds }
            resume?.blackRemainingMs?.let { restored[ClockSide.black] = it.milliseconds }
            val clock = ChessClock(initial = tc.initial, increment = tc.increment, restoredRemaining = restored)
            this.clock = clock
            session.clockSnapshot = { color -> clock.remaining(color.clockSide) }
            clock.onFlag = { side ->
                if (friend != null) {
                    // The server's clock decides; its verdict ends the game.
                    scope.launch { friend.claimTimeout() }
                } else {
                    session.flag(if (side == ClockSide.white) Piece.Color.white else Piece.Color.black)
                }
            }
        } else {
            this.clock = null
        }

        val opponent = config.opponent
        if (opponent is GameConfig.Opponent.Engine) {
            session.opponentProvider = EngineOpponent(
                level = opponent.level,
                blunderProbability = config.engineBlunderProbability,
            )
        }
        if (friend != null) {
            session.opponentProvider = FriendOpponent(friend)
        }
    }

    val isFriendGame: Boolean get() = friend != null

    /**
     * Fresh server state for a friend game: adopt its clocks, and end the
     * game here if it ended there (resignation, timeout, abort).
     */
    private fun applyFriendState(state: FriendGameState) {
        if (!session.status.isPlaying) return
        val result = state.domainResult
        if (state.isOver && result != null) {
            endedByServer = true
            when {
                result is GameResult.Win && result.termination == GameResult.Termination.resignation ->
                    session.resign(result.color.opposite)
                result is GameResult.Win && result.termination == GameResult.Termination.timeout ->
                    session.flag(result.color.opposite)
                result is GameResult.Aborted -> session.abort()
                else -> {}   // mate/draws: the board reaches them itself
            }
            if (session.status.isPlaying) endedByServer = false
            return
        }
        val clock = clock ?: return
        if (state.moves.size != session.moveHistory.size) return
        if (state.moves.size >= 2 && clock.running == null) {
            clock.start(session.sideToMove.clockSide)
        }
        clock.sync(
            white = state.whiteMs.milliseconds,
            black = state.blackMs.milliseconds,
            runningElapsed = state.turnElapsedMs.milliseconds,
        )
    }

    /** Reports a local ending (resign, mate, draw, abort) to the server. */
    private fun reportFriendEnd(result: GameResult) {
        val friend = friend ?: return
        if (endedByServer) return
        val termination: String = when {
            result is GameResult.Aborted -> "aborted"
            result is GameResult.Win && result.termination == GameResult.Termination.timeout ->
                return   // claimed via the clock
            else -> result.terminationString ?: "agreement"
        }
        scope.launch { friend.end(termination) }
    }

    fun attach(container: DependencyContainer) {
        this.container = container
    }

    fun start() {
        if (session.status != GameStatus.Idle) return
        eventTask = scope.launch {
            session.events.collect { event -> handle(event) }
        }
        if (friend != null) {
            friend.onState = { state -> applyFriendState(state) }
            friend.start()
        }
        session.start()

        // Lichess-style abort: a game nobody starts playing shouldn't count.
        // Training replays are exempt (thinking there is the whole point),
        // and friend games have an Abort button instead.
        if (!isTraining && !isFriendGame) {
            firstMoveAbortTask = scope.launch {
                delay(20.seconds)
                if (!session.status.isPlaying) return@launch
                val userMoved = session.moveHistory.any { session.isUserControlled(it.mover) }
                if (!userMoved) session.abort()
            }
        }
    }

    private fun handle(event: GameEvent) {
        when (event) {
            is GameEvent.GameStarted -> {
                audio?.play(SoundEffect.gameStart)
                // Friend games: each side's first move is free (server rule);
                // the clock starts once both have moved.
                if (!isFriendGame || session.moveHistory.size >= 2) {
                    clock?.start(session.sideToMove.clockSide)
                }
                friend?.let { applyFriendState(it.state) }
                startLowTimeWatcher()
                container?.sync?.track("game_start", analyticsGameProps)
                if (isFriendGame) container?.sync?.track("friend_game_started", analyticsGameProps)
                startPrefetch()
            }
            is GameEvent.MovePlayed -> {
                val move = event.move
                clock?.press(move.mover.clockSide)
                if (friend != null) {
                    if (session.moveHistory.size == 2) clock?.start(session.sideToMove.clockSide)
                    if (session.isUserControlled(move.mover)) {
                        scope.launch { friend.send(move.ply, move.uci) }
                    } else {
                        applyFriendState(friend.state)
                    }
                }
                audio?.play(soundEffect(move))
                if (move.isCapture || move.givesCheck) {
                    // UIImpactFeedbackGenerator: heavy for a check, medium for a capture.
                    MainActivity.current?.window?.decorView?.performHapticFeedback(
                        if (move.givesCheck) HapticFeedbackConstants.LONG_PRESS
                        else HapticFeedbackConstants.VIRTUAL_KEY
                    )
                }
                saveInProgressSnapshot()
                if (session.isUserControlled(move.mover)) {
                    firstMoveAbortTask?.cancel()
                }
                // Warm the position we just reached, then hand the engine back to
                // whoever moves next (the opponent gets priority).
                prefetcher?.enqueue(session.currentFEN)
                updatePrefetchTurn()
            }
            is GameEvent.GameEnded -> {
                val result = event.result
                prefetcher?.stop()
                clock?.stop()
                lowTimeTask?.cancel()
                firstMoveAbortTask?.cancel()
                audio?.play(SoundEffect.gameEnd)
                if (isFriendGame) {
                    reportFriendEnd(result)
                    friend?.stop()
                } else {
                    runCatching { container?.inProgress?.clear() }
                }
                if (result != GameResult.Aborted) {
                    applyRating(result)
                    saveFinishedGame(result)
                    val container = container
                    // Auto mode adapts the per-time-control level after each rated
                    // engine game: a completed win/loss streak nudges it ±1.
                    if (ratingChange != null && session.config.opponent is GameConfig.Opponent.Engine &&
                        (Defaults.standard.string("play.levelMode") ?: "auto") == "auto"
                    ) {
                        val cat = session.config.timeControl?.category ?: TimeControl.Category.classical
                        val outcome = normalizedResult(result)
                        val didWin: Boolean? = if (outcome == "win") true else if (outcome == "loss") false else null
                        AdaptiveLevel.recordResult(cat, didWin, container?.ratings?.rating(cat) ?: Glicko2Rating())
                    }
                    val props = analyticsGameProps.toMutableMap()
                    props["result"] = normalizedResult(result)
                    result.terminationString?.let { props["termination"] = it }
                    props["rated"] = if (ratingChange != null) "true" else "false"
                    props["moves"] = session.moveHistory.size.toString()
                    props["duration_s"] = JavaDuration.between(startedAt, Instant.now()).seconds.toString()
                    container?.sync?.track("game_finished", props)
                    if (isFriendGame) container?.sync?.track("friend_game_finished", props)
                    val games = runCatching { container?.games?.recentGames() }.getOrNull()
                    if (games != null) {
                        StreakNotifier.refresh(games)
                        // Chess.com-style: ask for loud notifications at a high-intent
                        // moment — after a win, or by the 3rd game so players who keep
                        // losing are still asked once.
                        if (session.config.opponent is GameConfig.Opponent.Engine &&
                            !App.arguments.contains("--uitest") &&
                            (normalizedResult(result) == "win" || games.size >= 3)
                        ) {
                            scope.launch { NotificationPermission.requestFullIfNeeded() }
                        }
                        if (normalizedResult(result) == "win" && container != null) {
                            ReviewPrompt.maybeRequest(if (isFriendGame) "friend_win" else "win", container)
                        }
                    }
                    if (container != null) ReengagementNotifier.refresh(container)
                    // Decisive games go straight to the analysis review; the
                    // sheet only appears when no auto-analysis is coming.
                    showGameOver = !preparingInsights
                }
            }
        }
    }

    private fun saveInProgressSnapshot() {
        // Friend games live on the server; a local snapshot would resume
        // them as a hot-seat game.
        val container = container ?: return
        if (!session.status.isPlaying || isFriendGame) return
        runCatching { container.inProgress.save(makeInProgressRecord(session, clock)) }
    }

    private fun saveFinishedGame(result: GameResult) {
        val container = container ?: return
        val config = session.config
        val record = GameRecord(
            id = gameID,
            startedAt = startedAt,
            endedAt = Instant.now(),
            opponentType = config.opponentTypeString,
            engineLevel = config.engineLevelValue,
            playerColor = if (config.playerColor == Piece.Color.black) "black" else "white",
            tcInitialSeconds = config.timeControl?.initial?.inWholeSeconds?.toInt(),
            tcIncrementSeconds = config.timeControl?.increment?.inWholeSeconds?.toInt(),
            result = result.resultString,
            termination = result.terminationString,
            pgn = session.pgn,
            finalFEN = session.currentFEN,
            ratingCategory = if (ratingChange != null)
                (config.timeControl?.category ?: TimeControl.Category.classical).rawValue else null,
            ratingBefore = ratingChange?.before?.rating,
            ratingAfter = ratingChange?.after?.rating,
        )
        runCatching { container.games.save(record) }
        savedGameRecord = record
        if (!isTraining) CoachHabit.gameFinished(record, container)
        // History row references the game row — must come after the save.
        if (ratingChange != null) {
            container.ratings.recordHistory(
                gameID,
                config.timeControl?.category ?: TimeControl.Category.classical,
            )
        }
        // Signed-in users sync every finished game, free or premium.
        if (container.sync.isSignedIn) {
            scope.launch { container.sync.pushPending() }
        }
        runPostGameAnalysis(record)
    }

    /**
     * Spins up the background eval prefetcher for a fresh game and warms the
     * starting position. Skipped for training games (never analyzed).
     */
    private fun startPrefetch() {
        if (isTraining) return
        val prefetcher = AnalysisPrefetcher.of() ?: return
        this.prefetcher = prefetcher
        prefetcher.reset()
        prefetcher.enqueue(session.currentFEN)
        updatePrefetchTurn()
    }

    /**
     * Prefetch only while the engine is otherwise idle: run during the user's
     * thinking time, pause the moment it's the engine opponent's turn.
     */
    private fun updatePrefetchTurn() {
        val prefetcher = prefetcher ?: return
        if (session.isUserControlled(session.sideToMove)) {
            prefetcher.resume()
        } else {
            prefetcher.pause()
        }
    }

    /**
     * Runs the on-device engine analysis automatically after every real game,
     * so insights are ready to show without digging for them. Engine analysis
     * is free for everyone; the AI report inside it is the paywall.
     */
    private fun runPostGameAnalysis(record: GameRecord) {
        // Engine analysis is free for everyone; the AI report inside is the
        // paywall. Skip only training games and games too short to analyze.
        if (isTraining || session.moveHistory.size < 4) return
        val big = StockfishNets.big ?: return
        val small = StockfishNets.small ?: return
        preparingInsights = true
        val pgn = record.pgn
        scope.launch {
            try {
                val analyzer = GameAnalyzer(evalFileBig = big, evalFileSmall = small)
                val json = try {
                    AppJson.encodeToString(analyzer.analyze(pgn, 250))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showGameOver = true   // analysis failed: fall back
                    return@launch
                }
                val container = container ?: return@launch
                runCatching { container.games.updateAnalysis(record.id, json) }
                postGameInsights = runCatching { container.games.game(record.id) }.getOrNull()
            } finally {
                preparingInsights = false
            }
        }
    }

    /**
     * Engine games are rated; the level's calibrated estimate stands in
     * as the opponent's rating (lichess model, one period per game).
     */
    private fun applyRating(result: GameResult) {
        // Training games (custom start position) are unrated.
        if (session.config.startFEN != null) return
        val opponent = session.config.opponent as? GameConfig.Opponent.Engine ?: return
        val ratings = container?.ratings ?: return
        val score: Double = when (result) {
            is GameResult.Win -> if (session.isUserControlled(result.color)) 1.0 else 0.0
            is GameResult.Draw -> 0.5
            is GameResult.Aborted -> return
        }
        val category = session.config.timeControl?.category ?: TimeControl.Category.classical
        val (before, after) = ratings.applyGame(
            category,
            StrengthLevel.level(opponent.level).ratingEstimate.toDouble(),
            score,
        )
        ratingChange = GameRatingChange(before, after)
    }

    private fun soundEffect(move: PlayedMove): SoundEffect {
        if (move.givesCheck) return SoundEffect.check
        if (move.isPromotion) return SoundEffect.promote
        if (move.isCastle) return SoundEffect.castle
        if (move.isCapture) return SoundEffect.capture
        return SoundEffect.move
    }

    /** Plays a single warning tick when the user's clock first dips under 10s. */
    private fun startLowTimeWatcher() {
        val clock = clock ?: return
        val userSide = session.config.playerColor.clockSide
        lowTimeTask = scope.launch {
            while (isActive) {
                delay(500.milliseconds)
                if (!session.status.isPlaying) return@launch
                if (clock.remaining(userSide) < 10.seconds && clock.running == userSide) {
                    audio?.play(SoundEffect.lowTime)
                    return@launch
                }
            }
        }
    }

    val title: String
        get() {
            val tc = session.config.timeControl ?: return "Casual"
            return if (isFriendGame) "${tc.label} vs ${friend?.opponentName ?: "Friend"}"
            else "${tc.label} ${tc.category.rawValue.replaceFirstChar { it.uppercase() }}"
        }

    fun playerName(color: Piece.Color): String =
        when (val opponent = session.config.opponent) {
            is GameConfig.Opponent.HumanLocal ->
                if (color == Piece.Color.white) "White" else "Black"
            is GameConfig.Opponent.Engine ->
                if (session.isUserControlled(color)) "You"
                else "Level ${opponent.level} · ${StrengthLevel.level(opponent.level).displayName}"
            is GameConfig.Opponent.Friend ->
                if (session.isUserControlled(color)) "You" else (friend?.opponentName ?: "Friend")
        }

    /** True when this is a retry-training game (custom start vs engine). */
    val isTraining: Boolean
        get() = session.config.startFEN != null && session.config.opponent != GameConfig.Opponent.HumanLocal

    // MARK: - Material

    /** Opponent pieces this side has won so far, biggest first. */
    fun capturedPieces(color: Piece.Color): List<Piece.Kind> {
        val remaining = mutableMapOf<Piece.Kind, Int>()
        for (piece in session.boardPieces) {
            if (piece.color == color.opposite) remaining[piece.kind] = (remaining[piece.kind] ?: 0) + 1
        }
        return initialCounts.entries
            .sortedByDescending { pieceValues[it.key] ?: 0 }
            .flatMap { (kind, initial) -> List(max(0, initial - (remaining[kind] ?: 0))) { kind } }
    }

    /**
     * Point edge for this side from the pieces still on the board
     * (positive = ahead; promotion-safe, unlike counting captures).
     */
    fun materialAdvantage(color: Piece.Color): Int =
        session.boardPieces.fold(0) { total, piece ->
            val value = pieceValues[piece.kind] ?: 0
            total + if (piece.color == color) value else -value
        }

    /** Shared dimensions for game_start / game_finished. */
    private val analyticsGameProps: Map<String, String>
        get() {
            val config = session.config
            return mapOf(
                "source" to startSource,
                "time_class" to (config.timeControl?.category?.rawValue ?: "untimed"),
                "time_control" to (config.timeControl?.label ?: "untimed"),
                "level" to (config.engineLevelValue?.toString() ?: "human"),
                "level_mode" to (Defaults.standard.string("play.levelMode") ?: "auto"),
                "color" to if (config.playerColor == Piece.Color.black) "black" else "white",
                "rated" to if (config.engineLevelValue != null && config.startFEN == null) "true" else "false",
                "opponent" to config.opponentTypeString,
                "training" to if (isTraining) "true" else "false",
            )
        }

    /**
     * "win"/"loss"/"draw" from the player's side for engine games;
     * hot-seat games keep the absolute result.
     */
    private fun normalizedResult(result: GameResult): String {
        val raw = result.resultString
        if (session.config.opponent == GameConfig.Opponent.HumanLocal) return raw
        return when (raw) {
            "whiteWin" -> if (session.config.playerColor == Piece.Color.white) "win" else "loss"
            "blackWin" -> if (session.config.playerColor == Piece.Color.black) "win" else "loss"
            else -> raw
        }
    }

    /**
     * Training verdict from the game result: winning the retry (or holding
     * a draw) counts as applying the lesson.
     */
    val trainingPassed: Boolean?
        get() {
            if (!isTraining) return null
            val result = (session.status as? GameStatus.Finished)?.result ?: return null
            return when (result) {
                is GameResult.Win -> session.isUserControlled(result.color)
                is GameResult.Draw -> true
                is GameResult.Aborted -> null
            }
        }

    /** A human word with the result — praise for wins, a lift for losses. */
    val encouragement: String
        get() {
            val headline = resultHeadline
            val pool: List<String> = if (isFriendGame) {
                if (headline.contains("won"))
                    listOf("Bragging rights are yours. 🏆", "Great game — send a rematch invite!")
                else if (headline.contains("lost"))
                    listOf("Good fight. Review it and get your revenge.", "Close one — run it back!")
                else listOf("Evenly matched — a rematch decides it.")
            } else if (headline.contains("won") || headline.contains("passed")) {
                listOf(
                    "Beautiful chess — you outplayed the AI. 👏",
                    "Clean win. Your training is paying off.",
                    "That's how it's done. Keep the momentum!",
                )
            } else if (headline.contains("lost") || headline.contains("Not this time")) {
                listOf(
                    "Good fight. One lesson from this game and it was worth it.",
                    "Every loss is a lesson — see where it turned.",
                    "Chess AI is relentless — you're getting closer every game.",
                )
            } else {
                listOf(
                    "Holding the AI to a draw is solid chess.",
                    "A hard-earned half point — nice defending.",
                )
            }
            return pool[abs(headline.hashCode() % pool.size)]
        }

    val resultHeadline: String
        get() {
            val result = (session.status as? GameStatus.Finished)?.result ?: return ""
            trainingPassed?.let { passed ->
                return if (passed) "Training passed! 🎉" else "Not this time"
            }
            return when (result) {
                is GameResult.Win -> {
                    if (session.config.opponent == GameConfig.Opponent.HumanLocal) {
                        if (result.color == Piece.Color.white) "White wins" else "Black wins"
                    } else if (session.isUserControlled(result.color)) "You won!" else "You lost"
                }
                is GameResult.Draw -> "Draw"
                is GameResult.Aborted -> "Game aborted"
            }
        }

    val resultDetail: String
        get() {
            val result = (session.status as? GameStatus.Finished)?.result ?: return ""
            return when (result) {
                is GameResult.Win -> when (result.termination) {
                    GameResult.Termination.checkmate -> "by checkmate"
                    GameResult.Termination.resignation -> "by resignation"
                    GameResult.Termination.timeout -> "on time"
                }
                is GameResult.Draw -> when (result.reason) {
                    GameResult.DrawReason.stalemate -> "by stalemate"
                    GameResult.DrawReason.repetition -> "by threefold repetition"
                    GameResult.DrawReason.fiftyMoves -> "by the fifty-move rule"
                    GameResult.DrawReason.insufficientMaterial -> "by insufficient material"
                    GameResult.DrawReason.agreement -> "by agreement"
                }
                is GameResult.Aborted -> ""
            }
        }

    companion object {
        private val pieceValues: Map<Piece.Kind, Int> = mapOf(
            Piece.Kind.pawn to 1, Piece.Kind.knight to 3, Piece.Kind.bishop to 3,
            Piece.Kind.rook to 5, Piece.Kind.queen to 9,
        )
        private val initialCounts: Map<Piece.Kind, Int> = mapOf(
            Piece.Kind.pawn to 8, Piece.Kind.knight to 2, Piece.Kind.bishop to 2,
            Piece.Kind.rook to 2, Piece.Kind.queen to 1,
        )
    }
}

val Piece.Color.clockSide: ClockSide get() = if (this == Piece.Color.white) ClockSide.white else ClockSide.black
