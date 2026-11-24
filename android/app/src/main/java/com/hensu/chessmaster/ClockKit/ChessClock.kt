// ClockKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ClockSide {
    white, black;

    val opposite: ClockSide get() = if (this == white) black else white
}

/**
 * Drift-free two-sided chess clock with Fischer increment.
 *
 * Remaining time is never decremented by a timer: the running side's
 * remaining is always computed as `storedRemaining - (now - stampedAt)`,
 * so late timer wakeups (or backgrounding) can't lose or gain time.
 * UI ticks only trigger re-rendering.
 *
 * `restoredRemaining` overrides the starting time per side when
 * resuming a saved game. `scope` runs the flag check (the main thread in the app; tests pass
 * their own).
 */
class ChessClock(
    val initial: Duration,
    val increment: Duration,
    restoredRemaining: Map<ClockSide, Duration> = emptyMap(),
    private val scope: CoroutineScope = MainScope(),
) {
    var running: ClockSide? by mutableStateOf(null)
        private set
    var isStopped: Boolean by mutableStateOf(false)
        private set

    /** Called exactly once when a side runs out of time. */
    var onFlag: ((ClockSide) -> Unit)? = null

    private var stored: Map<ClockSide, Duration> by mutableStateOf(
        mapOf(
            ClockSide.white to (restoredRemaining[ClockSide.white] ?: initial),
            ClockSide.black to (restoredRemaining[ClockSide.black] ?: initial),
        )
    )
    private var stampedAt: Duration? by mutableStateOf(null)
    private var flagTask: Job? = null

    fun remaining(side: ClockSide): Duration {
        var value = stored.getValue(side)
        val stampedAt = stampedAt
        if (running == side && stampedAt != null) {
            value -= now() - stampedAt
        }
        return maxOf(Duration.ZERO, value)
    }

    /** Starts the clock for `side` (game start: white). */
    fun start(side: ClockSide) {
        if (isStopped || running != null) return
        running = side
        stampedAt = now()
        scheduleFlagCheck(side)
    }

    /**
     * The running side completed a move: bank its time, add the increment,
     * start the opponent.
     */
    fun press(side: ClockSide) {
        val stampedAt = stampedAt
        if (isStopped || running != side || stampedAt == null) return
        flagTask?.cancel()
        val spent = now() - stampedAt
        stored = stored + (side to (maxOf(Duration.ZERO, stored.getValue(side) - spent) + increment))
        running = side.opposite
        this.stampedAt = now()
        scheduleFlagCheck(side.opposite)
    }

    /**
     * Overwrites both sides' banked time with an authoritative reading
     * (friend games: the server owns the clocks). The running side has
     * already been thinking for `runningElapsed`.
     */
    fun sync(white: Duration, black: Duration, runningElapsed: Duration = Duration.ZERO) {
        if (isStopped) return
        flagTask?.cancel()
        stored = mapOf(ClockSide.white to white, ClockSide.black to black)
        val running = running
        if (running != null) {
            stampedAt = now() - runningElapsed
            scheduleFlagCheck(running)
        }
    }

    /** Permanently stops the clock (game over). */
    fun stop() {
        if (isStopped) return
        flagTask?.cancel()
        val running = running
        val stampedAt = stampedAt
        if (running != null && stampedAt != null) {
            stored = stored + (running to maxOf(Duration.ZERO, stored.getValue(running) - (now() - stampedAt)))
        }
        this.running = null
        this.stampedAt = null
        isStopped = true
    }

    private fun scheduleFlagCheck(side: ClockSide) {
        flagTask = scope.launch {
            // Re-verify against computed remaining (guards against
            // spurious wakeups and clock adjustments): a wakeup that comes
            // early just waits for what is left.
            while (true) {
                val left = remaining(side)
                if (left == Duration.ZERO) break
                delay(left)
                if (!isActive || running != side) return@launch
            }
            if (!isActive || running != side) return@launch
            stop()
            onFlag?.invoke(side)
        }
    }

    private companion object {
        /** Monotonic and counts time asleep, like ContinuousClock (plain JVM in unit tests). */
        private val deviceClock: Boolean = runCatching { SystemClock.elapsedRealtimeNanos() > 0 }.getOrDefault(false)

        fun now(): Duration =
            (if (deviceClock) SystemClock.elapsedRealtimeNanos() else System.nanoTime()).nanoseconds
    }
}
