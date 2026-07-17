// Chessmaster — GPL-3.0-or-later
//
// Mapping between domain types and PersistenceKit's primitive records.

import Foundation
import ChessDomain
import ClockKit
import PersistenceKit

extension GameConfig {
    init?(record: InProgressGameRecord) {
        let opponent: Opponent
        switch record.opponentType {
        case "engine":
            opponent = .engine(level: record.engineLevel ?? 1)
        case "humanLocal":
            opponent = .humanLocal
        default:
            return nil
        }
        var timeControl: TimeControl?
        if let initial = record.tcInitialSeconds, let increment = record.tcIncrementSeconds {
            timeControl = TimeControl(initial: .seconds(initial), increment: .seconds(increment))
        }
        self.init(
            startFEN: record.startFEN,
            playerColor: record.playerColor == "black" ? .black : .white,
            timeControl: timeControl,
            opponent: opponent
        )
    }

    var opponentTypeString: String {
        switch opponent {
        case .engine: "engine"
        case .humanLocal: "humanLocal"
        }
    }

    var engineLevelValue: Int? {
        if case .engine(let level) = opponent { return level }
        return nil
    }
}

extension GameResult {
    var resultString: String {
        switch self {
        case .win(.white, _): "whiteWin"
        case .win(.black, _): "blackWin"
        case .draw: "draw"
        case .aborted: "aborted"
        }
    }

    var terminationString: String? {
        switch self {
        case .win(_, let termination): termination.rawValue
        case .draw(let reason): reason.rawValue
        case .aborted: nil
        }
    }
}

@MainActor
func makeInProgressRecord(session: GameSession, clock: ChessClock?) -> InProgressGameRecord {
    let config = session.config
    return InProgressGameRecord(
        startFEN: config.startFEN,
        opponentType: config.opponentTypeString,
        engineLevel: config.engineLevelValue,
        playerColor: config.playerColor == .black ? "black" : "white",
        tcInitialSeconds: config.timeControl.map { Int($0.initial.components.seconds) },
        tcIncrementSeconds: config.timeControl.map { Int($0.increment.components.seconds) },
        movesUCI: session.moveHistory.map(\.uci).joined(separator: " "),
        whiteRemainingMs: clock.map { Int($0.remaining(.white).milliseconds) },
        blackRemainingMs: clock.map { Int($0.remaining(.black).milliseconds) },
        updatedAt: .now
    )
}

extension Duration {
    var milliseconds: Int64 {
        components.seconds * 1000 + Int64(components.attoseconds / 1_000_000_000_000_000)
    }
}

/// Pieces for rendering a static FEN (history detail, analysis).
func boardPieces(fromFEN fen: String) -> [BoardPiece] {
    guard let position = Position(fen: fen) else { return [] }
    return position.pieces.enumerated().map { index, piece in
        BoardPiece(id: index, kind: piece.kind, color: piece.color, square: piece.square)
    }
}
