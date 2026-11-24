// ClockKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock's flag check runs in the test's own (single-threaded) scope, as it does on main. */
class ChessClockTests {
    @Test fun timeRunsOnlyForRunningSide() = runBlocking {
        coroutineScope {
            val clock = ChessClock(10.seconds, Duration.ZERO, scope = this)
            clock.start(ClockSide.white)
            delay(120)
            assertTrue(clock.remaining(ClockSide.white) < 10.seconds)
            assertEquals(10.seconds, clock.remaining(ClockSide.black))
            clock.stop()
        }
    }

    @Test fun pressAddsIncrementAndSwitchesSides() = runBlocking {
        coroutineScope {
            val clock = ChessClock(10.seconds, 2.seconds, scope = this)
            clock.start(ClockSide.white)
            delay(100)
            clock.press(ClockSide.white)
            // White banked: 10s - ~0.1s + 2s increment ≈ 11.9s.
            assertTrue(clock.remaining(ClockSide.white) > 11.seconds)
            assertTrue(clock.remaining(ClockSide.white) < 12.seconds)
            assertEquals(ClockSide.black, clock.running)
            clock.stop()
        }
    }

    @Test fun pressByNonRunningSideIsIgnored() = runBlocking {
        coroutineScope {
            val clock = ChessClock(10.seconds, 2.seconds, scope = this)
            clock.start(ClockSide.white)
            clock.press(ClockSide.black)
            assertEquals(ClockSide.white, clock.running)
            assertEquals(10.seconds, clock.remaining(ClockSide.black))
            clock.stop()
        }
    }

    @Test fun flagFiresWhenTimeRunsOut() = runBlocking {
        coroutineScope {
            val clock = ChessClock(150.milliseconds, Duration.ZERO, scope = this)
            var flagged: ClockSide? = null
            clock.onFlag = { flagged = it }
            clock.start(ClockSide.white)
            delay(400)
            assertEquals(ClockSide.white, flagged)
            assertEquals(Duration.ZERO, clock.remaining(ClockSide.white))
            assertTrue(clock.isStopped)
        }
    }

    @Test fun stopFreezesTime() = runBlocking {
        coroutineScope {
            val clock = ChessClock(10.seconds, Duration.ZERO, scope = this)
            clock.start(ClockSide.white)
            delay(80)
            clock.stop()
            val frozen = clock.remaining(ClockSide.white)
            delay(80)
            assertEquals(frozen, clock.remaining(ClockSide.white))
            // A stopped clock cannot restart.
            clock.start(ClockSide.black)
            assertNull(clock.running)
        }
    }
}
