// ChessDomain — Chessmaster
// GPL-3.0-or-later

import Foundation

/// A chess time control: initial time plus a Fischer increment per move.
public struct TimeControl: Hashable, Sendable, Codable {
    public var initial: Duration
    public var increment: Duration

    public init(initial: Duration, increment: Duration) {
        self.initial = initial
        self.increment = increment
    }

    public init(minutes: Int, incrementSeconds: Int) {
        self.init(initial: .seconds(minutes * 60), increment: .seconds(incrementSeconds))
    }

    public enum Category: String, Sendable, Codable, CaseIterable {
        case bullet, blitz, rapid, classical
    }

    /// Lichess speed categories, by estimated game duration
    /// (initial + 40 × increment).
    public var category: Category {
        let estimate = initial.components.seconds + 40 * increment.components.seconds
        switch estimate {
        case ..<180: return .bullet
        case ..<480: return .blitz
        case ..<1500: return .rapid
        default: return .classical
        }
    }

    /// "3+2" style label, minutes+seconds like lichess.
    public var label: String {
        let minutes = initial.components.seconds / 60
        let seconds = initial.components.seconds % 60
        let base = seconds == 0 ? "\(minutes)" : "\(minutes)½"
        return "\(base)+\(increment.components.seconds)"
    }

    /// PGN TimeControl tag value, e.g. "180+2".
    public var pgnTag: String {
        "\(initial.components.seconds)+\(increment.components.seconds)"
    }

    public static let presets: [TimeControl] = [
        .init(minutes: 1, incrementSeconds: 0),
        .init(minutes: 2, incrementSeconds: 1),
        .init(minutes: 3, incrementSeconds: 0),
        .init(minutes: 3, incrementSeconds: 2),
        .init(minutes: 5, incrementSeconds: 0),
        .init(minutes: 5, incrementSeconds: 3),
        .init(minutes: 10, incrementSeconds: 0),
        .init(minutes: 10, incrementSeconds: 5),
        .init(minutes: 15, incrementSeconds: 10),
        .init(minutes: 30, incrementSeconds: 0),
        .init(minutes: 30, incrementSeconds: 20),
    ]
}
