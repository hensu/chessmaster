// Chessmaster — GPL-3.0-or-later
import Foundation
import AnalysisKit

/// Warms the engine eval cache *while a game is being played*, so the free
/// post-game Stockfish analysis is mostly (often fully) precomputed and opens
/// near-instantly instead of grinding through every position at the end.
///
/// Positions are evaluated in the background during the user's thinking time
/// and the prefetcher pauses whenever the opponent needs the engine, so it
/// never delays a move. Everything it computes lands in `EngineEvalCache`,
/// which `GameAnalyzer` reads first — a miss simply recomputes, so this can
/// only ever make analysis faster.
@MainActor
final class AnalysisPrefetcher {
    /// Match the post-game budget so cached evals satisfy that pass exactly.
    private let movetimeMs: Int
    private let analyzer: GameAnalyzer

    private var queue: [String] = []
    private var enqueued: Set<String> = []
    private var worker: Task<Void, Never>?
    /// Start paused: the caller resumes once it's the user's turn to think.
    private var paused = true

    init?(movetimeMs: Int = 250) {
        guard let big = StockfishNets.big, let small = StockfishNets.small else { return nil }
        self.analyzer = GameAnalyzer(evalFileBig: big, evalFileSmall: small)
        self.movetimeMs = movetimeMs
    }

    /// New game: drop any pending work and clear the (bounded) shared cache.
    func reset() {
        worker?.cancel()
        worker = nil
        queue.removeAll()
        enqueued.removeAll()
        Task { await EngineEvalCache.shared.clear() }
    }

    /// Queue a position reached during the game for background evaluation.
    func enqueue(fen: String) {
        guard enqueued.insert(fen).inserted else { return }
        queue.append(fen)
        pump()
    }

    /// The user is thinking — the engine is idle, so burn cycles on analysis.
    func resume() {
        paused = false
        pump()
    }

    /// The opponent is about to think — stop starting new evals so we don't
    /// compete for the engine (the in-flight one, if any, finishes on its own).
    func pause() { paused = true }

    /// Game over: stop prefetching. The cache stays warm for the post-game pass.
    func stop() {
        worker?.cancel()
        worker = nil
    }

    private func pump() {
        guard worker == nil, !paused, !queue.isEmpty else { return }
        worker = Task { [weak self] in
            while let self, !self.paused, !Task.isCancelled, !self.queue.isEmpty {
                let fen = self.queue.removeFirst()
                await self.analyzer.warm(fen: fen, movetimeMs: self.movetimeMs)
            }
            self?.worker = nil
        }
    }
}
