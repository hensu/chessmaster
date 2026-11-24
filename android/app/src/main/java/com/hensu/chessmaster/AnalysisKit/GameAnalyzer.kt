// AnalysisKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class PlyEval(
    val ply: Int,
    val san: String,
    val uci: String,
    val moverIsWhite: Boolean,
    /** White-POV centipawns after the move (mates mapped to ±10_000). */
    val cpAfter: Int,
    /** Mate-in-N after the move (white POV sign), if forced. */
    val mateAfter: Int? = null,
    /** Win probability for white after the move (0-100). */
    val winPercentWhite: Double,
    val classification: MoveClassification,
    /**
     * Engine's preferred move in the position *before* this move
     * (what should have been played), when it differs meaningfully.
     */
    val bestMoveUCI: String? = null,
    /**
     * The engine's line after its preferred move (UCI, ≤8 plies) — the
     * concrete refutation the coach can show. Only kept for flagged moves.
     */
    val bestLineUCI: List<String>? = null,
) {
    val id: Int get() = ply
}

@Serializable
data class GameAnalysis(
    val plies: List<PlyEval>,
    val accuracyWhite: Double,
    val accuracyBlack: Double,
    val depthProfile: String,   // e.g. "movetime 120ms" — provenance
) {
    val criticalPlies: List<PlyEval>
        get() = plies.filter {
            it.classification == MoveClassification.blunder || it.classification == MoveClassification.mistake
        }
}

/**
 * Post-game eval pass: walks the game, searches every position with the
 * shared engine, classifies each move lichess-style. Progress streams back
 * so the UI can annotate incrementally.
 */
