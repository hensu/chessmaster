// PersistenceKit — Chessmaster (Android)
// GPL-3.0-or-later
//
// Records are deliberately primitive (strings/ints) so this package stays
// independent of the chess domain; the app maps to domain types.

package com.hensu.chessmaster

import android.content.ContentValues
import android.database.Cursor
import java.time.Instant
import java.util.UUID

data class GameRecord(
    // Uppercase like Swift's UUID().uuidString, so ids look the same on both platforms.
    var id: String = UUID.randomUUID().toString().uppercase(),
    var startedAt: Instant,
    var endedAt: Instant,
    var opponentType: String,
    var engineLevel: Int? = null,
    var playerColor: String,
    var tcInitialSeconds: Int? = null,
    var tcIncrementSeconds: Int? = null,
    var result: String,
    var termination: String? = null,
    var pgn: String,
    var finalFEN: String,
    var ratingCategory: String? = null,
    var ratingBefore: Double? = null,
    var ratingAfter: Double? = null,
    var syncState: String = "local",
    var analysisJSON: String? = null,
    /** Cached AI coaching report (JSON), so reviews never regenerate. */
    var coachingJSON: String? = null,
) {
    internal fun values(): ContentValues = ContentValues().apply {
        put("id", id)
        put("startedAt", AppDatabase.dateText(startedAt))
        put("endedAt", AppDatabase.dateText(endedAt))
        put("opponentType", opponentType)
        put("engineLevel", engineLevel)
        put("playerColor", playerColor)
        put("tcInitialSeconds", tcInitialSeconds)
        put("tcIncrementSeconds", tcIncrementSeconds)
        put("result", result)
        put("termination", termination)
        put("pgn", pgn)
        put("finalFEN", finalFEN)
        put("ratingCategory", ratingCategory)
        put("ratingBefore", ratingBefore)
        put("ratingAfter", ratingAfter)
        put("syncState", syncState)
        put("analysisJSON", analysisJSON)
        put("coachingJSON", coachingJSON)
    }

    companion object {
        const val databaseTableName = "game"

        internal fun from(row: Cursor): GameRecord = GameRecord(
            id = row.dbString("id"),
            startedAt = row.dbDate("startedAt"),
            endedAt = row.dbDate("endedAt"),
            opponentType = row.dbString("opponentType"),
            engineLevel = row.dbIntOrNull("engineLevel"),
            playerColor = row.dbString("playerColor"),
            tcInitialSeconds = row.dbIntOrNull("tcInitialSeconds"),
            tcIncrementSeconds = row.dbIntOrNull("tcIncrementSeconds"),
            result = row.dbString("result"),
            termination = row.dbStringOrNull("termination"),
            pgn = row.dbString("pgn"),
            finalFEN = row.dbString("finalFEN"),
            ratingCategory = row.dbStringOrNull("ratingCategory"),
            ratingBefore = row.dbDoubleOrNull("ratingBefore"),
            ratingAfter = row.dbDoubleOrNull("ratingAfter"),
            syncState = row.dbString("syncState"),
            analysisJSON = row.dbStringOrNull("analysisJSON"),
            coachingJSON = row.dbStringOrNull("coachingJSON"),
        )
    }
}

data class RatingHistoryRecord(
    var id: Long? = null,
    var gameID: String?,
    var at: Instant,
    var category: String,
    var rating: Double,
    var deviation: Double,
    var volatility: Double,
    var syncState: String = "local",
) {
    internal fun values(): ContentValues = ContentValues().apply {
        put("id", id)
        put("gameID", gameID)
        put("at", AppDatabase.dateText(at))
        put("category", category)
        put("rating", rating)
        put("deviation", deviation)
        put("volatility", volatility)
        put("syncState", syncState)
    }

    fun didInsert(rowID: Long) {
        id = rowID
    }

    companion object {
        const val databaseTableName = "ratingHistory"

        internal fun from(row: Cursor): RatingHistoryRecord = RatingHistoryRecord(
            id = row.dbLong("id"),
            gameID = row.dbStringOrNull("gameID"),
            at = row.dbDate("at"),
            category = row.dbString("category"),
            rating = row.dbDouble("rating"),
            deviation = row.dbDouble("deviation"),
            volatility = row.dbDouble("volatility"),
            syncState = row.dbString("syncState"),
        )
    }
}

data class InProgressGameRecord(
    var startFEN: String?,
    var opponentType: String,
    var engineLevel: Int?,
    var playerColor: String,
    var tcInitialSeconds: Int?,
    var tcIncrementSeconds: Int?,
    var movesUCI: String,
    var whiteRemainingMs: Int?,
    var blackRemainingMs: Int?,
    var updatedAt: Instant,
) {
    var id: Long = 1

    internal fun values(): ContentValues = ContentValues().apply {
        put("id", id)
        put("startFEN", startFEN)
        put("opponentType", opponentType)
        put("engineLevel", engineLevel)
        put("playerColor", playerColor)
        put("tcInitialSeconds", tcInitialSeconds)
        put("tcIncrementSeconds", tcIncrementSeconds)
        put("movesUCI", movesUCI)
        put("whiteRemainingMs", whiteRemainingMs)
        put("blackRemainingMs", blackRemainingMs)
        put("updatedAt", AppDatabase.dateText(updatedAt))
    }

    companion object {
        const val databaseTableName = "inProgressGame"

        internal fun from(row: Cursor): InProgressGameRecord = InProgressGameRecord(
            startFEN = row.dbStringOrNull("startFEN"),
            opponentType = row.dbString("opponentType"),
            engineLevel = row.dbIntOrNull("engineLevel"),
            playerColor = row.dbString("playerColor"),
            tcInitialSeconds = row.dbIntOrNull("tcInitialSeconds"),
            tcIncrementSeconds = row.dbIntOrNull("tcIncrementSeconds"),
            movesUCI = row.dbString("movesUCI"),
            whiteRemainingMs = row.dbIntOrNull("whiteRemainingMs"),
            blackRemainingMs = row.dbIntOrNull("blackRemainingMs"),
            updatedAt = row.dbDate("updatedAt"),
        ).also { it.id = row.dbLong("id") }
    }
}
