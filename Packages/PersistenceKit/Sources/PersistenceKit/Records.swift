// PersistenceKit — Chessmaster
// GPL-3.0-or-later
//
// Records are deliberately primitive (strings/ints) so this package stays
// independent of the chess domain; the app maps to domain types.

import Foundation
import GRDB

public struct GameRecord: Codable, Hashable, Identifiable, Sendable,
                          FetchableRecord, PersistableRecord {
    public static let databaseTableName = "game"

    public var id: String
    public var startedAt: Date
    public var endedAt: Date
    public var opponentType: String
    public var engineLevel: Int?
    public var playerColor: String
    public var tcInitialSeconds: Int?
    public var tcIncrementSeconds: Int?
    public var result: String
    public var termination: String?
    public var pgn: String
    public var finalFEN: String
    public var ratingCategory: String?
    public var ratingBefore: Double?
    public var ratingAfter: Double?
    public var syncState: String
    public var analysisJSON: String?
    /// Cached AI coaching report (JSON), so reviews never regenerate.
    public var coachingJSON: String?

    public init(
        id: String = UUID().uuidString,
        startedAt: Date,
        endedAt: Date,
        opponentType: String,
        engineLevel: Int? = nil,
        playerColor: String,
        tcInitialSeconds: Int? = nil,
        tcIncrementSeconds: Int? = nil,
        result: String,
        termination: String? = nil,
        pgn: String,
        finalFEN: String,
        ratingCategory: String? = nil,
        ratingBefore: Double? = nil,
        ratingAfter: Double? = nil,
        syncState: String = "local",
        analysisJSON: String? = nil,
        coachingJSON: String? = nil
    ) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.opponentType = opponentType
        self.engineLevel = engineLevel
        self.playerColor = playerColor
        self.tcInitialSeconds = tcInitialSeconds
        self.tcIncrementSeconds = tcIncrementSeconds
        self.result = result
        self.termination = termination
        self.pgn = pgn
        self.finalFEN = finalFEN
        self.ratingCategory = ratingCategory
        self.ratingBefore = ratingBefore
        self.ratingAfter = ratingAfter
        self.syncState = syncState
        self.analysisJSON = analysisJSON
        self.coachingJSON = coachingJSON
    }
}

public struct RatingHistoryRecord: Codable, Hashable, Identifiable, Sendable,
                                   FetchableRecord, PersistableRecord {
    public static let databaseTableName = "ratingHistory"

    public var id: Int64?
    public var gameID: String?
    public var at: Date
    public var category: String
    public var rating: Double
    public var deviation: Double
    public var volatility: Double
    public var syncState: String

    public init(
        id: Int64? = nil,
        gameID: String?,
        at: Date,
        category: String,
        rating: Double,
        deviation: Double,
        volatility: Double,
        syncState: String = "local"
    ) {
        self.id = id
        self.gameID = gameID
        self.at = at
        self.category = category
        self.rating = rating
        self.deviation = deviation
        self.volatility = volatility
        self.syncState = syncState
    }

    public mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }
}

public struct InProgressGameRecord: Codable, Hashable, Sendable,
                                    FetchableRecord, PersistableRecord {
    public static let databaseTableName = "inProgressGame"

    public var id: Int64 = 1
    public var startFEN: String?
    public var opponentType: String
    public var engineLevel: Int?
    public var playerColor: String
    public var tcInitialSeconds: Int?
    public var tcIncrementSeconds: Int?
    public var movesUCI: String
    public var whiteRemainingMs: Int?
    public var blackRemainingMs: Int?
    public var updatedAt: Date

    public init(
        startFEN: String?,
        opponentType: String,
        engineLevel: Int?,
        playerColor: String,
        tcInitialSeconds: Int?,
        tcIncrementSeconds: Int?,
        movesUCI: String,
        whiteRemainingMs: Int?,
        blackRemainingMs: Int?,
        updatedAt: Date
    ) {
        self.startFEN = startFEN
        self.opponentType = opponentType
        self.engineLevel = engineLevel
        self.playerColor = playerColor
        self.tcInitialSeconds = tcInitialSeconds
        self.tcIncrementSeconds = tcIncrementSeconds
        self.movesUCI = movesUCI
        self.whiteRemainingMs = whiteRemainingMs
        self.blackRemainingMs = blackRemainingMs
        self.updatedAt = updatedAt
    }
}
