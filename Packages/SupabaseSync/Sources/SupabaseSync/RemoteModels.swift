// SupabaseSync — Chessmaster
// GPL-3.0-or-later
//
// Wire types for the backend tables (see supabase/migrations/0001_init.sql).

import Foundation
import PersistenceKit

public struct RemoteGame: Codable, Sendable {
    /// Server PK is set to the client's UUID so rating_history FKs line up
    /// and re-pushing the same game is a no-op upsert.
    public var id: UUID
    public var userId: UUID
    public var clientGameId: UUID
    public var opponentType: String
    public var engineLevel: Int?
    public var userColor: String
    public var timeControl: String?
    public var timeClass: String
    public var result: String
    public var termination: String?
    public var pgn: String
    public var evals: String?
    public var analysisProfile: String?
    public var ratingBefore: Double?
    public var ratingAfter: Double?
    public var playedAt: Date

    enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case clientGameId = "client_game_id"
        case opponentType = "opponent_type"
        case engineLevel = "engine_level"
        case userColor = "user_color"
        case timeControl = "time_control"
        case timeClass = "time_class"
        case result
        case termination
        case pgn
        case evals
        case analysisProfile = "analysis_profile"
        case ratingBefore = "rating_before"
        case ratingAfter = "rating_after"
        case playedAt = "played_at"
    }

    /// Maps a local record onto the wire shape. Returns nil for rows the
    /// backend rejects (e.g. aborted hot-seat games are fine, malformed
    /// client IDs are not).
    public init?(record: GameRecord, userId: UUID) {
        guard let clientGameId = UUID(uuidString: record.id) else { return nil }
        self.id = clientGameId
        self.userId = userId
        self.clientGameId = clientGameId
        // Server enum has no 'humanLocal'; hot-seat games sync as 'human'.
        self.opponentType = record.opponentType == "engine" ? "engine" : "human"
        self.engineLevel = record.engineLevel
        self.userColor = record.playerColor
        if let initial = record.tcInitialSeconds, let increment = record.tcIncrementSeconds {
            self.timeControl = "\(initial)+\(increment)"
        }
        self.timeClass = record.ratingCategory ?? Self.timeClass(initialSeconds: record.tcInitialSeconds, incrementSeconds: record.tcIncrementSeconds)
        self.result = switch record.result {
        case "whiteWin": "white_win"
        case "blackWin": "black_win"
        case "draw": "draw"
        default: "aborted"
        }
        self.termination = record.termination
        self.pgn = record.pgn
        self.evals = record.analysisJSON
        self.ratingBefore = record.ratingBefore
        self.ratingAfter = record.ratingAfter
        self.playedAt = record.endedAt
    }

    /// Lichess speed buckets, duplicated from TimeControl.category so this
    /// package doesn't depend on the chess domain.
    static func timeClass(initialSeconds: Int?, incrementSeconds: Int?) -> String {
        guard let initial = initialSeconds else { return "classical" }
        let estimate = initial + 40 * (incrementSeconds ?? 0)
        switch estimate {
        case ..<180: return "bullet"
        case ..<480: return "blitz"
        case ..<1500: return "rapid"
        default: return "classical"
        }
    }
}

public struct RemoteRatingHistory: Codable, Sendable {
    public var userId: UUID
    public var gameId: UUID?
    public var timeClass: String
    public var rating: Double
    public var rd: Double
    public var volatility: Double
    public var createdAt: Date

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case gameId = "game_id"
        case timeClass = "time_class"
        case rating
        case rd
        case volatility
        case createdAt = "created_at"
    }

    public init(record: RatingHistoryRecord, userId: UUID) {
        self.userId = userId
        self.gameId = record.gameID.flatMap(UUID.init(uuidString:))
        self.timeClass = record.category
        self.rating = record.rating
        self.rd = record.deviation
        self.volatility = record.volatility
        self.createdAt = record.at
    }
}
