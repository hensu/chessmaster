// RatingKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Testing
@testable import RatingKit

@Suite struct Glicko2Tests {
    /// The worked example from Glickman's Glicko-2 paper: a 1500/200/0.06
    /// player (tau = 0.5) beats a 1400/30 opponent, then loses to 1550/100
    /// and 1700/300. Expected: r' = 1464.06, RD' = 151.52, sigma' = 0.05999.
    @Test func glickmanPaperExample() {
        let player = Glicko2Rating(rating: 1500, deviation: 200, volatility: 0.06)
        let results = [
            Glicko2Result(opponent: .init(rating: 1400, deviation: 30), score: 1),
            Glicko2Result(opponent: .init(rating: 1550, deviation: 100), score: 0),
            Glicko2Result(opponent: .init(rating: 1700, deviation: 300), score: 0),
        ]
        let updated = Glicko2Calculator.update(player: player, results: results, tau: 0.5)
        #expect(abs(updated.rating - 1464.06) < 0.01)
        #expect(abs(updated.deviation - 151.52) < 0.01)
        #expect(abs(updated.volatility - 0.05999) < 0.0001)
    }

    @Test func winAgainstStrongerOpponentGainsMore() {
        let player = Glicko2Rating()
        let vsWeak = Glicko2Calculator.update(
            player: player,
            results: [.init(opponent: .init(rating: 1200, deviation: 60), score: 1)])
        let vsStrong = Glicko2Calculator.update(
            player: player,
            results: [.init(opponent: .init(rating: 1800, deviation: 60), score: 1)])
        #expect(vsStrong.rating > vsWeak.rating)
        #expect(vsWeak.rating > player.rating)
    }

    @Test func lossDropsRatingAndShrinksDeviation() {
        let player = Glicko2Rating(rating: 1500, deviation: 350, volatility: 0.06)
        let updated = Glicko2Calculator.update(
            player: player,
            results: [.init(opponent: .init(rating: 1500, deviation: 60), score: 0)])
        #expect(updated.rating < 1500)
        #expect(updated.deviation < 350)
    }

    @Test func drawAgainstEqualBarelyMoves() {
        let player = Glicko2Rating(rating: 1500, deviation: 100, volatility: 0.06)
        let updated = Glicko2Calculator.update(
            player: player,
            results: [.init(opponent: .init(rating: 1500, deviation: 100), score: 0.5)])
        #expect(abs(updated.rating - 1500) < 1)
    }

    @Test func emptyPeriodOnlyGrowsDeviation() {
        let player = Glicko2Rating(rating: 1600, deviation: 100, volatility: 0.06)
        let updated = Glicko2Calculator.update(player: player, results: [])
        #expect(updated.rating == 1600)
        #expect(updated.deviation > 100)
        #expect(updated.volatility == 0.06)
    }
}
