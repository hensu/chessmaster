// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** TEMPORARY smoke test of the engine and the rules layer; replaced by the port of ChessmasterApp.swift. */
object ChessmasterApp {
    fun launched() {}
    fun onOpenURL(intent: Intent?) {}

    @Composable
    fun Content() {
        var log by remember { mutableStateOf("starting…") }
        LaunchedEffect(Unit) {
            log = withContext(Dispatchers.IO) {
                val out = StringBuilder()
                runCatching {
                    val dir = java.io.File(App.context.filesDir, "nnue").apply { mkdirs() }
                    val nets = listOf("nn-c288c895ea92.nnue", "nn-37f18f62d772.nnue").map { name ->
                        java.io.File(dir, name).also { f ->
                            if (!f.exists()) App.context.assets.open("NNUE/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        }
                    }
                    out.appendLine("start=" + StockfishBridge.start())
                    StockfishBridge.send("uci")
                    while (true) { val l = StockfishBridge.readLine() ?: break; if (l == "uciok") break }
                    StockfishBridge.send("setoption name EvalFile value ${nets[0].path}")
                    StockfishBridge.send("setoption name EvalFileSmall value ${nets[1].path}")
                    StockfishBridge.send("position startpos")
                    val t0 = System.currentTimeMillis()
                    StockfishBridge.send("go movetime 1000")
                    var last = ""
                    while (true) { val l = StockfishBridge.readLine() ?: break; if (l.startsWith("info depth")) last = l; if (l.startsWith("bestmove")) { out.appendLine(last.take(120)); out.appendLine(l); break } }
                    out.appendLine("ms=" + (System.currentTimeMillis() - t0))
                    val s = GameSession(GameConfig())
                    val ok = s.replay(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1", "f6e4", "d2d4", "e5d4"))
                    out.appendLine("replay=$ok " + s.moveHistory.joinToString(" ") { it.san })
                    out.appendLine(s.pgn)
                    val r = GameReplay.of(s.pgn)
                    out.appendLine("plies=" + r?.plies?.size + " pieces=" + r?.boardPieces(10)?.size + " fen=" + r?.plies?.lastOrNull()?.fenAfter)
                }.onFailure { out.appendLine("ERR $it") }
                out.toString()
            }
            android.util.Log.i("ChessAISmoke", log)
        }
        Box(Modifier.fillMaxSize().padding(24.dp)) { BasicText(log) }
    }
}
