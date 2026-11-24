// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Solve puzzles in a category: the opponent's setup move plays itself,
 * then the player must find the solution line. Wrong tries reset the
 * position; alternative checkmates count (lichess convention).
 */
@Composable
fun PuzzleScreen(category: PuzzleCategory) {
    val model = remember { PuzzleViewModel(category) }
    PuzzleScreenBody(category, model)
}

/** Today's daily puzzle: one position, a streak, a shareable result. */
@Composable
fun PuzzleScreen(daily: Puzzle) {
    val model = remember { PuzzleViewModel(daily) }
    PuzzleScreenBody(PuzzleCategory.fromRaw(daily.category) ?: PuzzleCategory.fork, model)
}

@Composable
private fun PuzzleScreenBody(category: PuzzleCategory, model: PuzzleViewModel) {
    val container = DependencyContainer.shared
    val progress = container.learnProgress

    DisposableEffect(Unit) {
        model.attach(container, progress)
        if (model.daily == null || model.puzzle == null) {
            model.startNextPuzzle()
        }
        onDispose { model.stop() }
    }

    ScreenScaffold(
        if (model.daily != null) "Daily #${DailyPuzzle.number()}" else category.title,
        modifier = Modifier.trackScreen("Puzzle"),
        background = Sys.background,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val puzzle = model.puzzle
            if (puzzle != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LearnLabel(category.title, category.icon, Sys.subheadline.weight(FontWeight.SemiBold), Sys.primary)
                    Spacer(Modifier.weight(1f))
                    Text("Puzzle rating ${puzzle.rating}", style = Sys.caption, color = Sys.secondary)
                }

                BoardView(
                    pieces = model.session.boardPieces,
                    orientation = model.playerColor,
                    lastMove = model.session.lastMove,
                    checkSquare = model.session.checkedKingSquare,
                    arrows = model.hintArrows,
                    canSelect = { square ->
                        val piece = if (model.canPlay) model.session.piece(square) else null
                        piece != null && piece.color == model.playerColor
                    },
                    legalTargets = { model.session.legalTargets(it) },
                    onMove = { from, to ->
                        model.session.attemptUserMove(from, to)
                    },
                    pendingPromotion = model.session.pendingPromotion,
                    onPromote = { model.session.completePromotion(it) },
                    onCancelPromotion = { model.session.cancelPromotion() },
                )

                PuzzleStatusBanner(model)

                Spacer(Modifier.weight(1f))

                PuzzleBottomControls(model, container)
            } else {
                // ContentUnavailableView
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, tint = Sys.secondary, modifier = Modifier.size(52.dp))
                    Text("No puzzles here yet", style = Sys.title2.bold(), color = Sys.primary, textAlign = TextAlign.Center)
                    Text("Check back after the next update.", style = Sys.subheadline, color = Sys.secondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun PuzzleStatusBanner(model: PuzzleViewModel) {
    when (model.phase) {
        PuzzleViewModel.Phase.yourTurn ->
            LearnLabel("${if (model.playerColor == Piece.Color.white) "White" else "Black"} to move — find the best move",
                       Icons.Rounded.TrackChanges, Sys.subheadline, Sys.secondary)
        PuzzleViewModel.Phase.wrong ->
            LearnLabel("Not quite — the position is reset, try again", Icons.AutoMirrored.Rounded.Undo,
                       Sys.subheadline.weight(FontWeight.Medium), Sys.orange)
        PuzzleViewModel.Phase.opponentThinking ->
            LearnLabel("Good! Keep going…", Icons.Rounded.Check, Sys.subheadline.weight(FontWeight.Medium), Sys.green)
        PuzzleViewModel.Phase.solved ->
            if (model.daily != null && DailyPuzzle.streak() > 0) {
                LearnLabel("Solved! 🔥 ${DailyPuzzle.streak()}-day streak", Icons.Rounded.Verified, Sys.headline, Sys.green,
                           iconSize = 22.dp)
            } else {
                LearnLabel("Solved! 🎉", Icons.Rounded.Verified, Sys.headline, Sys.green, iconSize = 22.dp)
            }
        PuzzleViewModel.Phase.loading ->
            CircularProgressIndicator(Modifier.size(16.dp), color = Sys.secondary, strokeWidth = 2.dp)
    }
}

@Composable
private fun PuzzleBottomControls(model: PuzzleViewModel, container: DependencyContainer) {
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val solved = model.phase == PuzzleViewModel.Phase.solved
        val text = if (solved && model.daily != null) DailyPuzzle.shareText() else null
        if (text != null) {
            LearnProminentButton("Share result", icon = Icons.Rounded.Share) {
                container.sync.track("daily_puzzle_shared", mapOf("streak" to DailyPuzzle.streak().toString()))
                Platform.shareText(text)
            }
        } else if (solved) {
            LearnProminentButton("Next Puzzle") { model.startNextPuzzle() }
        } else {
            LearnBorderedButton("Hint", Icons.Outlined.Lightbulb, enabled = model.phase == PuzzleViewModel.Phase.yourTurn) {
                model.showHint()
            }
        }
    }
}

class PuzzleViewModel private constructor(
    val category: PuzzleCategory,
    /** Set when this is the daily puzzle (single position, streak, share). */
    val daily: Puzzle?,
) {
    enum class Phase { loading, yourTurn, wrong, opponentThinking, solved }

    var puzzle: Puzzle? by mutableStateOf(null)
        private set
    var session: GameSession by mutableStateOf(GameSession(GameConfig()))
        private set
    var playerColor: Piece.Color by mutableStateOf(Piece.Color.white)
        private set
    var phase: Phase by mutableStateOf(Phase.loading)
        private set
    var hintArrows: List<BoardArrow> by mutableStateOf(emptyList())
        private set

    private var container: DependencyContainer? = null
    private var progress: LearnProgress? = null
    private var queue: MutableList<Puzzle> = mutableListOf()
    private var solutionIndex = 0
    private var expectingAutoMove = false
    private var attempts = 0
    private var usedHint = false
    private var eventTask: Job? = null
    private val scope = MainScope()

    val canPlay: Boolean
        get() = (phase == Phase.yourTurn || phase == Phase.wrong) && session.sideToMove == playerColor

    constructor(category: PuzzleCategory) : this(category, null)

    constructor(daily: Puzzle) : this(PuzzleCategory.fromRaw(daily.category) ?: PuzzleCategory.fork, daily)

    fun attach(container: DependencyContainer, progress: LearnProgress) {
        this.container = container
        this.progress = progress
        if (daily != null) {
            if (puzzle == null) queue = mutableListOf(daily)
        } else if (queue.isEmpty()) {
            val all = LearnContent.puzzles(category)
            val unsolved = all.filter { !progress.solvedPuzzles.contains(it.id) }
            queue = (if (unsolved.isEmpty()) all else unsolved).toMutableList()
        }
    }

    fun stop() { eventTask?.cancel() }

    fun startNextPuzzle() {
        if (queue.isEmpty()) { puzzle = null; return }
        val puzzle = queue.removeAt(0)
        this.puzzle = puzzle
        solutionIndex = 0
        attempts = 0
        usedHint = false
        // The FEN's side to move plays the setup move; the player is the
        // other side.
        val setupMover = if (puzzle.fen.split(" ").getOrNull(1) == "w") Piece.Color.white else Piece.Color.black
        playerColor = setupMover.opposite
        container?.sync?.track("puzzle_started", mapOf("category" to category.rawValue, "id" to puzzle.id))
        loadPosition(replaying = emptyList())
        phase = Phase.loading
        scope.launch {
            delay(600)
            playAutoMove()
        }
    }

    fun showHint() {
        val puzzle = puzzle ?: return
        if (solutionIndex >= puzzle.moves.size) return
        val move = OpponentMove.of(puzzle.moves[solutionIndex]) ?: return
        usedHint = true
        hintArrows = listOf(BoardArrow(move.from, move.to, Sys.green))
        container?.sync?.track("puzzle_hint_used", mapOf("category" to category.rawValue))
    }

    /** (Re)builds the session at fen + the solved prefix and re-subscribes. */
    private fun loadPosition(replaying: List<String>) {
        val puzzle = puzzle ?: return
        eventTask?.cancel()
        hintArrows = emptyList()
        val newSession = GameSession(GameConfig(startFEN = puzzle.fen, opponent = GameConfig.Opponent.HumanLocal))
        if (replaying.isNotEmpty()) newSession.replay(replaying)
        session = newSession
        newSession.start()
        eventTask = scope.launch {
            newSession.events.collect { event ->
                if (session !== newSession) return@collect
                if (event is GameEvent.MovePlayed) handleMovePlayed()
            }
        }
    }

    private fun playAutoMove() {
        val puzzle = puzzle ?: return
        if (solutionIndex >= puzzle.moves.size) return
        val move = OpponentMove.of(puzzle.moves[solutionIndex]) ?: return
        expectingAutoMove = true
        val outcome = session.attemptUserMove(move.from, move.to)
        if (outcome == MoveOutcome.needsPromotion) {
            session.completePromotion(move.promotion ?: Piece.Kind.queen)
        } else if (outcome == MoveOutcome.illegal) {
            // Malformed puzzle (shouldn't happen with the curated set):
            // skip it rather than trap the player.
            expectingAutoMove = false
            startNextPuzzle()
        }
    }

    private fun handleMovePlayed() {
        val puzzle = puzzle ?: return
        if (expectingAutoMove) {
            expectingAutoMove = false
            solutionIndex += 1
            phase = Phase.yourTurn
            return
        }
        val played = session.moveHistory.lastOrNull()?.uci ?: return
        val expected = if (solutionIndex < puzzle.moves.size) puzzle.moves[solutionIndex] else ""
        var playerWon = false
        val result = (session.status as? GameStatus.Finished)?.result
        if (result is GameResult.Win && result.color == playerColor) {
            playerWon = true   // alternative mates count, lichess-style
        }
        if (played == expected || playerWon) {
            solutionIndex += 1
            hintArrows = emptyList()
            if (solutionIndex >= puzzle.moves.size || playerWon) {
                phase = Phase.solved
                progress?.markSolved(puzzle.id)
                container?.sync?.track("puzzle_solved", mapOf(
                    "category" to category.rawValue, "id" to puzzle.id,
                    "attempts" to (attempts + 1).toString(),
                    "hint" to if (usedHint) "true" else "false",
                ))
                if (daily != null) {
                    val streak = DailyPuzzle.recordSolve(attempts = attempts + 1, usedHint = usedHint)
                    container?.sync?.track("daily_puzzle_solved", mapOf(
                        "number" to DailyPuzzle.number().toString(), "attempts" to (attempts + 1).toString(),
                        "hint" to (if (usedHint) "true" else "false"), "streak" to streak.toString(),
                    ))
                }
                container?.let { container ->
                    if (daily != null) {
                        ReviewPrompt.maybeRequest("daily_puzzle", container)
                    } else {
                        ReviewPrompt.puzzleSolved(attempts == 0 && !usedHint, container)
                    }
                }
            } else {
                phase = Phase.opponentThinking
                scope.launch {
                    delay(450)
                    playAutoMove()
                }
            }
        } else {
            attempts += 1
            phase = Phase.wrong
            val prefix = puzzle.moves.take(solutionIndex)
            scope.launch {
                delay(700)
                loadPosition(replaying = prefix)
            }
        }
    }
}
