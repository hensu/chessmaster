// PersistenceKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import GRDB

public enum AppDatabase {
    /// Opens (and migrates) the app database at `url`.
    public static func makeQueue(at url: URL) throws -> DatabaseQueue {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let queue = try DatabaseQueue(path: url.path)
        try migrator.migrate(queue)
        return queue
    }

    /// In-memory database for tests and previews.
    public static func makeInMemory() throws -> DatabaseQueue {
        let queue = try DatabaseQueue()
        try migrator.migrate(queue)
        return queue
    }

    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v1") { db in
            try db.create(table: "game") { t in
                t.primaryKey("id", .text)                      // UUID
                t.column("startedAt", .datetime).notNull()
                t.column("endedAt", .datetime).notNull()
                t.column("opponentType", .text).notNull()      // engine | humanLocal
                t.column("engineLevel", .integer)
                t.column("playerColor", .text).notNull()       // white | black
                t.column("tcInitialSeconds", .integer)
                t.column("tcIncrementSeconds", .integer)
                t.column("result", .text).notNull()            // whiteWin | blackWin | draw | aborted
                t.column("termination", .text)                 // checkmate | resignation | timeout | draw reason
                t.column("pgn", .text).notNull()
                t.column("finalFEN", .text).notNull()
                t.column("ratingCategory", .text)
                t.column("ratingBefore", .double)
                t.column("ratingAfter", .double)
                t.column("syncState", .text).notNull().defaults(to: "local")
                t.column("analysisJSON", .text)
            }
            try db.create(indexOn: "game", columns: ["endedAt"])

            try db.create(table: "ratingHistory") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("gameID", .text).references("game", onDelete: .setNull)
                t.column("at", .datetime).notNull()
                t.column("category", .text).notNull()
                t.column("rating", .double).notNull()
                t.column("deviation", .double).notNull()
                t.column("volatility", .double).notNull()
                t.column("syncState", .text).notNull().defaults(to: "local")
            }
            try db.create(indexOn: "ratingHistory", columns: ["category", "at"])

            // Single-row table holding the resumable in-progress game.
            try db.create(table: "inProgressGame") { t in
                t.primaryKey("id", .integer).check { $0 == 1 }
                t.column("startFEN", .text)
                t.column("opponentType", .text).notNull()
                t.column("engineLevel", .integer)
                t.column("playerColor", .text).notNull()
                t.column("tcInitialSeconds", .integer)
                t.column("tcIncrementSeconds", .integer)
                t.column("movesUCI", .text).notNull()          // space-separated
                t.column("whiteRemainingMs", .integer)
                t.column("blackRemainingMs", .integer)
                t.column("updatedAt", .datetime).notNull()
            }
        }

        // AI coaching report cached with the game, so reopening a reviewed
        // game never re-requests (or looks like it would re-bill).
        migrator.registerMigration("v2-coachingJSON") { db in
            try db.alter(table: "game") { t in
                t.add(column: "coachingJSON", .text)
            }
        }

        return migrator
    }
}
