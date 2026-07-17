// AnalysisKit — Chessmaster
// GPL-3.0-or-later

import Foundation

public enum MoveClassification: String, Codable, Sendable {
    case good
    case inaccuracy   // ?!
    case mistake      // ?
    case blunder      // ??

    public var glyph: String {
        switch self {
        case .good: ""
        case .inaccuracy: "?!"
        case .mistake: "?"
        case .blunder: "??"
        }
    }
}

/// Lichess's classification model: centipawns are mapped to win probability,
/// and a move is judged by how much win probability the mover threw away.
public enum MoveClassifier {
    /// Win probability (0-100) for the given side, from a white-POV
    /// centipawn eval. Constant is lichess's fitted value.
    public static func winPercent(cpWhite: Int, for moverIsWhite: Bool) -> Double {
        let cp = Double(moverIsWhite ? cpWhite : -cpWhite)
        let clamped = min(max(cp, -1000), 1000)
        return 50 + 50 * (2 / (1 + exp(-0.00368208 * clamped)) - 1)
    }

    /// Thresholds on win-probability drop. Tighter than lichess's 10/20/30:
    /// at club level games are usually lost through repeated 6-9% bleeds
    /// that a 10% floor never flags, leaving "nothing to review" in games
    /// the player clearly lost on merit.
    public static func classify(winPercentDrop drop: Double) -> MoveClassification {
        switch drop {
        case ..<6: .good
        case ..<14: .inaccuracy
        case ..<25: .mistake
        default: .blunder
        }
    }

    /// A move shouldn't be flagged when the game was already decided —
    /// "you were dead lost anyway" flags are noise ("inevitable moves"),
    /// and mopping-up moves in won positions don't need lessons either.
    public static func isPositionDecided(moverWinPercentBefore: Double) -> Bool {
        moverWinPercentBefore < 10 || moverWinPercentBefore > 95
    }

    /// Lichess's per-move accuracy curve (0-100) from win-probability drop.
    public static func accuracy(winPercentDrop drop: Double) -> Double {
        min(100, max(0, 103.1668 * exp(-0.04354 * max(0, drop)) - 3.1669))
    }
}
