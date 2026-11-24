// EngineKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoParsingTests {
    @Test fun parsesDepthScorePv() {
        val line = "info depth 18 seldepth 24 multipv 1 score cp 35 nodes 12345 nps 99999 time 123 pv e2e4 e7e5 g1f3"
        val info = UCIEngine.parseInfo(line)
        assertEquals(18, info?.depth)
        assertEquals(35, info?.scoreCp)
        assertEquals("e2e4", info?.pv?.firstOrNull())
        assertEquals(3, info?.pv?.size)
    }

    @Test fun parsesMateScore() {
        val info = UCIEngine.parseInfo("info depth 12 score mate 3 pv d1h5")
        assertEquals(3, info?.scoreMate)
        assertNull(info?.scoreCp)
    }
}

class StrengthLevelTests {
    @Test fun ladderIsComplete() {
        assertEquals(10, StrengthLevel.all.size)
        assertEquals((1..10).toList(), StrengthLevel.all.map { it.level })
        // Rating estimates must rise monotonically.
        val ratings = StrengthLevel.all.map { it.ratingEstimate }
        assertEquals(ratings.sorted(), ratings)
        assertEquals(1, StrengthLevel.level(0).level)
        assertEquals(10, StrengthLevel.level(99).level)
    }

    @Test fun lowLevelsUseSkillHighLevelsUseElo() {
        assertEquals("0", StrengthLevel.level(1).uciOptions["Skill Level"])
        assertEquals("true", StrengthLevel.level(7).uciOptions["UCI_LimitStrength"])
        assertEquals("1800", StrengthLevel.level(7).uciOptions["UCI_Elo"])
        assertEquals("false", StrengthLevel.level(10).uciOptions["UCI_LimitStrength"])
    }
}

class StrengthLadderTests {
    /**
     * Low rungs must blunder deliberately (Stockfish's natural floor is
     * far above "Newcomer") and the handicap must fade monotonically.
     */
    @Test fun blunderRatesDescendAndVanish() {
        assertEquals(0.45, StrengthLevel.level(1).blunderProbability, 0.0)
        assertEquals(0.30, StrengthLevel.level(2).blunderProbability, 0.0)
        for (n in 1 until 10) {
            assertTrue(StrengthLevel.level(n).blunderProbability >= StrengthLevel.level(n + 1).blunderProbability)
        }
        for (n in 6..10) {
            assertEquals(0.0, StrengthLevel.level(n).blunderProbability, 0.0)
        }
    }

    @Test fun ratingEstimatesAscend() {
        for (n in 1 until 10) {
            assertTrue(StrengthLevel.level(n).ratingEstimate < StrengthLevel.level(n + 1).ratingEstimate)
        }
    }
}

class WeakMovePickerTests {
    private val candidates = listOf(
        CandidateMove(moveUCI = "e2e4", scoreCp = 40, scoreMate = null),
        CandidateMove(moveUCI = "d2d4", scoreCp = -20, scoreMate = null),
        CandidateMove(moveUCI = "a2a3", scoreCp = -180, scoreMate = null),
    )

    @Test fun zeroTemperaturePicksBest() {
        assertEquals(0, WeakMovePicker.pick(candidates, temperatureCp = 0.0, random = 0.99))
    }

    @Test fun lowTemperatureStronglyPrefersBest() {
        // At T=25 a 60cp deficit is ~e^-2.4: the best move dominates.
        assertEquals(0, WeakMovePicker.pick(candidates, temperatureCp = 25.0, random = 0.5))
    }

    @Test fun highTemperatureSpreadsChoice() {
        // At T=225 the second move must be reachable well within [0,1).
        val picked = mutableSetOf<Int>()
        var r = 0.05
        while (r < 1.0) {
            picked.add(WeakMovePicker.pick(candidates, temperatureCp = 225.0, random = r))
            r += 0.05
        }
        assertTrue(picked.contains(0) && picked.contains(1))
    }

    @Test fun mateForAlwaysWinsAtLowTemperature() {
        val withMate = listOf(
            CandidateMove(moveUCI = "d8h4", scoreCp = null, scoreMate = 1),
            CandidateMove(moveUCI = "e2e4", scoreCp = 30, scoreMate = null),
        )
        assertEquals(0, WeakMovePicker.pick(withMate, temperatureCp = 150.0, random = 0.9))
    }

    @Test fun parsesMultiPVRank() {
        val info = UCIEngine.parseInfo("info depth 8 multipv 3 score cp -55 pv a2a3 e7e5")
        assertEquals(3, info?.multipv)
        assertEquals(-55, info?.scoreCp)
        assertEquals("a2a3", info?.pv?.firstOrNull())
    }
}
