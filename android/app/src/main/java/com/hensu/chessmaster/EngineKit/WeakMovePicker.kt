// EngineKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.math.exp

/**
 * Human-like weak move selection: sample among the engine's candidate
 * moves, preferring slightly-worse-but-plausible ones over the coin flip
 * between brilliant and suicidal that uniform randomness produces.
 * Weight = exp(-(best − candidate) / temperature): a small temperature
 * nearly always picks the best move; a large one happily plays moves a
 * pawn or two worse — the way actual beginners do.
 */
object WeakMovePicker {
    /** Picks an index into `candidates`. `random` must be uniform in [0, 1). */
    fun pick(candidates: List<CandidateMove>, temperatureCp: Double, random: Double): Int {
        if (candidates.size <= 1 || temperatureCp <= 0) return 0
        val best = candidates.maxOfOrNull { it.value } ?: 0.0
        val weights = candidates.map { exp(-(best - it.value) / temperatureCp) }
        val total = weights.sum()
        if (total <= 0) return 0
        var cursor = random * total
        for ((index, weight) in weights.withIndex()) {
            cursor -= weight
            if (cursor <= 0) return index
        }
        return candidates.size - 1
    }
}
