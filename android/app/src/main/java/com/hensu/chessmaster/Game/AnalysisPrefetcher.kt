// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Warms the engine eval cache *while a game is being played*, so the free
 * post-game Stockfish analysis is mostly (often fully) precomputed and opens
 * near-instantly instead of grinding through every position at the end.
 *
 * Positions are evaluated in the background during the user's thinking time
 * and the prefetcher pauses whenever the opponent needs the engine, so it
 * never delays a move. Everything it computes lands in `EngineEvalCache`,
 * which `GameAnalyzer` reads first — a miss simply recomputes, so this can
 * only ever make analysis faster.
 */
class AnalysisPrefetcher private constructor(
    /** Match the post-game budget so cached evals satisfy that pass exactly. */
    private val movetimeMs: Int,
    private val analyzer: GameAnalyzer,
) {
    private val scope = MainScope()
    private val queue = ArrayDeque<String>()
    private val enqueued = mutableSetOf<String>()
    private var worker: Job? = null
    /** Start paused: the caller resumes once it's the user's turn to think. */
    private var paused = true

    companion object {
        /** `AnalysisPrefetcher(movetimeMs:)`: null when the NNUE nets are missing. */
        fun of(movetimeMs: Int = 250): AnalysisPrefetcher? {
            val big = StockfishNets.big ?: return null
            val small = StockfishNets.small ?: return null
            return AnalysisPrefetcher(movetimeMs, GameAnalyzer(evalFileBig = big, evalFileSmall = small))
        }
    }

    /** New game: drop any pending work and clear the (bounded) shared cache. */
    fun reset() {
        worker?.cancel()
        worker = null
        queue.clear()
        enqueued.clear()
        EngineEvalCache.shared.clear()
    }

    /** Queue a position reached during the game for background evaluation. */
    fun enqueue(fen: String) {
        if (!enqueued.add(fen)) return
        queue.addLast(fen)
        pump()
    }

    /** The user is thinking — the engine is idle, so burn cycles on analysis. */
    fun resume() {
        paused = false
        pump()
    }

    /**
     * The opponent is about to think — stop starting new evals so we don't
     * compete for the engine (the in-flight one, if any, finishes on its own).
     */
    fun pause() {
        paused = true
    }

    /** Game over: stop prefetching. The cache stays warm for the post-game pass. */
    fun stop() {
        worker?.cancel()
        worker = null
    }

    private fun pump() {
        if (worker != null || paused || queue.isEmpty()) return
        var job: Job? = null
        job = scope.launch {
            while (!paused && isActive && queue.isNotEmpty()) {
                val fen = queue.removeFirst()
                analyzer.warm(fen = fen, movetimeMs = movetimeMs)
            }
            // A cancelled worker was already replaced or cleared by stop()/reset().
            if (worker === job) worker = null
        }
        worker = job
    }
}
