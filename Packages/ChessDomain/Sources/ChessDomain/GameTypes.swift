// ChessDomain — Chessmaster
// GPL-3.0-or-later

import ChessKit
import Foundation

public enum GameResult: Hashable, Sendable, Codable {
    case win(Piece.Color, Termination)
    case draw(DrawReason)
    case aborted

    public enum Termination: String, Hashable, Sendable, Codable {
        case checkmate, resignation, timeout
    }

    public enum DrawReason: String, Hashable, Sendable, Codable {
        case agreement, fiftyMoves, insufficientMaterial, repetition, stalemate
    }

    /// PGN result tag value.
    public var pgnResult: String {
        switch self {
        case .win(.white, _): "1-0"
        case .win(.black, _): "0-1"
        case .draw: "1/2-1/2"
        case .aborted: "*"
        }
    }
}

public enum GameStatus: Hashable, Sendable {
    case idle
    case playing
    case finished(GameResult)

    public var isPlaying: Bool { self == .playing }
}

/// One move as played, with everything downstream consumers need
/// (move list UI, PGN export, analysis, sync).
public struct PlayedMove: Hashable, Sendable, Identifiable {
    /// 1-based ply (1 = White's first move).
    public let ply: Int
    public let san: String
    public let uci: String
    public let mover: Piece.Color
    public let isCapture: Bool
    public let isCastle: Bool
    public let isPromotion: Bool
    public let givesCheck: Bool
    /// Mover's clock after the move, if the game is timed.
    public var clockRemaining: Duration?

    public var id: Int { ply }

    /// Full-move number this ply belongs to.
    public var moveNumber: Int { (ply + 1) / 2 }

    public init(
        ply: Int, san: String, uci: String, mover: Piece.Color,
        isCapture: Bool, isCastle: Bool, isPromotion: Bool, givesCheck: Bool,
        clockRemaining: Duration? = nil
    ) {
        self.ply = ply
        self.san = san
        self.uci = uci
        self.mover = mover
        self.isCapture = isCapture
        self.isCastle = isCastle
        self.isPromotion = isPromotion
        self.givesCheck = givesCheck
        self.clockRemaining = clockRemaining
    }
}

/// Events emitted by GameSession, consumed by audio/haptics/persistence.
public enum GameEvent: Sendable {
    case gameStarted
    case movePlayed(PlayedMove)
    case gameEnded(GameResult)
}

public struct GameConfig: Sendable, Hashable, Identifiable {
    public var id: Self { self }

    /// nil = standard starting position; set for retry-training games.
    public var startFEN: String?
    public var playerColor: Piece.Color
    public var timeControl: TimeControl?
    public var opponent: Opponent
    /// Probability the engine plays a random legal move instead of its
    /// best — strength below the ladder's level-1 floor for players still
    /// losing there. 0 = engine plays at its level.
    public var engineBlunderProbability: Double

    public enum Opponent: Sendable, Hashable {
        /// Both sides played on this device (M1 hot-seat; also useful for tests).
        case humanLocal
        /// Stockfish at a ladder level (1...10).
        case engine(level: Int)
    }

    public init(
        startFEN: String? = nil,
        playerColor: Piece.Color = .white,
        timeControl: TimeControl? = nil,
        opponent: Opponent = .humanLocal,
        engineBlunderProbability: Double = 0
    ) {
        self.startFEN = startFEN
        self.playerColor = playerColor
        self.timeControl = timeControl
        self.opponent = opponent
        self.engineBlunderProbability = engineBlunderProbability
    }
}
