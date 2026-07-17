// PersistenceKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import GRDB

public struct GameRepository: Sendable {
    private let dbQueue: DatabaseQueue

    public init(dbQueue: DatabaseQueue) {
        self.dbQueue = dbQueue
    }

    public func save(_ game: GameRecord) throws {
        try dbQueue.write { try game.save($0) }
    }

    public func recentGames(limit: Int = 200) throws -> [GameRecord] {
        try dbQueue.read {
            try GameRecord.order(Column("endedAt").desc).limit(limit).fetchAll($0)
        }
    }

    public func game(id: String) throws -> GameRecord? {
        try dbQueue.read { try GameRecord.fetchOne($0, key: id) }
    }

    public func delete(id: String) throws {
        _ = try dbQueue.write { try GameRecord.deleteOne($0, key: id) }
    }

    public func updateAnalysis(id: String, analysisJSON: String) throws {
        try dbQueue.write { db in
            if var game = try GameRecord.fetchOne(db, key: id) {
                game.analysisJSON = analysisJSON
                // The evals ride along on sync; re-queue the row so a game
                // pushed at game-end gets its analysis uploaded too.
                game.syncState = "local"
                try game.update(db)
            }
        }
    }

    /// Caches the AI coaching report with the game. Local-only cache — the
    /// durable copy lives in the backend's coaching_reports table, so the
    /// sync state is deliberately untouched.
    public func updateCoaching(id: String, coachingJSON: String) throws {
        try dbQueue.write { db in
            if var game = try GameRecord.fetchOne(db, key: id) {
                game.coachingJSON = coachingJSON
                try game.update(db)
            }
        }
    }

    /// Games not yet pushed to the backend.
    public func pendingSyncGames() throws -> [GameRecord] {
        try dbQueue.read {
            try GameRecord.filter(Column("syncState") == "local").fetchAll($0)
        }
    }

    public func markGameSynced(id: String) throws {
        try dbQueue.write { db in
            if var game = try GameRecord.fetchOne(db, key: id) {
                game.syncState = "synced"
                try game.update(db)
            }
        }
    }

    /// Win/draw/loss counts from the user's perspective for engine games.
    public func stats() throws -> (wins: Int, draws: Int, losses: Int) {
        try dbQueue.read { db in
            let games = try GameRecord
                .filter(Column("opponentType") == "engine")
                .filter(Column("result") != "aborted")
                .fetchAll(db)
            var wins = 0, draws = 0, losses = 0
            for game in games {
                switch (game.result, game.playerColor) {
                case ("draw", _): draws += 1
                case ("whiteWin", "white"), ("blackWin", "black"): wins += 1
                default: losses += 1
                }
            }
            return (wins, draws, losses)
        }
    }
}

public struct RatingHistoryRepository: Sendable {
    private let dbQueue: DatabaseQueue

    public init(dbQueue: DatabaseQueue) {
        self.dbQueue = dbQueue
    }

    public func append(_ record: RatingHistoryRecord) throws {
        try dbQueue.write { try record.insert($0) }
    }

    public func history(category: String, limit: Int = 500) throws -> [RatingHistoryRecord] {
        try dbQueue.read {
            try RatingHistoryRecord
                .filter(Column("category") == category)
                .order(Column("at").asc)
                .limit(limit)
                .fetchAll($0)
        }
    }

    public func pendingSync() throws -> [RatingHistoryRecord] {
        try dbQueue.read {
            try RatingHistoryRecord.filter(Column("syncState") == "local").fetchAll($0)
        }
    }

    public func markSynced(id: Int64) throws {
        try dbQueue.write { db in
            if var row = try RatingHistoryRecord.fetchOne(db, key: id) {
                row.syncState = "synced"
                try row.update(db)
            }
        }
    }

    /// Latest stored rating per category (source of truth for RatingStore).
    public func latestPerCategory() throws -> [String: RatingHistoryRecord] {
        try dbQueue.read { db in
            let rows = try RatingHistoryRecord.order(Column("at").desc).fetchAll(db)
            var latest: [String: RatingHistoryRecord] = [:]
            for row in rows where latest[row.category] == nil {
                latest[row.category] = row
            }
            return latest
        }
    }
}

public struct InProgressGameStore: Sendable {
    private let dbQueue: DatabaseQueue

    public init(dbQueue: DatabaseQueue) {
        self.dbQueue = dbQueue
    }

    public func save(_ record: InProgressGameRecord) throws {
        try dbQueue.write { try record.save($0) }
    }

    public func load() throws -> InProgressGameRecord? {
        try dbQueue.read { try InProgressGameRecord.fetchOne($0) }
    }

    public func clear() throws {
        _ = try dbQueue.write { try InProgressGameRecord.deleteAll($0) }
    }
}
