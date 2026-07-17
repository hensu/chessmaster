// AnalysisKit — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import EngineKit
import Foundation

public struct PlyEval: Codable, Sendable, Hashable, Identifiable {
    public let ply: Int
    public let san: String
    public let uci: String
    public let moverIsWhite: Bool
    /// White-POV centipawns after the move (mates mapped to ±10_000).
    public let cpAfter: Int
    /// Mate-in-N after the move (white POV sign), if forced.
    public let mateAfter: Int?
    /// Win probability for white after the move (0-100).
    public let winPercentWhite: Double
    public let classification: MoveClassification
    /// Engine's preferred move in the position *before* this move
    /// (what should have been played), when it differs meaningfully.
    public let bestMoveUCI: String?
    /// The engine's line after its preferred move (UCI, ≤8 plies) — the
    /// concrete refutation the coach can show. Only kept for flagged moves.
    public let bestLineUCI: [String]?

    public var id: Int { ply }
}

public struct GameAnalysis: Codable, Sendable {
    public let plies: [PlyEval]
    public let accuracyWhite: Double
    public let accuracyBlack: Double
    public let depthProfile: String   // e.g. "movetime 120ms" — provenance

    public var criticalPlies: [PlyEval] {
        plies.filter { $0.classification == .blunder || $0.classification == .mistake }
    }
}

