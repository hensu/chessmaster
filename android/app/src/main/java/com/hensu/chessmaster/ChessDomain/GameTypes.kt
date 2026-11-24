// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.time.Duration

sealed class GameResult {
    data class Win(val color: Piece.Color, val termination: Termination) : GameResult()
    data class Draw(val reason: DrawReason) : GameResult()
    data object Aborted : GameResult()

    enum class Termination(val rawValue: String) {
        checkmate("checkmate"), resignation("resignation"), timeout("timeout");
        companion object { fun fromRaw(raw: String): Termination? = entries.firstOrNull { it.rawValue == raw } }
    }

    enum class DrawReason(val rawValue: String) {
        agreement("agreement"), fiftyMoves("fiftyMoves"), insufficientMaterial("insufficientMaterial"),
        repetition("repetition"), stalemate("stalemate");
        companion object { fun fromRaw(raw: String): DrawReason? = entries.firstOrNull { it.rawValue == raw } }
    }

    /** PGN result tag value. */
    val pgnResult: String
        get() = when (this) {
            is Win -> if (color == Piece.Color.white) "1-0" else "0-1"
            is Draw -> "1/2-1/2"
            Aborted -> "*"
        }
}

sealed class GameStatus {
    data object Idle : GameStatus()
    data object Playing : GameStatus()
    data class Finished(val result: GameResult) : GameStatus()

    val isPlaying: Boolean get() = this == Playing
}

/**
 * One move as played, with everything downstream consumers need
 * (move list UI, PGN export, analysis, sync).
 */
data class PlayedMove(
    /** 1-based ply (1 = White's first move). */
    val ply: Int,
    val san: String,
    val uci: String,
    val mover: Piece.Color,
    val isCapture: Boolean,
    val isCastle: Boolean,
    val isPromotion: Boolean,
    val givesCheck: Boolean,
    /** Mover's clock after the move, if the game is timed. */
    var clockRemaining: Duration? = null,
) {
    val id: Int get() = ply

    /** Full-move number this ply belongs to. */
    val moveNumber: Int get() = (ply + 1) / 2
}

/** Events emitted by GameSession, consumed by audio/haptics/persistence. */
sealed class GameEvent {
    data object GameStarted : GameEvent()
    data class MovePlayed(val move: PlayedMove) : GameEvent()
    data class GameEnded(val result: GameResult) : GameEvent()
}

data class GameConfig(
    /** nil = standard starting position; set for retry-training games. */
    var startFEN: String? = null,
    var playerColor: Piece.Color = Piece.Color.white,
    var timeControl: TimeControl? = null,
    var opponent: Opponent = Opponent.HumanLocal,
    /**
     * Probability the engine plays a random legal move instead of its
     * best — strength below the ladder's level-1 floor for players still
     * losing there. 0 = engine plays at its level.
     */
    var engineBlunderProbability: Double = 0.0,
) {
    sealed class Opponent {
        /** Both sides played on this device (M1 hot-seat; also useful for tests). */
        data object HumanLocal : Opponent()
        /** Stockfish at a ladder level (1...10). */
        data class Engine(val level: Int) : Opponent()
        /**
         * A friend on another device (live invite game); moves arrive
         * through the `opponentProvider`.
         */
        data object Friend : Opponent()
    }
}
