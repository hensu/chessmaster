// EngineKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Testing
@testable import EngineKit

@Suite struct InfoParsingTests {
    @Test func parsesDepthScorePv() {
        let line = "info depth 18 seldepth 24 multipv 1 score cp 35 nodes 12345 nps 99999 time 123 pv e2e4 e7e5 g1f3"
        let info = UCIEngine.parseInfo(line)
        #expect(info?.depth == 18)
        #expect(info?.scoreCp == 35)
        #expect(info?.pv.first == "e2e4")
        #expect(info?.pv.count == 3)
    }

    @Test func parsesMateScore() {
        let info = UCIEngine.parseInfo("info depth 12 score mate 3 pv d1h5")
        #expect(info?.scoreMate == 3)
        #expect(info?.scoreCp == nil)
    }
}

@Suite struct StrengthLevelTests {
    @Test func ladderIsComplete() {
        #expect(StrengthLevel.all.count == 10)
        #expect(StrengthLevel.all.map(\.level) == Array(1...10))
        // Rating estimates must rise monotonically.
        let ratings = StrengthLevel.all.map(\.ratingEstimate)
        #expect(ratings == ratings.sorted())
        #expect(StrengthLevel.level(0).level == 1)
        #expect(StrengthLevel.level(99).level == 10)
    }

    @Test func lowLevelsUseSkillHighLevelsUseElo() {
        #expect(StrengthLevel.level(1).uciOptions["Skill Level"] == "0")
        #expect(StrengthLevel.level(7).uciOptions["UCI_LimitStrength"] == "true")
        #expect(StrengthLevel.level(7).uciOptions["UCI_Elo"] == "1800")
        #expect(StrengthLevel.level(10).uciOptions["UCI_LimitStrength"] == "false")
    }
}

/// Runs the real embedded engine. Needs the NNUE nets fetched into
/// Chessmaster/Resources/NNUE (see scripts/fetch-nnue.sh).
@Suite(.serialized) struct LiveEngineTests {
    private var nnueDir: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // -> EngineKitTests/
            .deletingLastPathComponent()   // -> Tests/
            .deletingLastPathComponent()   // -> EngineKit/
            .deletingLastPathComponent()   // -> Packages/
            .deletingLastPathComponent()   // -> repo root
            .appendingPathComponent("Chessmaster/Resources/NNUE")
    }

    @Test func engineFindsOpeningMoveAndMateInOne() async throws {
        let big = nnueDir.appendingPathComponent("nn-c288c895ea92.nnue")
        let small = nnueDir.appendingPathComponent("nn-37f18f62d772.nnue")
        try #require(FileManager.default.fileExists(atPath: big.path), "run scripts/fetch-nnue.sh first")

        let engine = UCIEngine.shared
        try await engine.startIfNeeded(evalFileBig: big, evalFileSmall: small)
        try await engine.newGame()

        // Sane move from the start position.
        let start = try await engine.search(
            fen: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            movetimeMs: 300
        )
        #expect(start.bestMoveUCI.count >= 4)

        // Mate in one: scholar's mate position, white to play Qxf7#.
        let mate = try await engine.search(
            fen: "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1KNR1 w Qkq - 4 4",
            movetimeMs: 500
        )
        #expect(mate.bestMoveUCI == "h5f7")
        #expect(mate.info.scoreMate == 1)

        // Strength options apply cleanly.
        try await engine.apply(options: StrengthLevel.level(3).uciOptions)
        let weak = try await engine.search(
            fen: "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            movetimeMs: 100,
            depth: 5
        )
        #expect(weak.bestMoveUCI.count >= 4)
        try await engine.apply(options: StrengthLevel.level(10).uciOptions)
    }
}

@Suite struct StrengthLadderTests {
    /// Low rungs must blunder deliberately (Stockfish's natural floor is
    /// far above "Newcomer") and the handicap must fade monotonically.
    @Test func blunderRatesDescendAndVanish() {
        #expect(StrengthLevel.level(1).blunderProbability == 0.45)
        #expect(StrengthLevel.level(2).blunderProbability == 0.30)
        for n in 1..<10 {
            #expect(StrengthLevel.level(n).blunderProbability
                    >= StrengthLevel.level(n + 1).blunderProbability)
        }
        for n in 6...10 {
            #expect(StrengthLevel.level(n).blunderProbability == 0)
        }
    }

    @Test func ratingEstimatesAscend() {
        for n in 1..<10 {
            #expect(StrengthLevel.level(n).ratingEstimate
                    < StrengthLevel.level(n + 1).ratingEstimate)
        }
    }
}

@Suite struct WeakMovePickerTests {
    private let candidates = [
        CandidateMove(moveUCI: "e2e4", scoreCp: 40, scoreMate: nil),
        CandidateMove(moveUCI: "d2d4", scoreCp: -20, scoreMate: nil),
        CandidateMove(moveUCI: "a2a3", scoreCp: -180, scoreMate: nil),
    ]

    @Test func zeroTemperaturePicksBest() {
        #expect(WeakMovePicker.pick(candidates, temperatureCp: 0, random: 0.99) == 0)
    }

    @Test func lowTemperatureStronglyPrefersBest() {
        // At T=25 a 60cp deficit is ~e^-2.4: the best move dominates.
        #expect(WeakMovePicker.pick(candidates, temperatureCp: 25, random: 0.5) == 0)
    }

    @Test func highTemperatureSpreadsChoice() {
        // At T=225 the second move must be reachable well within [0,1).
        var picked = Set<Int>()
        for r in stride(from: 0.05, to: 1.0, by: 0.05) {
            picked.insert(WeakMovePicker.pick(candidates, temperatureCp: 225, random: r))
        }
        #expect(picked.contains(0) && picked.contains(1))
    }

    @Test func mateForAlwaysWinsAtLowTemperature() {
        let withMate = [
            CandidateMove(moveUCI: "d8h4", scoreCp: nil, scoreMate: 1),
            CandidateMove(moveUCI: "e2e4", scoreCp: 30, scoreMate: nil),
        ]
        #expect(WeakMovePicker.pick(withMate, temperatureCp: 150, random: 0.9) == 0)
    }

    @Test func parsesMultiPVRank() {
        let info = UCIEngine.parseInfo("info depth 8 multipv 3 score cp -55 pv a2a3 e7e5")
        #expect(info?.multipv == 3)
        #expect(info?.scoreCp == -55)
        #expect(info?.pv.first == "a2a3")
    }
}
