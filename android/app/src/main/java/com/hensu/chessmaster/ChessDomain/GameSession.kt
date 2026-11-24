// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import com.github.bhlangonijr.chesslib.Board as LibBoard

/** The outcome of attempting a move on the board. */
enum class MoveOutcome {
    played,
    /** Pawn reached the last rank; call `completePromotion(kind)`. */
    needsPromotion,
    illegal,
}

/**
 * A piece with an identity that is stable across moves, so the board UI
 * can animate a piece sliding rather than tearing down and recreating views.
 */
data class BoardPiece(val id: Int, var kind: Piece.Kind, val color: Piece.Color, var square: Square)

/** `lastMove: (from: Square, to: Square)` */
data class LastMove(val from: Square, val to: Square)

/** `pendingPromotion: (square: Square, color: Piece.Color)` */
data class PendingPromotion(val square: Square, val color: Piece.Color)

/**
 * Central game state machine. Owns the live board (rules), the game record
 * (PGN), the move history, and drives the opponent. UI observes it; audio
 * and persistence subscribe to `events`.
 */
class GameSession(val config: GameConfig) {
    var status: GameStatus by mutableStateOf(GameStatus.Idle)
        private set
    var boardPieces: List<BoardPiece> by mutableStateOf(emptyList())
        private set
    var moveHistory: List<PlayedMove> by mutableStateOf(emptyList())
        private set
    var lastMove: LastMove? by mutableStateOf(null)
        private set
    /** Square of a king currently in check, for the red highlight. */
    var checkedKingSquare: Square? by mutableStateOf(null)
        private set
    /** Set while a promotion picker must be shown. */
    var pendingPromotion: PendingPromotion? by mutableStateOf(null)
        private set
    /** True while the opponent is choosing a move. */
    var opponentThinking: Boolean by mutableStateOf(false)
        private set

    private val eventChannel = Channel<GameEvent>(Channel.UNLIMITED)
    /** One consumer (the view model), like the Swift AsyncStream. Completes when the game ends. */
    val events: Flow<GameEvent> = eventChannel.receiveAsFlow()

    /** Set by the app when the opponent is an engine or a friend. */
    var opponentProvider: OpponentProvider? = null

    /** Injected by the clock layer: returns the mover's remaining time. */
    var clockSnapshot: ((Piece.Color) -> Duration?)? = null

    private val startFEN: String
    private val board: LibBoard
    private var pendingPromotionMove: LastMove? = null
    private var opponentTask: Job? = null
    private val scope = MainScope()
    private var nextPieceID = 0
    private var suppressEvents = false

    init {
        val position = config.startFEN?.let { Position.of(it) } ?: Position.standard
        board = ChessRules.copy(position.lib)
        startFEN = position.fen
        rebuildBoardPieces()
    }

    private fun rebuildBoardPieces() {
        val pieces = Position(board).pieces
        boardPieces = pieces.mapIndexed { index, piece ->
            BoardPiece(nextPieceID + index, piece.kind, piece.color, piece.square)
        }
        nextPieceID += pieces.size
    }

    // MARK: - Reading state

    val pieces: List<Piece> get() = Position(board).pieces
    val sideToMove: Piece.Color get() = Piece.Color.from(board.sideToMove)
    val currentFEN: String get() = board.fen

    /** Whether the given color is controlled by the local user. */
    fun isUserControlled(color: Piece.Color): Boolean = when (config.opponent) {
        is GameConfig.Opponent.HumanLocal -> true
        else -> color == config.playerColor
    }

    fun legalTargets(square: Square): List<Square> {
        if (!status.isPlaying || pendingPromotion != null) return emptyList()
        return ChessRules.legalTargets(board, square)
    }

    fun piece(square: Square): Piece? = Position(board).piece(square)

    // MARK: - Lifecycle

    /**
     * Replays previously played moves (UCI) into an idle session — used to
     * resume a saved game. Returns false if any move fails to apply.
     */
    fun replay(movesUCI: List<String>): Boolean {
        if (status != GameStatus.Idle) return false
        suppressEvents = true
        try {
            for (uci in movesUCI) {
                val move = OpponentMove.of(uci) ?: return false
                if (performMove(move.from, move.to, move.promotion ?: Piece.Kind.queen) != MoveOutcome.played) return false
            }
            return true
        } finally {
            suppressEvents = false
        }
    }

    fun start() {
        if (status != GameStatus.Idle) return
        status = GameStatus.Playing
        eventChannel.trySend(GameEvent.GameStarted)
        driveOpponentIfNeeded()
    }

    fun resign(color: Piece.Color? = null) {
        if (!status.isPlaying) return
        val resigning = color ?: config.playerColor
        finish(GameResult.Win(resigning.opposite, GameResult.Termination.resignation))
    }

    /** Called by the clock when a side runs out of time. */
    fun flag(color: Piece.Color) {
        if (!status.isPlaying) return
        finish(GameResult.Win(color.opposite, GameResult.Termination.timeout))
    }

    fun abort() {
        if (!status.isPlaying || moveHistory.size >= 2) return
        finish(GameResult.Aborted)
    }

    // MARK: - Moves

