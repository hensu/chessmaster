// Chessmaster — GPL-3.0-or-later
import Foundation
import ChessDomain
import ChessKit
import EngineKit

enum EngineOpponentError: Error {
    case nnueMissing
    case badMove(String)
}

/// OpponentProvider backed by the embedded Stockfish at a ladder level.
struct EngineOpponent: OpponentProvider {
    let level: StrengthLevel
    /// Chance per move of playing a random legal move instead of the
    /// engine's choice — how "Auto" gets weaker than level 1.
    let blunderProbability: Double

    init(level: Int, blunderProbability: Double = 0) {
        let strength = StrengthLevel.level(level)
        self.level = strength
        // The ladder's own handicap keeps low levels at their advertised
        // strength; adaptive easing can push further, never weaker.
        self.blunderProbability = max(strength.blunderProbability, blunderProbability)
    }

    func nextMove(fen: String, remainingTime: Duration?) async throws -> OpponentMove {
        // A sub-100ms reply feels jarring; keep a small minimum think time.
        let minimumThink = ContinuousClock.now + .milliseconds(300)

        if Double.random(in: 0..<1) < blunderProbability, let move = randomMove(fen: fen) {
            try? await Task.sleep(until: minimumThink)
            return move
        }

        guard
            let big = Bundle.main.url(forResource: "nn-c288c895ea92", withExtension: "nnue"),
            let small = Bundle.main.url(forResource: "nn-37f18f62d772", withExtension: "nnue")
        else { throw EngineOpponentError.nnueMissing }

        let engine = UCIEngine.shared
        try await engine.startIfNeeded(evalFileBig: big, evalFileSmall: small)
        try await engine.apply(options: level.uciOptions)

        let result = try await engine.search(fen: fen, movetimeMs: level.movetimeMs, depth: level.depth)
        try? await Task.sleep(until: minimumThink)

        guard let move = OpponentMove(uci: result.bestMoveUCI) else {
            throw EngineOpponentError.badMove(result.bestMoveUCI)
        }
        return move
    }

    /// A uniformly random legal move for the side to move.
    private func randomMove(fen: String) -> OpponentMove? {
        guard let position = Position(fen: fen) else { return nil }
        let board = Board(position: position)
        let moves = position.pieces
            .filter { $0.color == position.sideToMove }
            .flatMap { piece in
                board.legalMoves(forPieceAt: piece.square).map { (piece, $0) }
            }
        guard let (piece, target) = moves.randomElement() else { return nil }
        let promotes = piece.kind == .pawn && (target.rank.value == 1 || target.rank.value == 8)
        return OpponentMove(from: piece.square, to: target, promotion: promotes ? .queen : nil)
    }
}
