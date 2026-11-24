// Chessmaster — GPL-3.0-or-later
//
// Mapping between domain types and PersistenceKit's primitive records.

package com.hensu.chessmaster

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Swift's failable `GameConfig.init?(record:)`. `GameConfig` has no companion object to hang
 * `of(record)` on, so this is a factory function with the type's name: `GameConfig(record)`.
 */
fun GameConfig(record: InProgressGameRecord): GameConfig? {
    val opponent: GameConfig.Opponent = when (record.opponentType) {
        "engine" -> GameConfig.Opponent.Engine(record.engineLevel ?: 1)
        "humanLocal" -> GameConfig.Opponent.HumanLocal
        else -> return null
    }
    var timeControl: TimeControl? = null
    val initial = record.tcInitialSeconds
    val increment = record.tcIncrementSeconds
    if (initial != null && increment != null) {
        timeControl = TimeControl(initial.seconds, increment.seconds)
    }
    return GameConfig(
        startFEN = record.startFEN,
        playerColor = if (record.playerColor == "black") Piece.Color.black else Piece.Color.white,
        timeControl = timeControl,
        opponent = opponent,
    )
}

val GameConfig.opponentTypeString: String
    get() = when (opponent) {
        is GameConfig.Opponent.Engine -> "engine"
        GameConfig.Opponent.HumanLocal -> "humanLocal"
        GameConfig.Opponent.Friend -> "friend"
    }

val GameConfig.engineLevelValue: Int?
    get() = (opponent as? GameConfig.Opponent.Engine)?.level

val GameResult.resultString: String
    get() = when (this) {
        is GameResult.Win -> if (color == Piece.Color.white) "whiteWin" else "blackWin"
        is GameResult.Draw -> "draw"
        GameResult.Aborted -> "aborted"
    }

val GameResult.terminationString: String?
    get() = when (this) {
        is GameResult.Win -> termination.rawValue
        is GameResult.Draw -> reason.rawValue
        GameResult.Aborted -> null
    }

fun makeInProgressRecord(session: GameSession, clock: ChessClock?): InProgressGameRecord {
    val config = session.config
    return InProgressGameRecord(
        startFEN = config.startFEN,
        opponentType = config.opponentTypeString,
        engineLevel = config.engineLevelValue,
        playerColor = if (config.playerColor == Piece.Color.black) "black" else "white",
        tcInitialSeconds = config.timeControl?.initial?.inWholeSeconds?.toInt(),
        tcIncrementSeconds = config.timeControl?.increment?.inWholeSeconds?.toInt(),
        movesUCI = session.moveHistory.joinToString(" ") { it.uci },
        whiteRemainingMs = clock?.remaining(ClockSide.white)?.milliseconds?.toInt(),
        blackRemainingMs = clock?.remaining(ClockSide.black)?.milliseconds?.toInt(),
        updatedAt = Instant.now(),
    )
}

val Duration.milliseconds: Long
    get() = inWholeMilliseconds

/** Pieces for rendering a static FEN (history detail, analysis). */
fun boardPieces(fromFEN: String): List<BoardPiece> {
    val position = Position.of(fromFEN) ?: return emptyList()
    return position.pieces.mapIndexed { index, piece ->
        BoardPiece(id = index, kind = piece.kind, color = piece.color, square = piece.square)
    }
}
