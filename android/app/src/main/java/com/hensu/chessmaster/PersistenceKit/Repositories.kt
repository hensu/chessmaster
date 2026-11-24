// PersistenceKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import android.database.sqlite.SQLiteDatabase

class GameRepository(private val dbQueue: AppDatabase) {

    fun save(game: GameRecord) {
        dbQueue.write { saveGame(it, game) }
    }

    fun recentGames(limit: Int = 200): List<GameRecord> =
        dbQueue.read {
            it.fetchAll("""SELECT * FROM "game" ORDER BY "endedAt" DESC LIMIT $limit""", map = GameRecord::from)
        }

    fun game(id: String): GameRecord? =
        dbQueue.read { fetchGame(it, id) }

    fun delete(id: String) {
        dbQueue.write { it.delete("game", "id = ?", arrayOf(id)) }
    }

    fun updateAnalysis(id: String, analysisJSON: String) {
        dbQueue.write { db ->
            val game = fetchGame(db, id)
            if (game != null) {
                game.analysisJSON = analysisJSON
                // The evals ride along on sync; re-queue the row so a game
                // pushed at game-end gets its analysis uploaded too.
                game.syncState = "local"
                updateGame(db, game)
            }
        }
    }

    /**
     * Caches the AI coaching report with the game. Local-only cache — the
     * durable copy lives in the backend's coaching_reports table, so the
     * sync state is deliberately untouched.
     */
    fun updateCoaching(id: String, coachingJSON: String) {
        dbQueue.write { db ->
            val game = fetchGame(db, id)
            if (game != null) {
                game.coachingJSON = coachingJSON
                updateGame(db, game)
            }
        }
    }

    /** Games not yet pushed to the backend. */
    fun pendingSyncGames(): List<GameRecord> =
        dbQueue.read {
            it.fetchAll("""SELECT * FROM "game" WHERE "syncState" = 'local'""", map = GameRecord::from)
        }

    fun markGameSynced(id: String) {
        dbQueue.write { db ->
            val game = fetchGame(db, id)
            if (game != null) {
                game.syncState = "synced"
                updateGame(db, game)
            }
        }
    }

    /** Win/draw/loss counts (in that order) from the user's perspective for engine games. */
    fun stats(): Triple<Int, Int, Int> =
        dbQueue.read { db ->
            val games = db.fetchAll(
                """SELECT * FROM "game" WHERE "opponentType" = 'engine' AND "result" <> 'aborted'""",
                map = GameRecord::from,
            )
            var wins = 0
            var draws = 0
            var losses = 0
            for (game in games) {
                when {
                    game.result == "draw" -> draws += 1
                    (game.result == "whiteWin" && game.playerColor == "white") ||
                        (game.result == "blackWin" && game.playerColor == "black") -> wins += 1
                    else -> losses += 1
                }
            }
            Triple(wins, draws, losses)
        }

    private fun fetchGame(db: SQLiteDatabase, id: String): GameRecord? =
        db.fetchOne("""SELECT * FROM "game" WHERE "id" = ?""", arrayOf(id), GameRecord::from)

    private fun updateGame(db: SQLiteDatabase, game: GameRecord): Int =
        db.update("game", game.values(), "id = ?", arrayOf(game.id))

    /**
     * Update the row if it exists, insert it otherwise (GRDB's `save`). Not INSERT OR REPLACE:
     * that deletes the old row first, which would null out ratingHistory.gameID.
     */
    private fun saveGame(db: SQLiteDatabase, game: GameRecord) {
        if (updateGame(db, game) == 0) db.insertOrThrow("game", null, game.values())
    }
}

class RatingHistoryRepository(private val dbQueue: AppDatabase) {

    fun append(record: RatingHistoryRecord) {
        dbQueue.write { record.didInsert(it.insertOrThrow("ratingHistory", null, record.values())) }
    }

    fun history(category: String, limit: Int = 500): List<RatingHistoryRecord> =
        dbQueue.read {
            it.fetchAll(
                """SELECT * FROM "ratingHistory" WHERE "category" = ? ORDER BY "at" ASC LIMIT $limit""",
                arrayOf(category),
                RatingHistoryRecord::from,
            )
        }

    fun pendingSync(): List<RatingHistoryRecord> =
        dbQueue.read {
            it.fetchAll("""SELECT * FROM "ratingHistory" WHERE "syncState" = 'local'""", map = RatingHistoryRecord::from)
        }

    fun markSynced(id: Long) {
        dbQueue.write {
            it.execSQL("""UPDATE "ratingHistory" SET "syncState" = 'synced' WHERE "id" = ?""", arrayOf<Any>(id))
        }
    }

    /** Latest stored rating per category (source of truth for RatingStore). */
    fun latestPerCategory(): Map<String, RatingHistoryRecord> =
        dbQueue.read { db ->
            val rows = db.fetchAll("""SELECT * FROM "ratingHistory" ORDER BY "at" DESC""", map = RatingHistoryRecord::from)
            val latest = LinkedHashMap<String, RatingHistoryRecord>()
            for (row in rows) {
                if (latest[row.category] == null) latest[row.category] = row
            }
            latest
        }
}

class InProgressGameStore(private val dbQueue: AppDatabase) {

    fun save(record: InProgressGameRecord) {
        dbQueue.write { db ->
            // Single row (id = 1): update it, or insert it the first time.
            val values = record.values()
            if (db.update("inProgressGame", values, "id = ?", arrayOf(record.id.toString())) == 0) {
                db.insertOrThrow("inProgressGame", null, values)
            }
        }
    }

    fun load(): InProgressGameRecord? =
        dbQueue.read { it.fetchOne("""SELECT * FROM "inProgressGame" LIMIT 1""", map = InProgressGameRecord::from) }

    fun clear() {
        dbQueue.write { it.delete("inProgressGame", null, null) }
    }
}
