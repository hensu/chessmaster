// AnalysisKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveClassifierTests {
    @Test fun winPercentIsSymmetricAroundEqual() {
        assertTrue(abs(MoveClassifier.winPercent(0, true) - 50) < 0.001)
        assertTrue(abs(MoveClassifier.winPercent(0, false) - 50) < 0.001)
        val up = MoveClassifier.winPercent(200, true)
        val down = MoveClassifier.winPercent(-200, true)
        assertTrue(abs(up + down - 100) < 0.001)
        // +200 for white is the same win% as -200 seen from black.
        assertTrue(abs(up - MoveClassifier.winPercent(-200, false)) < 0.001)
        assertTrue(up > 60 && up < 80)
    }

    @Test fun thresholdsMatchLichess() {
        assertEquals(MoveClassification.good, MoveClassifier.classify(winPercentDrop = 5.0))
        assertEquals(MoveClassification.inaccuracy, MoveClassifier.classify(winPercentDrop = 12.0))
        assertEquals(MoveClassification.mistake, MoveClassifier.classify(winPercentDrop = 22.0))
        assertEquals(MoveClassification.blunder, MoveClassifier.classify(winPercentDrop = 35.0))
    }

    @Test fun accuracyCurve() {
        assertTrue(MoveClassifier.accuracy(winPercentDrop = 0.0) > 99.99)
        assertTrue(MoveClassifier.accuracy(winPercentDrop = 50.0) < 15)
        assertTrue(MoveClassifier.accuracy(winPercentDrop = 10.0) > MoveClassifier.accuracy(winPercentDrop = 20.0))
    }
}
