// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Wordle-style daily puzzle: everyone gets the same puzzle on the same
 * (local) day, numbered from launch day. Solving it builds a streak and
 * produces a shareable result. Device-local, like LearnProgress.
 */
object DailyPuzzle {
    /** Daily #1. Puzzles are picked by day number, so this must never change. */
    private val epoch: LocalDate = LocalDate.of(2026, 11, 1)

    private const val streakKey = "daily.streak"
    private const val lastDayKey = "daily.lastSolvedDay"
    private const val resultsKey = "daily.results"   // "day" → attempts (negative = used a hint)

    /** Today's number (#1 on the epoch day). */
    fun number(on: Instant = Instant.now()): Int {
        val day = on.atZone(ZoneId.systemDefault()).toLocalDate()
        val days = ChronoUnit.DAYS.between(epoch, day).toInt()
        return max(1, days + 1)
    }

    /**
     * Mid-difficulty pool so a daily is satisfying for most players.
     * Sorted by id in plain code-unit order (the ids are ASCII), which is what Swift's `<`
     * gives, so both platforms pick the same puzzle.
     */
    private val pool: List<Puzzle> by lazy {
        LearnContent.puzzles
            .filter { it.rating in 800..1700 }
            .sortedWith { a, b -> a.id.compareTo(b.id) }
    }

    fun puzzle(number: Int = number()): Puzzle? {
        if (pool.isEmpty()) return null
        // Stride by a prime so consecutive days hop across themes/ratings.
        return pool[(number * 7919) % pool.size]
    }

    // MARK: - Results and streak

    data class Result(val attempts: Int, val usedHint: Boolean)

    fun result(number: Int = number()): Result? {
        val results = Defaults.standard.intMap(resultsKey)
        val value = results[number.toString()] ?: return null
        return Result(attempts = abs(value), usedHint = value < 0)
    }

    /**
     * Records today's first solve; later replays don't change it.
     * Returns the streak after recording.
     */
    fun recordSolve(attempts: Int, usedHint: Boolean, number: Int = number()): Int {
        val defaults = Defaults.standard
        val results = defaults.intMap(resultsKey).toMutableMap()
        if (results[number.toString()] != null) return streak(today = number)
        results[number.toString()] = if (usedHint) -attempts else attempts
        defaults.setIntMap(resultsKey, results)

        val last = defaults.integer(lastDayKey)
        val previous = defaults.integer(streakKey)
        val streak = if (last == number - 1) previous + 1 else 1
        defaults.set(streakKey, streak)
        defaults.set(lastDayKey, number)
        return streak
    }

    /** Current streak: alive if today's or yesterday's daily was solved. */
    fun streak(today: Int = number()): Int {
        val defaults = Defaults.standard
        val last = defaults.integer(lastDayKey)
        return if (last >= today - 1) defaults.integer(streakKey) else 0
    }

    /** "Chess AI Daily #12 / 🟥🟩 / 🔥 5-day streak / link". */
    fun shareText(number: Int = number()): String? {
        val result = result(number) ?: return null
        val squares = "🟥".repeat(max(0, result.attempts - 1)) + "🟩"
        val hint = if (result.usedHint) " 💡" else ""
        val tries = if (result.attempts == 1) "first try" else "${result.attempts} tries"
        val lines = mutableListOf("Chess AI Daily #$number", "$squares$hint ($tries)")
        val streak = streak(today = number)
        if (streak > 1) lines.add("🔥 $streak-day streak")
        lines.add("https://getchessai.com/daily/")
        return lines.joinToString("\n")
    }
}
