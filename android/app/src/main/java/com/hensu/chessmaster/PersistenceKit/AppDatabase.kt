// PersistenceKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The app database (GRDB's `DatabaseQueue` on iOS): a plain SQLiteOpenHelper with the same
 * tables, columns and migrations. Schema version 1 = iOS migration "v1", 2 = "v2-coachingJSON".
 */
class AppDatabase private constructor(context: Context, name: String?) :
    SQLiteOpenHelper(context, name, null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        // GRDB turns foreign keys on by default (ratingHistory.gameID → game, ON DELETE SET NULL).
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        migrateV1(db)
        migrateV2CoachingJSON(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateV2CoachingJSON(db)
    }

    private fun migrateV1(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE "game" (
                "id" TEXT PRIMARY KEY NOT NULL,
                "startedAt" DATETIME NOT NULL,
                "endedAt" DATETIME NOT NULL,
                "opponentType" TEXT NOT NULL,
                "engineLevel" INTEGER,
                "playerColor" TEXT NOT NULL,
                "tcInitialSeconds" INTEGER,
                "tcIncrementSeconds" INTEGER,
                "result" TEXT NOT NULL,
                "termination" TEXT,
                "pgn" TEXT NOT NULL,
                "finalFEN" TEXT NOT NULL,
                "ratingCategory" TEXT,
                "ratingBefore" DOUBLE,
                "ratingAfter" DOUBLE,
                "syncState" TEXT NOT NULL DEFAULT 'local',
                "analysisJSON" TEXT
            )
            """.trimIndent()
        )
        // id: UUID · opponentType: engine | humanLocal · playerColor: white | black
        // result: whiteWin | blackWin | draw | aborted
        // termination: checkmate | resignation | timeout | draw reason
        db.execSQL("""CREATE INDEX "index_game_on_endedAt" ON "game"("endedAt")""")

        db.execSQL(
            """
            CREATE TABLE "ratingHistory" (
                "id" INTEGER PRIMARY KEY AUTOINCREMENT,
                "gameID" TEXT REFERENCES "game"("id") ON DELETE SET NULL,
                "at" DATETIME NOT NULL,
                "category" TEXT NOT NULL,
                "rating" DOUBLE NOT NULL,
                "deviation" DOUBLE NOT NULL,
                "volatility" DOUBLE NOT NULL,
                "syncState" TEXT NOT NULL DEFAULT 'local'
            )
            """.trimIndent()
        )
        db.execSQL("""CREATE INDEX "index_ratingHistory_on_category_at" ON "ratingHistory"("category", "at")""")

        // Single-row table holding the resumable in-progress game.
        db.execSQL(
            """
            CREATE TABLE "inProgressGame" (
                "id" INTEGER PRIMARY KEY CHECK ("id" = 1),
                "startFEN" TEXT,
                "opponentType" TEXT NOT NULL,
                "engineLevel" INTEGER,
                "playerColor" TEXT NOT NULL,
                "tcInitialSeconds" INTEGER,
                "tcIncrementSeconds" INTEGER,
                "movesUCI" TEXT NOT NULL,
                "whiteRemainingMs" INTEGER,
                "blackRemainingMs" INTEGER,
                "updatedAt" DATETIME NOT NULL
            )
            """.trimIndent()
        )
        // movesUCI: space-separated
    }

    // AI coaching report cached with the game, so reopening a reviewed
    // game never re-requests (or looks like it would re-bill).
    private fun migrateV2CoachingJSON(db: SQLiteDatabase) {
        db.execSQL("""ALTER TABLE "game" ADD COLUMN "coachingJSON" TEXT""")
    }

    /** Runs `block` in a transaction on the calling thread (GRDB's `dbQueue.write`). */
    internal fun <T> write(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** Runs `block` on the calling thread (GRDB's `dbQueue.read`). */
    internal fun <T> read(block: (SQLiteDatabase) -> T): T = block(readableDatabase)

    companion object {
        /** Opens (and migrates) the app database, `chessmaster.sqlite` in the app's database folder. */
        fun open(): AppDatabase = AppDatabase(App.context.applicationContext, "chessmaster.sqlite").also {
            // Open now so a broken database fails at the composition root, like iOS.
            it.writableDatabase
        }

        /** In-memory database for tests and previews. */
        fun makeInMemory(): AppDatabase = AppDatabase(App.context.applicationContext, null)

        // Dates are stored the way GRDB stores them: UTC text "yyyy-MM-dd HH:mm:ss.SSS"
        // (sorts correctly as text).
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        internal fun dateText(instant: Instant): String =
            dateFormat.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

        internal fun date(text: String): Instant =
            LocalDateTime.parse(text, dateFormat).toInstant(ZoneOffset.UTC)
    }
}

// Cursor helpers shared by the records and repositories.

internal fun Cursor.dbString(column: String): String = getString(getColumnIndexOrThrow(column))
internal fun Cursor.dbStringOrNull(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
internal fun Cursor.dbIntOrNull(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
internal fun Cursor.dbLong(column: String): Long = getLong(getColumnIndexOrThrow(column))
internal fun Cursor.dbDouble(column: String): Double = getDouble(getColumnIndexOrThrow(column))
internal fun Cursor.dbDoubleOrNull(column: String): Double? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getDouble(it) }
internal fun Cursor.dbDate(column: String): Instant = AppDatabase.date(dbString(column))

/** Runs a query and maps every row (GRDB's `fetchAll`). */
internal fun <T> SQLiteDatabase.fetchAll(sql: String, args: Array<String> = emptyArray(), map: (Cursor) -> T): List<T> =
    rawQuery(sql, args).use { cursor ->
        val rows = ArrayList<T>(cursor.count)
        while (cursor.moveToNext()) rows.add(map(cursor))
        rows
    }

/** Runs a query and maps the first row, if any (GRDB's `fetchOne`). */
internal fun <T> SQLiteDatabase.fetchOne(sql: String, args: Array<String> = emptyArray(), map: (Cursor) -> T): T? =
    rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) map(cursor) else null }
