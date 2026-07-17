// ChessDomain — Chessmaster
// GPL-3.0-or-later

import ChessKit
import Foundation

/// A parsed game flattened to its main line — the input shape for analysis
/// and the replay UI.
public struct GameReplay: Sendable {
    public struct Ply: Sendable, Hashable, Identifiable {
        /// 1-based ply number.
        public let ply: Int
        public let san: String
        public let uci: String
        public let mover: Piece.Color
        /// FEN of the position after this move.
        public let fenAfter: String

        public var id: Int { ply }
        public var moveNumber: Int { (ply + 1) / 2 }
    }

    public let startFEN: String
    public let plies: [Ply]

    /// Parses PGN and walks the main variation.
    public init?(pgn: String) {
        guard let game = try? Game(pgn: pgn) else { return nil }
        guard let startPosition = game.positions[game.startingIndex] else { return nil }
        self.startFEN = startPosition.fen

        var plies: [Ply] = []
        let indices = game.moves.fullVariation(for: game.startingIndex)
        for index in indices {
            guard let move = game.moves[index],
                  let position = game.positions[index] else { continue }
            var uci = move.start.notation + move.end.notation
            if let promoted = move.promotedPiece {
                uci += promoted.kind.notation.lowercased()
            }
            plies.append(Ply(
                ply: plies.count + 1,
                san: move.san,
                uci: uci,
                mover: move.piece.color,
                fenAfter: position.fen
            ))
        }
        self.plies = plies
    }

    /// FEN before the given 1-based ply.
    public func fenBefore(ply: Int) -> String {
        ply <= 1 ? startFEN : plies[ply - 2].fenAfter
    }

    /// Pieces at a ply with identity that is stable ACROSS plies: ids are
    /// assigned once from the starting position and follow each piece
    /// through the moves. Rendering these lets the replay UI slide the
    /// moved piece, instead of refreshing every piece the way FEN-derived
    /// lists do (piece order differs between positions).
    public func boardPieces(atPly targetPly: Int) -> [BoardPiece] {
        guard let position = Position(fen: startFEN) else { return [] }
        var board = Board(position: position)
        var pieces = position.pieces.enumerated().map { index, piece in
            BoardPiece(id: index, kind: piece.kind, color: piece.color, square: piece.square)
        }
        for ply in plies.prefix(max(0, min(targetPly, plies.count))) {
            guard let uciMove = OpponentMove(uci: ply.uci),
                  var move = board.move(pieceAt: uciMove.from, to: uciMove.to)
            else { break }
            if case .promotion(let promoMove) = board.state {
                move = board.completePromotion(of: promoMove, to: uciMove.promotion ?? .queen)
            }
            apply(move, to: &pieces)
        }
        return pieces
    }

    private func apply(_ move: Move, to pieces: inout [BoardPiece]) {
        if case .capture(let captured) = move.result {
            // For en passant the captured pawn is not on `move.end`.
            pieces.removeAll { $0.square == captured.square }
        }
        if case .castle(let castling) = move.result {
            movePiece(from: castling.kingStart, to: castling.kingEnd, in: &pieces)
            movePiece(from: castling.rookStart, to: castling.rookEnd, in: &pieces)
        } else {
            movePiece(from: move.start, to: move.end, in: &pieces)
        }
        if let promoted = move.promotedPiece,
           let i = pieces.firstIndex(where: { $0.square == move.end }) {
            pieces[i].kind = promoted.kind
        }
    }

    private func movePiece(from: Square, to: Square, in pieces: inout [BoardPiece]) {
        guard let i = pieces.firstIndex(where: { $0.square == from }) else { return }
        pieces[i].square = to
    }
}
