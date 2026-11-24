// EngineKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed class UCIEngineError : Exception() {
    data object StartFailed : UCIEngineError()
    data object NotStarted : UCIEngineError()
    data class Timeout(val command: String) : UCIEngineError()
    data object NoBestMove : UCIEngineError()
}

/** One line of `info ...` output from a search, reduced to what the app uses. */
data class SearchInfo(
    var depth: Int? = null,
    /** Centipawns from the side to move's perspective. */
    var scoreCp: Int? = null,
    /** Mate in N (negative: getting mated). */
    var scoreMate: Int? = null,
    /** 1-based MultiPV rank (null when MultiPV is off). */
    var multipv: Int? = null,
    var pv: List<String> = emptyList(),
)

/** One engine candidate move from a MultiPV search. */
data class CandidateMove(val moveUCI: String, val scoreCp: Int?, val scoreMate: Int?) {
    /** Comparable value in centipawns; mates saturate the scale. */
    val value: Double
        get() {
            if (scoreMate != null) return if (scoreMate > 0) 30_000.0 else -30_000.0
            return (scoreCp ?: 0).toDouble()
        }
}

data class SearchResult(
    val bestMoveUCI: String,
    /** Last info line seen before bestmove (deepest completed search). */
    val info: SearchInfo,
)

/**
 * Serialized access to the single embedded Stockfish instance.
 * The process-wide stdio redirection in the bridge means there can only
 * ever be one engine; this class is that engine's owner.
 */
class UCIEngine private constructor() {
    @Volatile private var started = false
    private var currentOptions = mutableMapOf<String, String>()

    // The engine has a single output mailbox and shared option/position
    // state, so a whole transaction (options → position → go → bestmove)
    // must run without another transaction interleaving. This async lock
    // serializes transactions, which is what lets background analysis share
    // the engine safely with live opponent play.
    private val lock = Mutex()

    /**
     * Runs one transaction under the lock, off the main thread. Once it has the lock a
     * transaction always runs to its end, even if the caller is cancelled (as on iOS): a search
     * abandoned halfway would leave its `bestmove` in the mailbox for the next transaction.
     */
    private suspend fun <T> withLock(body: suspend () -> T): T =
        lock.withLock { withContext(NonCancellable + Dispatchers.Default) { body() } }

    // Line mailbox: one dedicated reader thread pushes engine output here;
    // `nextLine` consumers pop (or suspend until a line arrives or the deadline passes).
    private val pendingLines = Channel<String>(Channel.UNLIMITED)
    private var readerThread: Thread? = null
    private val sendLock = Any()

    // MARK: - Lifecycle

    /**
     * Starts the engine thread and completes the UCI handshake.
     * Safe to call repeatedly.
     */
    suspend fun startIfNeeded(evalFileBig: String, evalFileSmall: String) {
        if (started) return
        // Android: the nets ship as APK assets and Stockfish opens them by path, so the app's
        // own nets are copied out to files first (once; a no-op afterwards).
        if (evalFileBig == StockfishNets.big || evalFileSmall == StockfishNets.small) StockfishNets.prepare()
        withLock {
            if (started) return@withLock   // re-check: a concurrent caller may have started it
            if (StockfishBridge.start() != 0) throw UCIEngineError.StartFailed
            readerThread = thread(name = "stockfish-reader", isDaemon = true) {
                try {
                    while (true) {
                        val line = StockfishBridge.readLine() ?: break
                        pendingLines.trySend(line)
                    }
                } catch (_: Throwable) {
                }
                pendingLines.close()
            }
            started = true

            send("uci")
            waitFor(timeoutMs = 5000, command = "uci") { it == "uciok" }
            send("setoption name EvalFile value $evalFileBig")
            send("setoption name EvalFileSmall value $evalFileSmall")
            send("setoption name Threads value 2")
            send("setoption name Hash value 32")
            syncLocked()
        }
    }

    /** Blocks until the engine has processed everything sent so far. */
    suspend fun sync() {
        withLock { syncLocked() }
    }

    /** `sync()` core; the caller must already hold the lock. */
    private suspend fun syncLocked() {
        send("isready")
        waitFor(timeoutMs = 10000, command = "isready") { it == "readyok" }
    }

    suspend fun newGame() {
        ensureStarted()
        withLock {
            send("ucinewgame")
            syncLocked()
        }
    }

    /** Interrupts a running search (e.g. when backgrounding). */
    fun stopSearch() {
        if (!started) return
        send("stop")
    }

    // MARK: - Options

    suspend fun apply(options: Map<String, String>) {
        ensureStarted()
        withLock {
            if (applyOptionsLocked(options)) syncLocked()
        }
    }

    /**
     * Sends option diffs synchronously (no `isready`). The engine processes
     * `setoption` before any following `position`/`go` sent in the same
     * critical section, so folding options into a search keeps the whole
     * transaction atomic. Returns whether anything changed. Lock required.
     */
    private fun applyOptionsLocked(options: Map<String, String>): Boolean {
        var changed = false
        for ((name, value) in options.toSortedMap()) {
            if (currentOptions[name] == value) continue
            send("setoption name $name value $value")
            currentOptions[name] = value
            changed = true
        }
        return changed
    }