    /** Attempt a move by the local user (board input). */
    fun attemptUserMove(from: Square, to: Square): MoveOutcome {
        if (!status.isPlaying || pendingPromotion != null || !isUserControlled(sideToMove) || opponentThinking) {
            return MoveOutcome.illegal
        }
        return performMove(from, to, null)
    }

    fun completePromotion(kind: Piece.Kind) {
        val pending = pendingPromotionMove ?: return
        pendingPromotionMove = null
        pendingPromotion = null
        val move = ChessRules.find(board, pending.from, pending.to, kind) ?: return
        commit(move)
    }

    fun cancelPromotion() {
        // The pawn has not moved yet on this board (the move is only made once the piece is
        // picked), so cancelling just drops the pending choice.
        if (pendingPromotionMove == null) return
        pendingPromotionMove = null
        pendingPromotion = null
    }

    private fun performMove(from: Square, to: Square, promotion: Piece.Kind?): MoveOutcome {
        if (ChessRules.isPromotion(board, from, to)) {
            if (promotion != null) {
                val move = ChessRules.find(board, from, to, promotion) ?: return MoveOutcome.illegal
                commit(move)
                return MoveOutcome.played
            }
            pendingPromotionMove = LastMove(from, to)
            pendingPromotion = PendingPromotion(to, sideToMove)
            return MoveOutcome.needsPromotion
        }
        val move = ChessRules.find(board, from, to, null) ?: return MoveOutcome.illegal
        commit(move)
        return MoveOutcome.played
    }

    private fun commit(move: Move) {
        val mover = sideToMove
        val isCastle = ChessRules.isCastle(board, move)
        val isCapture = ChessRules.isCapture(board, move)
        val san = ChessRules.san(board, move)
        val uci = ChessRules.uci(move)
        val isPromotion = uci.length == 5

        val pieces = boardPieces.toMutableList()
        ChessRules.apply(board, move, pieces)
        board.doMove(move)
        boardPieces = pieces

        val played = PlayedMove(
            ply = moveHistory.size + 1,
            san = san,
            uci = uci,
            mover = mover,
            isCapture = isCapture,
            isCastle = isCastle,
            isPromotion = isPromotion,
            givesCheck = board.isKingAttacked,
            clockRemaining = clockSnapshot?.invoke(mover),
        )
        moveHistory = moveHistory + played
        lastMove = LastMove(Square.from(move.from), Square.from(move.to))
        updateCheckHighlight()
        if (!suppressEvents) eventChannel.trySend(GameEvent.MovePlayed(played))

        val result = terminalResult()
        if (result != null) finish(result) else driveOpponentIfNeeded()
    }

    private fun terminalResult(): GameResult? = when (val state = ChessRules.state(board)) {
        is Board.State.Checkmate -> GameResult.Win(state.color.opposite, GameResult.Termination.checkmate)
        is Board.State.Draw -> GameResult.Draw(state.reason)
        else -> null
    }

    private fun updateCheckHighlight() {
        checkedKingSquare = if (board.isKingAttacked) {
            Square.from(board.getKingSquare(board.sideToMove))
        } else null
    }

    private fun finish(result: GameResult) {
        opponentTask?.cancel()
        opponentThinking = false
        status = GameStatus.Finished(result)
        eventChannel.trySend(GameEvent.GameEnded(result))
        eventChannel.close()
    }

    // MARK: - Opponent

    private fun driveOpponentIfNeeded() {
        val provider = opponentProvider
        if (!status.isPlaying || isUserControlled(sideToMove) || provider == null) return

        opponentThinking = true
        val fen = currentFEN
        opponentTask = scope.launch {
            try {
                val move = provider.nextMove(fen, null)
                if (!isActive) return@launch
                opponentThinking = false
                performMove(move.from, move.to, move.promotion ?: Piece.Kind.queen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                opponentThinking = false
            }
        }
    }

    // MARK: - Export

    /** PGN of the game so far, with headers and [%clk] comments when timed. */
    val pgn: String
        get() {
            val result = (status as? GameStatus.Finished)?.result?.pgnResult ?: "*"
            val tags = mutableListOf("Event" to "Chess AI game", "Site" to "Chess AI Android", "Result" to result)
            config.timeControl?.let { tags.add("TimeControl" to it.pgnTag) }
            if (config.startFEN != null) {
                tags.add("SetUp" to "1")
                tags.add("FEN" to startFEN)
            }
            val headers = tags.joinToString("\n") { "[${it.first} \"${it.second}\"]" }

            val whiteStarts = startFEN.split(" ").getOrNull(1) != "b"
            val firstNumber = startFEN.split(" ").getOrNull(5)?.toIntOrNull() ?: 1
            val tokens = mutableListOf<String>()
            moveHistory.forEachIndexed { index, move ->
                val whiteMove = move.mover == Piece.Color.white
                val number = firstNumber + (index + if (whiteStarts) 0 else 1) / 2
                if (whiteMove) tokens.add("$number.") else if (index == 0) tokens.add("$number...")
                tokens.add(move.san)
            }
            tokens.add(result)
            return PGNClockAnnotator.annotate(headers + "\n\n" + tokens.joinToString(" "), moveHistory)
        }
}
