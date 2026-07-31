// AnalysisKit — Chessmaster
// GPL-3.0-or-later

import Foundation

/// A single position's engine evaluation, white-POV.
public struct PositionEval: Sendable {
    /// White-POV centipawns (mates mapped to ±10_000).
    public let cpWhite: Int
    /// Mate-in-N after the move (white POV sign), if forced.
    public let mate: Int?
    public let bestMoveUCI: String?
    public let pv: [String]
    /// Engine budget spent — a cheaper eval must not overwrite a richer one.
    public let movetimeMs: Int

    public init(cpWhite: Int, mate: Int?, bestMoveUCI: String?, pv: [String], movetimeMs: Int) {
        self.cpWhite = cpWhite
        self.mate = mate
        self.bestMoveUCI = bestMoveUCI
        self.pv = pv
        self.movetimeMs = movetimeMs
    }
}

/// Process-wide FEN → eval cache. Warmed in the background while a game is
/// played (see AnalysisPrefetcher) so the post-game analysis is mostly — often
/// entirely — precomputed and opens near-instantly. A miss just falls back to
/// a live engine search, so the cache only ever makes things faster.
///
/// FENs come from the same ChessKit serializer live (`GameSession.currentFEN`)
/// and post-game (`GameReplay.Ply.fenAfter`), so keys match exactly.
public actor EngineEvalCache {
    public static let shared = EngineEvalCache()

    private var store: [String: PositionEval] = [:]

    public init() {}

    /// A cached eval for `fen`, but only if it was computed with at least the
    /// requested budget (a 250ms eval satisfies a 120ms request, not vice versa).
    public func value(fen: String, atLeastMovetimeMs: Int) -> PositionEval? {
        guard let eval = store[fen], eval.movetimeMs >= atLeastMovetimeMs else { return nil }
        return eval
    }

    public func set(_ eval: PositionEval, fen: String) {
        if let existing = store[fen], existing.movetimeMs >= eval.movetimeMs { return }
        store[fen] = eval
    }

    /// Cleared at the start of each new game to bound memory.
    public func clear() {
        store.removeAll(keepingCapacity: true)
    }

    public var count: Int { store.count }
}
