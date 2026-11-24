// ChessDomain — Chessmaster (Android)
// GPL-3.0-or-later

package com.hensu.chessmaster

import kotlin.time.Duration

/**
 * Inserts lichess-style `{[%clk h:mm:ss]}` comments after each SAN move,
 * using the session's own move history as the source of truth.
 */
object PGNClockAnnotator {
    fun annotate(pgn: String, history: List<PlayedMove>): String {
        if (history.none { it.clockRemaining != null }) return pgn

        // Split headers from movetext (headers end at the first blank line).
        val parts = pgn.split("\n\n")
        val movetext = parts.lastOrNull() ?: return pgn
        val headers = parts.dropLast(1).joinToString("\n\n")

        var plyIndex = 0
        val out = mutableListOf<String>()
        for (token in movetext.split(" ").filter { it.isNotEmpty() }) {
            out.add(token)
            val isMoveNumber = token.endsWith(".") || token.contains("...")
            val isResult = token in listOf("1-0", "0-1", "1/2-1/2", "*")
            if (!isMoveNumber && !isResult && !token.startsWith("{") && !token.startsWith("$")) {
                val clock = history.getOrNull(plyIndex)?.clockRemaining
                if (clock != null) out.add("{[%clk ${format(clock)}]}")
                plyIndex += 1
            }
        }
        val annotated = out.joinToString(" ")
        return if (headers.isEmpty()) annotated else headers + "\n\n" + annotated
    }

    private fun format(duration: Duration): String {
        val total = maxOf(0, duration.inWholeSeconds)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    }
}