/// Post-game eval pass: walks the game, searches every position with the
/// shared engine, classifies each move lichess-style. Progress streams back
/// so the UI can annotate incrementally.
public actor GameAnalyzer {
    public enum AnalyzerError: Error {
        case badPGN
        case engineUnavailable
    }

    private let engine: UCIEngine
    private let evalFileBig: URL
    private let evalFileSmall: URL

    public init(engine: UCIEngine = .shared, evalFileBig: URL, evalFileSmall: URL) {
        self.engine = engine
        self.evalFileBig = evalFileBig
        self.evalFileSmall = evalFileSmall
    }

    /// Analyze a full game.
    /// - Parameters:
    ///   - pgn: the game to analyze.
    ///   - movetimeMs: engine budget per position (free blunder-check: 120,
    ///     premium deep: 500).
    ///   - onProgress: called after each ply with (done, total).
    public func analyze(
        pgn: String,
        movetimeMs: Int,
        onProgress: (@Sendable (Int, Int) -> Void)? = nil
    ) async throws -> GameAnalysis {
        guard let replay = GameReplay(pgn: pgn) else { throw AnalyzerError.badPGN }

        try await engine.startIfNeeded(evalFileBig: evalFileBig, evalFileSmall: evalFileSmall)
        // Full strength for analysis regardless of last game level.
        try await engine.apply(options: [
            "Skill Level": "20",
            "UCI_LimitStrength": "false",
        ])
        try await engine.newGame()

        // Eval of every position: index 0 = start, i = after ply i.
        var cpByPosition: [Int] = []
        var mateByPosition: [Int?] = []
        var bestMoveByPosition: [String?] = []
        var pvByPosition: [[String]] = []

        let fens = [replay.startFEN] + replay.plies.map(\.fenAfter)
        for (i, fen) in fens.enumerated() {
            // A "#" in the SAN that produced this position is authoritative:
            // no engine call needed (and a mated position has no bestmove).
            if i > 0, replay.plies[i - 1].san.hasSuffix("#") {
                let moverWasWhite = replay.plies[i - 1].mover == .white
                cpByPosition.append(moverWasWhite ? 10_000 : -10_000)
                mateByPosition.append(nil)
                bestMoveByPosition.append(nil)
                pvByPosition.append([])
            } else {
                let (cp, mate, best, pv) = try await evaluate(fen: fen, movetimeMs: movetimeMs)
                cpByPosition.append(cp)
                mateByPosition.append(mate)
                bestMoveByPosition.append(best)
                pvByPosition.append(pv)
            }
            onProgress?(i + 1, fens.count)
        }

        var plies: [PlyEval] = []
        var accuracySumWhite = 0.0, accuracyCountWhite = 0.0
        var accuracySumBlack = 0.0, accuracyCountBlack = 0.0

        for plyInfo in replay.plies {
            let i = plyInfo.ply
            let moverIsWhite = plyInfo.mover == .white
            let before = MoveClassifier.winPercent(cpWhite: cpByPosition[i - 1], for: moverIsWhite)
            let after = MoveClassifier.winPercent(cpWhite: cpByPosition[i], for: moverIsWhite)
            let drop = max(0, before - after)
            // Never flag the engine's own best move (a forced defense that
            // "loses less" is not a mistake), nor moves in decided positions.
            let playedBest = bestMoveByPosition[i - 1] == plyInfo.uci
            let classification = (playedBest || MoveClassifier.isPositionDecided(moverWinPercentBefore: before))
                ? MoveClassification.good
                : MoveClassifier.classify(winPercentDrop: drop)
            let accuracy = MoveClassifier.accuracy(winPercentDrop: drop)
            if moverIsWhite {
                accuracySumWhite += accuracy; accuracyCountWhite += 1
            } else {
                accuracySumBlack += accuracy; accuracyCountBlack += 1
            }

            let best = bestMoveByPosition[i - 1]
            plies.append(PlyEval(
                ply: i,
                san: plyInfo.san,
                uci: plyInfo.uci,
                moverIsWhite: moverIsWhite,
                cpAfter: cpByPosition[i],
                mateAfter: mateByPosition[i],
                winPercentWhite: MoveClassifier.winPercent(cpWhite: cpByPosition[i], for: true),
                classification: classification,
                bestMoveUCI: playedBest ? nil : best,
                bestLineUCI: (playedBest || pvByPosition[i - 1].isEmpty)
                    ? nil
                    : pvByPosition[i - 1]
            ))
        }

        return GameAnalysis(
            plies: plies,
            accuracyWhite: accuracyCountWhite > 0 ? accuracySumWhite / accuracyCountWhite : 100,
            accuracyBlack: accuracyCountBlack > 0 ? accuracySumBlack / accuracyCountBlack : 100,
            depthProfile: "movetime \(movetimeMs)ms"
        )
    }

    /// Returns (cp white-POV, mate white-POV, bestmove) for a FEN.
    /// Terminal positions (no legal moves) are synthesized from the rules.
    private func evaluate(fen: String, movetimeMs: Int) async throws -> (Int, Int?, String?, [String]) {
        let sideToMoveIsWhite = fen.split(separator: " ").dropFirst().first == "w"
        do {
            let result = try await engine.search(fen: fen, movetimeMs: movetimeMs)
            var cp: Int
            var mate: Int?
            if let mateIn = result.info.scoreMate {
                mate = sideToMoveIsWhite ? mateIn : -mateIn
                cp = mateIn > 0 ? 10_000 : -10_000
                if !sideToMoveIsWhite { cp = -cp }
            } else {
                cp = result.info.scoreCp ?? 0
                if !sideToMoveIsWhite { cp = -cp }
            }
            return (cp, mate, result.bestMoveUCI, Array(result.info.pv.prefix(8)))
        } catch UCIEngineError.noBestMove {
            // Checkmate or stalemate on the board.
            guard let position = Position(fen: fen) else { return (0, nil, nil, []) }
            let board = Board(position: position)
            let anyMove = position.pieces
                .filter { $0.color == position.sideToMove }
                .contains { !board.legalMoves(forPieceAt: $0.square).isEmpty }
            if anyMove { return (0, nil, nil, []) }
            // No legal moves: mate if in check, stalemate otherwise.
            if case .checkmate = board.state {
                return (sideToMoveIsWhite ? -10_000 : 10_000, nil, nil, [])
            }
            return (0, nil, nil, [])
        }
    }
}
