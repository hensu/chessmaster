// EngineKit — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

/**
 * JNI face of the embedded Stockfish (app/src/main/cpp). The engine's UCI loop runs on its own
 * thread with the process stdin/stdout redirected onto pipes. One engine per process.
 */
object StockfishBridge {
    init { System.loadLibrary("chessai_engine") }

    /** Starts the engine thread. Returns 0 on success, -1 if already started. */
    external fun start(): Int
    /** Writes one UCI command (the newline is added). */
    external fun send(command: String)
    /** Blocks until the engine prints a line; null at end of stream. Call from one thread only. */
    external fun readLine(): String?
}
