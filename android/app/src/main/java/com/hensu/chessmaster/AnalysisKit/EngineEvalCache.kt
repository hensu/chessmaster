// AnalysisKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

/** A single position's engine evaluation, white-POV. */
data class PositionEval(
    /** White-POV centipawns (mates mapped to ±10_000). */
    val cpWhite: Int,
    /** Mate-in-N after the move (white POV sign), if forced. */
    val mate: Int?,
    val bestMoveUCI: String?,
    val pv: List<String>,
    /** Engine budget spent — a cheaper eval must not overwrite a richer one. */
    val movetimeMs: Int,
)

/**
 * Process-wide FEN → eval cache. Warmed in the background while a game is
 * played (see AnalysisPrefetcher) so the post-game analysis is mostly — often
 * entirely — precomputed and opens near-instantly. A miss just falls back to
 * a live engine search, so the cache only ever makes things faster.
 *
 * FENs come from the same ChessKit serializer live (`GameSession.currentFEN`)
 * and post-game (`GameReplay.Ply.fenAfter`), so keys match exactly.
 *
 * Thread-safe (the Swift type is an actor); every call is a short synchronized map operation.
 */
class EngineEvalCache {
    private val store = HashMap<String, PositionEval>()

    /**
     * A cached eval for `fen`, but only if it was computed with at least the
     * requested budget (a 250ms eval satisfies a 120ms request, not vice versa).
     */
    fun value(fen: String, atLeastMovetimeMs: Int): PositionEval? = synchronized(store) {
        val eval = store[fen]
        if (eval != null && eval.movetimeMs >= atLeastMovetimeMs) eval else null
    }

    fun set(eval: PositionEval, fen: String) {
        synchronized(store) {
            val existing = store[fen]
            if (existing != null && existing.movetimeMs >= eval.movetimeMs) return
            store[fen] = eval
        }
    }

    /** Cleared at the start of each new game to bound memory. */
    fun clear() {
        synchronized(store) { store.clear() }
    }

    val count: Int get() = synchronized(store) { store.size }

    companion object {
        val shared: EngineEvalCache by lazy { EngineEvalCache() }
    }
}
