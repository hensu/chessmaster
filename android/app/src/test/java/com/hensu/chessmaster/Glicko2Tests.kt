// RatingKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Glicko2Tests {
    /**
     * The worked example from Glickman's Glicko-2 paper: a 1500/200/0.06
     * player (tau = 0.5) beats a 1400/30 opponent, then loses to 1550/100
     * and 1700/300. Expected: r' = 1464.06, RD' = 151.52, sigma' = 0.05999.
     */
    @Test fun glickmanPaperExample() {
        val player = Glicko2Rating(rating = 1500.0, deviation = 200.0, volatility = 0.06)
        val results = listOf(
            Glicko2Result(opponent = Glicko2Rating(rating = 1400.0, deviation = 30.0), score = 1.0),
            Glicko2Result(opponent = Glicko2Rating(rating = 1550.0, deviation = 100.0), score = 0.0),
            Glicko2Result(opponent = Glicko2Rating(rating = 1700.0, deviation = 300.0), score = 0.0),
        )
        val updated = Glicko2Calculator.update(player = player, results = results, tau = 0.5)
        assertTrue(abs(updated.rating - 1464.06) < 0.01)
        assertTrue(abs(updated.deviation - 151.52) < 0.01)
        assertTrue(abs(updated.volatility - 0.05999) < 0.0001)
    }

    @Test fun winAgainstStrongerOpponentGainsMore() {
        val player = Glicko2Rating()
        val vsWeak = Glicko2Calculator.update(
            player = player,
            results = listOf(Glicko2Result(Glicko2Rating(rating = 1200.0, deviation = 60.0), score = 1.0)),
        )
        val vsStrong = Glicko2Calculator.update(
            player = player,
            results = listOf(Glicko2Result(Glicko2Rating(rating = 1800.0, deviation = 60.0), score = 1.0)),
        )
        assertTrue(vsStrong.rating > vsWeak.rating)
        assertTrue(vsWeak.rating > player.rating)
    }

    @Test fun lossDropsRatingAndShrinksDeviation() {
        val player = Glicko2Rating(rating = 1500.0, deviation = 350.0, volatility = 0.06)
        val updated = Glicko2Calculator.update(
            player = player,
            results = listOf(Glicko2Result(Glicko2Rating(rating = 1500.0, deviation = 60.0), score = 0.0)),
        )
        assertTrue(updated.rating < 1500)
        assertTrue(updated.deviation < 350)
    }

    @Test fun drawAgainstEqualBarelyMoves() {
        val player = Glicko2Rating(rating = 1500.0, deviation = 100.0, volatility = 0.06)
        val updated = Glicko2Calculator.update(
            player = player,
            results = listOf(Glicko2Result(Glicko2Rating(rating = 1500.0, deviation = 100.0), score = 0.5)),
        )
        assertTrue(abs(updated.rating - 1500) < 1)
    }

    @Test fun emptyPeriodOnlyGrowsDeviation() {
        val player = Glicko2Rating(rating = 1600.0, deviation = 100.0, volatility = 0.06)
        val updated = Glicko2Calculator.update(player = player, results = emptyList())
        assertEquals(1600.0, updated.rating, 0.0)
        assertTrue(updated.deviation > 100)
        assertEquals(0.06, updated.volatility, 0.0)
    }
}
