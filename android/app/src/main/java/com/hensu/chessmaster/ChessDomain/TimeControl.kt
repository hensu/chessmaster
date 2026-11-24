// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A chess time control: initial time plus a Fischer increment per move. */
data class TimeControl(var initial: Duration, var increment: Duration) {
    constructor(minutes: Int, incrementSeconds: Int) : this((minutes * 60).seconds, incrementSeconds.seconds)

    enum class Category(val rawValue: String) {
        bullet("bullet"), blitz("blitz"), rapid("rapid"), classical("classical");
        companion object { fun fromRaw(raw: String): Category? = entries.firstOrNull { it.rawValue == raw } }
    }

    /**
     * Lichess speed categories, by estimated game duration
     * (initial + 40 × increment).
     */
    val category: Category
        get() {
            val estimate = initial.inWholeSeconds + 40 * increment.inWholeSeconds
            return when {
                estimate < 180 -> Category.bullet
                estimate < 480 -> Category.blitz
                estimate < 1500 -> Category.rapid
                else -> Category.classical
            }
        }

    /** "3+2" style label, minutes+seconds like lichess. */
    val label: String
        get() {
            val minutes = initial.inWholeSeconds / 60
            val seconds = initial.inWholeSeconds % 60
            val base = if (seconds == 0L) "$minutes" else "$minutes½"
            return "$base+${increment.inWholeSeconds}"
        }

    /** PGN TimeControl tag value, e.g. "180+2". */
    val pgnTag: String get() = "${initial.inWholeSeconds}+${increment.inWholeSeconds}"

    companion object {
        val presets: List<TimeControl> = listOf(
            TimeControl(1, 0),
            TimeControl(2, 1),
            TimeControl(3, 0),
            TimeControl(3, 2),
            TimeControl(5, 0),
            TimeControl(5, 3),
            TimeControl(10, 0),
            TimeControl(10, 5),
            TimeControl(15, 10),
            TimeControl(30, 0),
            TimeControl(30, 20),
        )
    }
}
