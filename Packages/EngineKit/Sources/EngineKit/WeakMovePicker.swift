// EngineKit — Chessmaster
// GPL-3.0-or-later

import Foundation

/// Human-like weak move selection: sample among the engine's candidate
/// moves, preferring slightly-worse-but-plausible ones over the coin flip
/// between brilliant and suicidal that uniform randomness produces.
/// Weight = exp(-(best − candidate) / temperature): a small temperature
/// nearly always picks the best move; a large one happily plays moves a
/// pawn or two worse — the way actual beginners do.
public enum WeakMovePicker {
    /// Picks an index into `candidates`. `random` must be uniform in [0, 1).
    public static func pick(
        _ candidates: [CandidateMove],
        temperatureCp: Double,
        random: Double
    ) -> Int {
        guard candidates.count > 1, temperatureCp > 0 else { return 0 }
        let best = candidates.map(\.value).max() ?? 0
        let weights = candidates.map { exp(-(best - $0.value) / temperatureCp) }
        let total = weights.reduce(0, +)
        guard total > 0 else { return 0 }
        var cursor = random * total
        for (index, weight) in weights.enumerated() {
            cursor -= weight
            if cursor <= 0 { return index }
        }
        return candidates.count - 1
    }
}