class GameAnalyzer(
    private val engine: UCIEngine,
    private val evalFileBig: String,
    private val evalFileSmall: String,
) {
    /** `GameAnalyzer(evalFileBig:evalFileSmall:)`: the shared engine. */
    constructor(evalFileBig: String, evalFileSmall: String) : this(UCIEngine.shared, evalFileBig, evalFileSmall)

    sealed class AnalyzerError : Exception() {
        data object BadPGN : AnalyzerError()
        data object EngineUnavailable : AnalyzerError()
    }

    /**
     * Evaluate one position and store it in the shared cache. Used by the
     * in-game prefetcher to warm positions during the user's thinking time,
     * so the post-game `analyze` pass mostly hits the cache. Best-effort:
     * engine hiccups are swallowed (a miss just recomputes later).
     */
    suspend fun warm(fen: String, movetimeMs: Int) {
        try {
            engine.startIfNeeded(evalFileBig = evalFileBig, evalFileSmall = evalFileSmall)
            evaluate(fen = fen, movetimeMs = movetimeMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Analyze a full game.
     * - `pgn`: the game to analyze.
     * - `movetimeMs`: engine budget per position (free blunder-check: 120,
     *   premium deep: 500).
     * - `onProgress`: called after each ply with (done, total), on the caller's thread.
     */
    suspend fun analyze(
        pgn: String,
        movetimeMs: Int,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): GameAnalysis {
        val replay = GameReplay.of(pgn) ?: throw AnalyzerError.BadPGN

        engine.startIfNeeded(evalFileBig = evalFileBig, evalFileSmall = evalFileSmall)
        engine.newGame()

        // Eval of every position: index 0 = start, i = after ply i.
        val cpByPosition = mutableListOf<Int>()
        val mateByPosition = mutableListOf<Int?>()
        val bestMoveByPosition = mutableListOf<String?>()
        val pvByPosition = mutableListOf<List<String>>()

        val fens = listOf(replay.startFEN) + replay.plies.map { it.fenAfter }
        for ((i, fen) in fens.withIndex()) {
            // A "#" in the SAN that produced this position is authoritative:
            // no engine call needed (and a mated position has no bestmove).
            if (i > 0 && replay.plies[i - 1].san.endsWith("#")) {
                val moverWasWhite = replay.plies[i - 1].mover == Piece.Color.white
                cpByPosition.add(if (moverWasWhite) 10_000 else -10_000)
                mateByPosition.add(null)
                bestMoveByPosition.add(null)
                pvByPosition.add(emptyList())
            } else {
                val eval = evaluate(fen = fen, movetimeMs = movetimeMs)
                cpByPosition.add(eval.cp)
                mateByPosition.add(eval.mate)
                bestMoveByPosition.add(eval.best)
                pvByPosition.add(eval.pv)
            }
            onProgress?.invoke(i + 1, fens.size)
        }

        val plies = mutableListOf<PlyEval>()
        var accuracySumWhite = 0.0
        var accuracyCountWhite = 0.0
        var accuracySumBlack = 0.0
        var accuracyCountBlack = 0.0

        for (plyInfo in replay.plies) {
            val i = plyInfo.ply
            val moverIsWhite = plyInfo.mover == Piece.Color.white
            val before = MoveClassifier.winPercent(cpByPosition[i - 1], moverIsWhite)
            val after = MoveClassifier.winPercent(cpByPosition[i], moverIsWhite)
            val drop = maxOf(0.0, before - after)
            // Never flag the engine's own best move (a forced defense that
            // "loses less" is not a mistake), nor moves in decided positions.
            val playedBest = bestMoveByPosition[i - 1] == plyInfo.uci
            val classification =
                if (playedBest || MoveClassifier.isPositionDecided(moverWinPercentBefore = before)) MoveClassification.good
                else MoveClassifier.classify(winPercentDrop = drop)
            val accuracy = MoveClassifier.accuracy(winPercentDrop = drop)
            if (moverIsWhite) {
                accuracySumWhite += accuracy; accuracyCountWhite += 1
            } else {
                accuracySumBlack += accuracy; accuracyCountBlack += 1
            }

            val best = bestMoveByPosition[i - 1]
            plies.add(
                PlyEval(
                    ply = i,
                    san = plyInfo.san,
                    uci = plyInfo.uci,
                    moverIsWhite = moverIsWhite,
                    cpAfter = cpByPosition[i],
                    mateAfter = mateByPosition[i],
                    winPercentWhite = MoveClassifier.winPercent(cpByPosition[i], true),
                    classification = classification,
                    bestMoveUCI = if (playedBest) null else best,
                    bestLineUCI = if (playedBest || pvByPosition[i - 1].isEmpty()) null else pvByPosition[i - 1],
                )
            )
        }

        return GameAnalysis(
            plies = plies,
            accuracyWhite = if (accuracyCountWhite > 0) accuracySumWhite / accuracyCountWhite else 100.0,
            accuracyBlack = if (accuracyCountBlack > 0) accuracySumBlack / accuracyCountBlack else 100.0,
            depthProfile = "movetime ${movetimeMs}ms",
        )
    }

    /** (cp white-POV, mate white-POV, bestmove, pv) for a FEN. */
    private data class GameAnalyzerEval(val cp: Int, val mate: Int?, val best: String?, val pv: List<String>)

    /**
     * Returns (cp white-POV, mate white-POV, bestmove) for a FEN.
     * Terminal positions (no legal moves) are synthesized from the rules.
     */
    private suspend fun evaluate(fen: String, movetimeMs: Int): GameAnalyzerEval {
        // Warmed during play? Serve it — no engine call needed.
        EngineEvalCache.shared.value(fen = fen, atLeastMovetimeMs = movetimeMs)?.let { cached ->
            return GameAnalyzerEval(cached.cpWhite, cached.mate, cached.bestMoveUCI, cached.pv)
        }
        val sideToMoveIsWhite = fen.split(" ").filter { it.isNotEmpty() }.getOrNull(1) == "w"
        try {
            val result = engine.search(fen = fen, movetimeMs = movetimeMs, options = engineOptions)
            var cp: Int
            var mate: Int? = null
            val mateIn = result.info.scoreMate
            if (mateIn != null) {
                mate = if (sideToMoveIsWhite) mateIn else -mateIn
                cp = if (mateIn > 0) 10_000 else -10_000
                if (!sideToMoveIsWhite) cp = -cp
            } else {
                cp = result.info.scoreCp ?: 0
                if (!sideToMoveIsWhite) cp = -cp
            }
            val pv = result.info.pv.take(8)
            EngineEvalCache.shared.set(
                PositionEval(
                    cpWhite = cp, mate = mate, bestMoveUCI = result.bestMoveUCI,
                    pv = pv, movetimeMs = movetimeMs,
                ),
                fen = fen,
            )
            return GameAnalyzerEval(cp, mate, result.bestMoveUCI, pv)
        } catch (_: UCIEngineError.NoBestMove) {
            // Checkmate or stalemate on the board.
            val position = Position.of(fen) ?: return GameAnalyzerEval(0, null, null, emptyList())
            val board = Board(position)
            val anyMove = position.pieces
                .filter { it.color == position.sideToMove }
                .any { board.legalMoves(it.square).isNotEmpty() }
            if (anyMove) return GameAnalyzerEval(0, null, null, emptyList())
            // No legal moves: mate if in check, stalemate otherwise.
            if (board.state is Board.State.Checkmate) {
                return GameAnalyzerEval(if (sideToMoveIsWhite) -10_000 else 10_000, null, null, emptyList())
            }
            return GameAnalyzerEval(0, null, null, emptyList())
        }
    }

    companion object {
        /**
         * Full strength for analysis, regardless of the last game's level.
         * Folded into every search so it applies atomically even when the
         * opponent is sharing the engine at a reduced skill.
         */
        val engineOptions = mapOf(
            "Skill Level" to "20",
            "UCI_LimitStrength" to "false",
        )
    }
}
