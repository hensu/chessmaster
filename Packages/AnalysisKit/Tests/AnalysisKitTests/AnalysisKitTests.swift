// AnalysisKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Testing
import ChessDomain
@testable import AnalysisKit

@Suite struct MoveClassifierTests {
    @Test func winPercentIsSymmetricAroundEqual() {
        #expect(abs(MoveClassifier.winPercent(cpWhite: 0, for: true) - 50) < 0.001)
        #expect(abs(MoveClassifier.winPercent(cpWhite: 0, for: false) - 50) < 0.001)
        let up = MoveClassifier.winPercent(cpWhite: 200, for: true)
        let down = MoveClassifier.winPercent(cpWhite: -200, for: true)
        #expect(abs(up + down - 100) < 0.001)
        // +200 for white is the same win% as -200 seen from black.
        #expect(abs(up - MoveClassifier.winPercent(cpWhite: -200, for: false)) < 0.001)
        #expect(up > 60 && up < 80)
    }

    @Test func thresholdsMatchLichess() {
        #expect(MoveClassifier.classify(winPercentDrop: 5) == .good)
        #expect(MoveClassifier.classify(winPercentDrop: 12) == .inaccuracy)
        #expect(MoveClassifier.classify(winPercentDrop: 22) == .mistake)
        #expect(MoveClassifier.classify(winPercentDrop: 35) == .blunder)
    }

    @Test func accuracyCurve() {
        #expect(MoveClassifier.accuracy(winPercentDrop: 0) > 99.99)
        #expect(MoveClassifier.accuracy(winPercentDrop: 50) < 15)
        #expect(MoveClassifier.accuracy(winPercentDrop: 10) > MoveClassifier.accuracy(winPercentDrop: 20))
    }
}

@Suite struct GameReplayTests {
    @Test func parsesMainLineWithFENs() throws {
        let replay = try #require(GameReplay(pgn: "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0"))
        #expect(replay.plies.count == 6)
        #expect(replay.plies[0].san == "e4")
        #expect(replay.plies[0].uci == "e2e4")
        #expect(replay.plies[0].mover == .white)
        #expect(replay.plies[0].fenAfter.hasPrefix("rnbqkbnr/pppppppp/8/8/4P3"))
        #expect(replay.plies[5].mover == .black)
        #expect(replay.fenBefore(ply: 1) == replay.startFEN)
        #expect(replay.fenBefore(ply: 2) == replay.plies[0].fenAfter)
    }

    @Test func promotionUCIIncludesPiece() throws {
        // White promotes: set up via PGN with promotion.
        let pgn = """
        [FEN "8/P6k/8/8/8/8/8/K7 w - - 0 1"]
        [SetUp "1"]

        1. a8=Q 1-0
        """
        let replay = try #require(GameReplay(pgn: pgn))
        #expect(replay.plies.count == 1)
        #expect(replay.plies[0].uci == "a7a8q")
    }
}

/// Full engine-backed analysis of a short game with a known blunder.
@Suite(.serialized) struct GameAnalyzerLiveTests {
    private var nnueDir: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Chessmaster/Resources/NNUE")
    }

    @Test func flagsScholarsMateDefenseAsBlunder() async throws {
        let big = nnueDir.appendingPathComponent("nn-c288c895ea92.nnue")
        let small = nnueDir.appendingPathComponent("nn-37f18f62d772.nnue")
        try #require(FileManager.default.fileExists(atPath: big.path))

        let analyzer = GameAnalyzer(evalFileBig: big, evalFileSmall: small)
        // 3...Nf6?? allows Qxf7#.
        let analysis = try await analyzer.analyze(
            pgn: "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0",
            movetimeMs: 150
        )
        #expect(analysis.plies.count == 7)

        let nf6 = analysis.plies[5]
        #expect(nf6.san == "Nf6")
        #expect(nf6.classification == .blunder, "3...Nf6 must be flagged as a blunder")
        #expect(nf6.bestMoveUCI != nil)

        // Final position is mate: eval pegged for white.
        #expect(analysis.plies[6].cpAfter == 10_000)
        // White played perfectly per the engine's own line or close to it.
        #expect(analysis.accuracyBlack < analysis.accuracyWhite)
    }
}