    // MARK: - Search

    /**
     * Search a position given by FEN. `movetimeMs` bounds wall time;
     * `depth` optionally caps depth (whichever stops first).
     * `options`: engine options to apply atomically for this
     * search (e.g. Skill Level). `MultiPV` is forced to 1 so the returned
     * score is always the principal line, regardless of prior state.
     */
    suspend fun search(
        fen: String, movetimeMs: Int, depth: Int? = null, options: Map<String, String> = emptyMap(),
    ): SearchResult {
        ensureStarted()
        return withLock {
            val opts = options.toMutableMap()
            opts["MultiPV"] = "1"
            applyOptionsLocked(opts)
            drainPendingLines()   // drop stale output from any prior transaction
            send("position fen $fen")
            var go = "go movetime $movetimeMs"
            if (depth != null) go += " depth $depth"
            send(go)

            var lastInfo = SearchInfo()
            val line = waitFor(timeoutMs = movetimeMs + 10000, command = go) { line ->
                if (line.startsWith("info ")) parseInfo(line)?.let { lastInfo = it }
                line.startsWith("bestmove")
            }
            val parts = line.split(" ").filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[1] == "(none)") throw UCIEngineError.NoBestMove
            SearchResult(bestMoveUCI = parts[1], info = lastInfo)
        }
    }

    /**
     * MultiPV search: the engine's top candidate moves with evaluations,
     * deepest iteration wins. Each search sets its own MultiPV, so no reset
     * is needed — a following single-line `search` forces MultiPV back to 1.
     */
    suspend fun searchCandidates(
        fen: String, movetimeMs: Int, depth: Int? = null, count: Int,
        options: Map<String, String> = emptyMap(),
    ): List<CandidateMove> {
        ensureStarted()
        return withLock {
            val opts = options.toMutableMap()
            opts["MultiPV"] = "$count"
            applyOptionsLocked(opts)
            drainPendingLines()   // drop stale output from any prior transaction
            send("position fen $fen")
            var go = "go movetime $movetimeMs"
            if (depth != null) go += " depth $depth"
            send(go)

            val byRank = mutableMapOf<Int, SearchInfo>()
            waitFor(timeoutMs = movetimeMs + 10000, command = go) { line ->
                if (line.startsWith("info ")) {
                    val parsed = parseInfo(line)
                    if (parsed != null && parsed.pv.isNotEmpty()) byRank[parsed.multipv ?: 1] = parsed
                }
                line.startsWith("bestmove")
            }
            byRank.keys.sorted().mapNotNull { rank ->
                val info = byRank[rank] ?: return@mapNotNull null
                val move = info.pv.firstOrNull() ?: return@mapNotNull null
                CandidateMove(moveUCI = move, scoreCp = info.scoreCp, scoreMate = info.scoreMate)
            }
        }
    }

    // MARK: - Plumbing

    private fun ensureStarted() {
        if (!started) throw UCIEngineError.NotStarted
    }

    private fun send(command: String) {
        synchronized(sendLock) { StockfishBridge.send(command) }
    }

    private fun drainPendingLines() {
        while (pendingLines.tryReceive().isSuccess) { /* discard */ }
    }

    /**
     * Reads lines (feeding each to `handle`) until it returns true;
     * returns the matching line. Throws on timeout.
     */
    private suspend fun waitFor(timeoutMs: Int, command: String, handle: (String) -> Boolean): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val line = nextLine(deadline) ?: break
            if (handle(line)) return line
        }
        throw UCIEngineError.Timeout(command)
    }

    /** The next output line, or null at the deadline (or at end of the engine's output). */
    private suspend fun nextLine(deadline: Long): String? {
        pendingLines.tryReceive().getOrNull()?.let { return it }
        val leftMs = (deadline - System.nanoTime()) / 1_000_000L
        if (leftMs <= 0) return null
        return withTimeoutOrNull(leftMs) { pendingLines.receiveCatching().getOrNull() }
    }

    companion object {
        val shared: UCIEngine by lazy { UCIEngine() }

        fun parseInfo(line: String): SearchInfo? {
            val info = SearchInfo()
            val tokens = line.split(" ").filter { it.isNotEmpty() }
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token == "depth" && i + 1 < tokens.size -> {
                        info.depth = tokens[i + 1].toIntOrNull(); i += 2
                    }
                    token == "multipv" && i + 1 < tokens.size -> {
                        info.multipv = tokens[i + 1].toIntOrNull(); i += 2
                    }
                    token == "score" && i + 2 < tokens.size -> {
                        if (tokens[i + 1] == "cp") info.scoreCp = tokens[i + 2].toIntOrNull()
                        if (tokens[i + 1] == "mate") info.scoreMate = tokens[i + 2].toIntOrNull()
                        i += 3
                    }
                    token == "pv" -> {
                        info.pv = tokens.subList(i + 1, tokens.size).toList()
                        i = tokens.size
                    }
                    else -> i += 1
                }
            }
            return if (info.depth == null && info.scoreCp == null && info.scoreMate == null && info.pv.isEmpty()) null
            else info
        }
    }
}
