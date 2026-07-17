// ChessDomain — Chessmaster
// GPL-3.0-or-later

import Foundation

/// Inserts lichess-style `{[%clk h:mm:ss]}` comments after each SAN move.
/// ChessKit's PGN writer has no clock-comment support, so this post-processes
/// its movetext using the session's own move history as the source of truth.
public enum PGNClockAnnotator {
    public static func annotate(pgn: String, history: [PlayedMove]) -> String {
        guard history.contains(where: { $0.clockRemaining != nil }) else { return pgn }

        // Split headers from movetext (headers end at the first blank line).
        let parts = pgn.components(separatedBy: "\n\n")
        guard let movetext = parts.last, parts.count >= 1 else { return pgn }
        let headers = parts.dropLast().joined(separator: "\n\n")

        var tokens = movetext.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        var plyIndex = 0
        var out: [String] = []
        for token in tokens {
            out.append(token)
            let isMoveNumber = token.hasSuffix(".") || token.contains("...")
            let isResult = ["1-0", "0-1", "1/2-1/2", "*"].contains(token)
            if !isMoveNumber && !isResult && !token.hasPrefix("{") && !token.hasPrefix("$") {
                if plyIndex < history.count, let clock = history[plyIndex].clockRemaining {
                    out.append("{[%clk \(format(clock))]}")
                }
                plyIndex += 1
            }
        }
        tokens = out
        let annotated = tokens.joined(separator: " ")
        return headers.isEmpty ? annotated : headers + "\n\n" + annotated
    }

    private static func format(_ duration: Duration) -> String {
        let total = max(0, duration.components.seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return String(format: "%d:%02d:%02d", h, m, s)
    }
}
