// ChessDomain — Chessmaster
// GPL-3.0-or-later

import ChessKit

/// A move chosen by an opponent, in coordinate form.
public struct OpponentMove: Sendable, Hashable {
    public var from: Square
    public var to: Square
    public var promotion: Piece.Kind?

    public init(from: Square, to: Square, promotion: Piece.Kind? = nil) {
        self.from = from
        self.to = to
        self.promotion = promotion
    }

    /// Parse a UCI move string like "e2e4" or "e7e8q".
    public init?(uci: String) {
        guard uci.count == 4 || uci.count == 5 else { return nil }
        let from = Square(String(uci.prefix(2)))
        let to = Square(String(uci.dropFirst(2).prefix(2)))
        var promotion: Piece.Kind?
        if uci.count == 5 {
            switch uci.last {
            case "q": promotion = .queen
            case "r": promotion = .rook
            case "b": promotion = .bishop
            case "n": promotion = .knight
            default: return nil
            }
        }
        self.init(from: from, to: to, promotion: promotion)
    }
}

/// Supplies the opponent's moves. V1: EngineOpponent (Stockfish).
/// V2: RemoteOpponent (live multiplayer) implements the same protocol.
public protocol OpponentProvider: Sendable {
    /// Return the opponent's move for the current position.
    /// - Parameters:
    ///   - fen: FEN of the position to move in.
    ///   - remainingTime: opponent clock remaining, if timed (lets engines budget).
    func nextMove(fen: String, remainingTime: Duration?) async throws -> OpponentMove
}
