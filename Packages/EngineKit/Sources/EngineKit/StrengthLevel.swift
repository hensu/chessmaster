// EngineKit — Chessmaster
// GPL-3.0-or-later

/// The 10-level difficulty ladder (lichess offers 8; we extend to 10).
/// Low levels use Stockfish's "Skill Level" handicap (adds bounded move
/// randomness); mid/high levels use the calibrated UCI_Elo cap.
/// `ratingEstimate` is used as the opponent's Glicko-2 rating.
public struct StrengthLevel: Sendable, Hashable, Identifiable {
    public let level: Int
    public let uciOptions: [String: String]
    public let movetimeMs: Int
    public let depth: Int?
    public let ratingEstimate: Int
    /// Chance per move of playing a random legal move instead of the
    /// engine's choice. Stockfish's natural floor is ~1200 human Elo even
    /// at Skill 0 / depth 1 (measured: 4% player score across all games),
    /// so the low rungs blunder deliberately to actually PLAY at their
    /// advertised ratingEstimate. Tune from win-rate data.
    public let blunderProbability: Double

    public var id: Int { level }

    /// Player-facing name for the level (shown in pickers and game titles).
    public var displayName: String {
        switch level {
        case 1: "Newcomer"
        case 2: "Beginner"
        case 3: "Casual"
        case 4: "Improving"
        case 5: "Club player"
        case 6: "Strong club"
        case 7: "Expert"
        case 8: "Master"
        case 9: "Grandmaster"
        default: "Maximum"
        }
    }

    public static let all: [StrengthLevel] = [
        .skill(1, skill: 0, depth: 1, movetimeMs: 50, rating: 450, blunder: 0.45),
        .skill(2, skill: 2, depth: 3, movetimeMs: 80, rating: 700, blunder: 0.30),
        .skill(3, skill: 4, depth: 5, movetimeMs: 100, rating: 900, blunder: 0.18),
        .skill(4, skill: 6, depth: 8, movetimeMs: 200, rating: 1100, blunder: 0.10),
        .skill(5, skill: 9, depth: 10, movetimeMs: 200, rating: 1350, blunder: 0.05),
        .elo(6, elo: 1500, movetimeMs: 400, rating: 1500),
        .elo(7, elo: 1800, movetimeMs: 400, rating: 1800),
        .elo(8, elo: 2100, movetimeMs: 400, rating: 2100),
        .elo(9, elo: 2500, movetimeMs: 800, rating: 2500),
        StrengthLevel(
            level: 10,
            uciOptions: ["Skill Level": "20", "UCI_LimitStrength": "false"],
            movetimeMs: 1000,
            depth: nil,
            ratingEstimate: 3000,
            blunderProbability: 0
        ),
    ]

    public static func level(_ n: Int) -> StrengthLevel {
        all[max(1, min(10, n)) - 1]
    }

    private static func skill(_ level: Int, skill: Int, depth: Int, movetimeMs: Int,
                              rating: Int, blunder: Double) -> StrengthLevel {
        StrengthLevel(
            level: level,
            uciOptions: ["Skill Level": "\(skill)", "UCI_LimitStrength": "false"],
            movetimeMs: movetimeMs,
            depth: depth,
            ratingEstimate: rating,
            blunderProbability: blunder
        )
    }

    private static func elo(_ level: Int, elo: Int, movetimeMs: Int, rating: Int) -> StrengthLevel {
        StrengthLevel(
            level: level,
            uciOptions: [
                "Skill Level": "20",
                "UCI_LimitStrength": "true",
                "UCI_Elo": "\(elo)",
            ],
            movetimeMs: movetimeMs,
            depth: nil,
            ratingEstimate: rating,
            blunderProbability: 0
        )
    }
}
