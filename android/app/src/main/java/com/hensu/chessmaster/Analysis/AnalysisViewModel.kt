// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class AnalysisViewModel(val game: GameRecord) {
    val replay: GameReplay? = GameReplay.of(game.pgn)

    /** 0 = starting position, N = after ply N. */
    var currentPly by mutableIntStateOf(0)
    var analysis: GameAnalysis? by mutableStateOf(null)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var progress: Double by mutableStateOf(0.0)
        private set

    // Premium coaching state.
    var coachingReport: CoachingReport? by mutableStateOf(null)
        private set
    var coachingLoading by mutableStateOf(false)
        private set
    var coachingError: String? by mutableStateOf(null)
        private set
    var showPaywall by mutableStateOf(false)
    /** Which premium gate raised the paywall, for funnel attribution. */
    var paywallSource by mutableStateOf("analysis")
        private set
    /** Set to launch a retry-training game from a critical position. */
    var trainingConfig: GameConfig? by mutableStateOf(null)
    /**
     * Coach's guidance shown during the replay, and what happened in the
     * original game — for the then-vs-now comparison at the end.
     */
    var trainingAdvice: String? by mutableStateOf(null)
        private set
    var trainingOriginal: String? by mutableStateOf(null)
        private set
    /** Arrows for the retry board: red = played move, green = suggestion. */
    var trainingArrows: List<BoardArrow> by mutableStateOf(emptyList())
        private set

    /** Current step in the guided review, nil when not on a mistake. */
    var mistakeIndex: Int? by mutableStateOf(null)

    private var container: DependencyContainer? by mutableStateOf(null)
    private val scope = MainScope()

    init {
        game.analysisJSON?.let { json ->
            runCatching { AppJson.decodeFromString<GameAnalysis>(json) }.getOrNull()?.let { analysis = it }
        }
        // A previously generated coaching report loads from the local cache
        // — reopening a reviewed game never regenerates.
        game.coachingJSON?.let { json ->
            runCatching { AppJson.decodeFromString<CoachingReport>(json) }.getOrNull()?.let { coachingReport = it }
        }
        currentPly = replay?.plies?.size ?: 0
    }

    fun attach(container: DependencyContainer) {
        this.container = container
        restoreRemoteReportIfNeeded()
    }

    /**
     * Reinstall / other device: the report exists server-side but not in
     * the local cache — restore it silently (read-only; never generates).
     */
    private fun restoreRemoteReportIfNeeded() {
        val container = container ?: return
        if (coachingReport != null || !container.sync.isSignedIn || game.opponentType != "engine") return
        val gameID = game.id
        scope.launch {
            val data = analysisReportJSON(this@AnalysisViewModel.container?.sync?.fetchCoachingReport(gameID))
                ?: return@launch
            val report = runCatching { AppJson.decodeFromString<CoachingReport>(data) }.getOrNull()
                ?: return@launch
            coachingReport = report
            persistReport(report)
        }
    }

    private fun persistReport(report: CoachingReport) {
        val json = runCatching { AppJson.encodeToString(CoachingReport.serializer(), report) }.getOrNull() ?: return
        runCatching { container?.games?.updateCoaching(game.id, json) }
    }

    val isPremium: Boolean get() = container?.entitlements?.isPremium ?: false
    val coachingAvailable: Boolean get() = container?.sync?.isConfigured ?: false

    /**
     * Requests the AI coaching report. Free players get one per week —
     * the server enforces the limit, so everyone may ask.
     */
    fun requestCoaching(automatic: Boolean = false) {
        val container = container ?: return
        // Engine analysis is free; the AI coaching report is a paid feature.
        if (!container.entitlements.isPremium) {
            paywallSource = "coaching"
            showPaywall = true
            return
        }
        if (coachingLoading || coachingReport != null) return
        if (automatic) {
            container.sync.track("auto_coach_started", mapOf("plan" to container.entitlements.plan.rawValue, "trigger" to "habit"))
        } else {
            CoachHabit.coachingRequested()
            container.sync.track("coaching_requested")
        }
        coachingLoading = true
        coachingError = null
        val gameID = game.id
        scope.launch {
            try {
                val sync = this@AnalysisViewModel.container?.sync ?: return@launch
                val report = CoachingGenerator.generate(gameID, sync)
                coachingReport = report
                if (report != null) {
                    persistReport(report)
                } else {
                    coachingError = "The coach couldn't analyze this game."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Premium is confirmed before we get here; any failure is a
                // transient backend/model hiccup and the report is retryable.
                coachingError = "The coach is taking a moment — tap to try again."
            }
            coachingLoading = false
        }
    }

    /**
     * Starts a retry-training game from the position before the given ply
     * (premium). The player replays their own critical moment vs Stockfish.
     */
    fun startTraining(atPly: Int) {
        val ply = atPly
        val container = container ?: return
        if (!container.entitlements.isPremium) {
            paywallSource = "training"
            showPaywall = true
            return
        }
        val replay = replay ?: return
        val fen = replay.fenBefore(ply)

        // Arm the replay with the coach's advice and the original outcome.
        val playedSAN = if (ply - 1 < replay.plies.size) replay.plies[ply - 1].san else ""
        val note = coachNote(ply)
        val bestForAdvice = evalFor(ply)?.bestMoveUCI
        trainingAdvice = if (note != null) {
            "You played $playedSAN. Chess AI suggests ${note.betterMove}: ${note.betterPlan}"
        } else if (bestForAdvice != null) {
            "You played $playedSAN. Chess AI suggested ${bestForAdvice.take(2)}→${bestForAdvice.drop(2).take(2)}."
        } else {
            if (playedSAN.isEmpty()) null else "You played $playedSAN here — find a better move."
        }
        // Board arrows for the retry: red = the move you played,
        // green = Chess AI's suggestion. Shown until the first new move.
        val arrows = mutableListOf<BoardArrow>()
        if (ply - 1 < replay.plies.size) {
            OpponentMove.of(replay.plies[ply - 1].uci)?.let { played ->
                arrows.add(BoardArrow(played.from, played.to, Sys.red))
            }
        }
        evalFor(ply)?.bestMoveUCI?.let { bestUCI ->
            OpponentMove.of(bestUCI)?.let { best ->
                arrows.add(BoardArrow(best.from, best.to, Sys.green))
            }
        }
        trainingArrows = arrows
        val userIsWhite = game.playerColor != "black"
        val userWon = (game.result == "whiteWin" && userIsWhite) ||
            (game.result == "blackWin" && !userIsWhite)
        val outcome = if (game.result == "draw") "drew" else (if (userWon) "won" else "went on to lose")
        var original = "Original game: you played $playedSAN here"
        val drop = dropFor(ply)
        if (drop != null && drop >= 1) {
            original += " (−${drop.roundToInt()}% win chance)"
        }
        original += " and $outcome."
        trainingOriginal = original
        val rating = container.ratings.rating(
            TimeControl.Category.fromRaw(game.ratingCategory ?: "") ?: TimeControl.Category.blitz
        ).rating
        val level = max(1, min(10, (rating / 300).roundToInt()))
        trainingConfig = GameConfig(
            startFEN = fen,
            playerColor = if (game.playerColor == "black") Piece.Color.black else Piece.Color.white,
            timeControl = null,
            opponent = GameConfig.Opponent.Engine(level),
        )
        container.sync.track("training_started", mapOf("from_ply" to ply.toString(), "level" to level.toString()))
    }

    val plyCount: Int get() = replay?.plies?.size ?: 0

    val currentFEN: String
        get() {
            val replay = replay ?: return game.finalFEN
            return if (currentPly == 0) replay.startFEN else replay.plies[currentPly - 1].fenAfter
        }

    /**
     * Stable-identity pieces for the current ply, so stepping animates
     * the moved piece instead of refreshing the whole board. Falls back
     * to FEN-derived pieces when the PGN failed to parse.
     */
    val currentBoardPieces: List<BoardPiece>
        get() = replay?.boardPieces(currentPly) ?: boardPieces(game.finalFEN)

    val lastMove: LastMove?
        get() {
            val replay = replay ?: return null
            if (currentPly <= 0) return null
            val uci = replay.plies[currentPly - 1].uci
            val move = OpponentMove.of(uci) ?: return null
            return LastMove(move.from, move.to)
        }

    /** White's win probability at the current ply (50 when unanalyzed). */
    val evalBarPercent: Double
        get() {
            val analysis = analysis ?: return 50.0
            if (currentPly == 0) return 50.0
            return analysis.plies[min(currentPly, analysis.plies.size) - 1].winPercentWhite
        }

    fun evalFor(ply: Int): PlyEval? {
        val analysis = analysis ?: return null
        if (ply < 1 || ply > analysis.plies.size) return null
        return analysis.plies[ply - 1]
    }

    fun jump(to: Int) {
        currentPly = max(0, min(plyCount, to))
        // Keep the guided review in step with wherever the board actually is.
        mistakeIndex = userMistakes.indexOfFirst { it.ply == currentPly + 1 }.takeIf { it >= 0 }
    }

    /**
     * Positions the board just before a flagged move so the arrows show
     * "you played this (red), better was this (green)".
     */
    fun showMistake(atPly: Int) {
        jump(atPly - 1)
    }

    /** Jumps to the game's worst moment (biggest classification first). */
    fun jumpToWorstMoment() {
        val analysis = analysis ?: return
        val worst = analysis.plies.firstOrNull { it.classification == MoveClassification.blunder }
            ?: analysis.criticalPlies.firstOrNull()
        if (worst != null) showMistake(worst.ply)
    }

    // MARK: - Guided mistake review

    /**
     * The review list: the player's flagged moves PLUS their biggest
     * win-probability losses, so a lost game always has a real review even
     * when it was lost by drift rather than one blunder. Game order.
     */
    val userMistakes: List<PlyEval>
        get() {
            val analysis = analysis ?: return emptyList()
            val userIsWhite = game.playerColor != "black"
            val (dropByPly, reviewableByPly) = reviewSignals()

            val userPlies = analysis.plies.filter { it.moverIsWhite == userIsWhite }
            val chosen = userPlies.filter { it.classification != MoveClassification.good }.map { it.ply }.toMutableSet()
            val target = 5
            if (chosen.size < target) {
                val fallback = userPlies
                    .filter { !chosen.contains(it.ply) }
                    .filter { reviewableByPly[it.ply] == true && (dropByPly[it.ply] ?: 0.0) >= 2 }
                    .sortedByDescending { dropByPly[it.ply] ?: 0.0 }
                for (e in fallback.take(target - chosen.size)) chosen.add(e.ply)
            }
            return userPlies.filter { chosen.contains(it.ply) }
        }

    /** Win% the move at `ply` cost the player ("−5% win chance" captions). */
    fun dropFor(ply: Int): Double? = reviewSignals().first[ply]

    /** Per-user-ply win% drop and whether the position was still contested. */
    private fun reviewSignals(): Pair<Map<Int, Double>, Map<Int, Boolean>> {
        val analysis = analysis ?: return Pair(emptyMap(), emptyMap())
        val userIsWhite = game.playerColor != "black"
        val drops = mutableMapOf<Int, Double>()
        val reviewable = mutableMapOf<Int, Boolean>()
        var prevWhite = 50.0
        for (e in analysis.plies) {
            if (e.moverIsWhite == userIsWhite) {
                val before = if (userIsWhite) prevWhite else 100 - prevWhite
                val after = if (userIsWhite) e.winPercentWhite else 100 - e.winPercentWhite
                drops[e.ply] = max(0.0, before - after)
                reviewable[e.ply] = before >= 10 && before <= 95
            }
            prevWhite = e.winPercentWhite
        }
        return Pair(drops, reviewable)
    }

    /** The coach's note for a specific mistake, once the report exists. */
    fun coachNote(forPly: Int): CoachingReport.KeyMoment? =
        coachingReport?.keyMoments?.firstOrNull { it.ply == forPly }

    /** Starts the walkthrough at the player's first mistake. */
    fun startGuidedReview() {
        container?.sync?.track("mistake_review_started")
        val first = userMistakes.firstOrNull()
        if (first == null) {
            jumpToWorstMoment()
            return
        }
        showMistake(first.ply)
    }

    fun stepMistake(by: Int) {
        val index = mistakeIndex
        if (index == null) {
            startGuidedReview()
            return
        }
        val next = index + by
        val mistakes = userMistakes
        if (next !in mistakes.indices) return
        showMistake(mistakes[next].ply)
    }

    /**
     * Arrows for the current position: when the *next* ply is a flagged
     * move, draw the played move in red and the engine's move in green.
     */
    val boardArrows: List<BoardArrow>
        get() {
            val replay = replay ?: return emptyList()
            val eval = evalFor(currentPly + 1) ?: return emptyList()
            if (userMistakes.none { it.ply == eval.ply }) return emptyList()
            val arrows = mutableListOf<BoardArrow>()
            OpponentMove.of(replay.plies[currentPly].uci)?.let { played ->
                arrows.add(BoardArrow(played.from, played.to, Sys.red))
            }
            eval.bestMoveUCI?.let { bestUCI ->
                OpponentMove.of(bestUCI)?.let { best ->
                    arrows.add(BoardArrow(best.from, best.to, Sys.green))
                }
            }
            return arrows
        }

    fun runAnalysis(movetimeMs: Int = 120) {
        if (analyzing || analysis != null) return
        analyzing = true
        progress = 0.0
        val pgn = game.pgn
        val gameID = game.id
        // The analyzer may report from its own thread; the state changes on the main one.
        val progressHandler: (Int, Int) -> Unit = { done, total ->
            scope.launch { progress = done.toDouble() / total.toDouble() }
        }
        scope.launch {
            // The nets are copied out of the APK on first use, hence the suspend accessors.
            val big = StockfishNets.big()
            val small = StockfishNets.small()
            if (big == null || small == null) {
                analyzing = false
                return@launch
            }
            val analyzer = GameAnalyzer(evalFileBig = big, evalFileSmall = small)
            try {
                val result = analyzer.analyze(pgn, movetimeMs, progressHandler)
                analysis = result
                analyzing = false
                runCatching { AppJson.encodeToString(GameAnalysis.serializer(), result) }.getOrNull()?.let { json ->
                    runCatching { container?.games?.updateAnalysis(gameID, json) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                analyzing = false
            }
        }
    }

    /** Static members added by extensions live here (`playedLikeRating` in ShareCard.kt). */
    companion object
}

/**
 * `SyncService.fetchCoachingReport` hands back the report JSON (`Data?` in Swift);
 * this reads it whichever JSON carrier the Kotlin port chose.
 */
private fun analysisReportJSON(data: Any?): String? = when (data) {
    null -> null
    is String -> data
    is ByteArray -> data.decodeToString()
    is JsonElement -> data.toString()
    else -> null
}
