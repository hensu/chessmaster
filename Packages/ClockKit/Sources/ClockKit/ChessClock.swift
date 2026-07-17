// ClockKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Observation

public enum ClockSide: Sendable, Hashable, CaseIterable {
    case white, black

    public var opposite: ClockSide { self == .white ? .black : .white }
}

/// Drift-free two-sided chess clock with Fischer increment.
///
/// Remaining time is never decremented by a timer: the running side's
/// remaining is always computed as `storedRemaining - (now - stampedAt)`,
/// so late timer wakeups (or backgrounding) can't lose or gain time.
/// UI ticks only trigger re-rendering.
@Observable @MainActor
public final class ChessClock {
    public let initial: Duration
    public let increment: Duration

    public private(set) var running: ClockSide?
    public private(set) var isStopped = false

    /// Called exactly once when a side runs out of time.
    public var onFlag: (@MainActor (ClockSide) -> Void)?

    private var stored: [ClockSide: Duration]
    private var stampedAt: ContinuousClock.Instant?
    private var flagTask: Task<Void, Never>?

    /// `restoredRemaining` overrides the starting time per side when
    /// resuming a saved game.
    public init(initial: Duration, increment: Duration, restoredRemaining: [ClockSide: Duration] = [:]) {
        self.initial = initial
        self.increment = increment
        self.stored = [
            .white: restoredRemaining[.white] ?? initial,
            .black: restoredRemaining[.black] ?? initial,
        ]
    }

    public func remaining(_ side: ClockSide) -> Duration {
        var value = stored[side]!
        if running == side, let stampedAt {
            value -= stampedAt.duration(to: .now)
        }
        return max(.zero, value)
    }

    /// Starts the clock for `side` (game start: white).
    public func start(_ side: ClockSide) {
        guard !isStopped, running == nil else { return }
        running = side
        stampedAt = .now
        scheduleFlagCheck(for: side)
    }

    /// The running side completed a move: bank its time, add the increment,
    /// start the opponent.
    public func press(_ side: ClockSide) {
        guard !isStopped, running == side, let stampedAt else { return }
        flagTask?.cancel()
        let spent = stampedAt.duration(to: .now)
        stored[side] = max(.zero, stored[side]! - spent) + increment
        running = side.opposite
        self.stampedAt = .now
        scheduleFlagCheck(for: side.opposite)
    }

    /// Permanently stops the clock (game over).
    public func stop() {
        guard !isStopped else { return }
        flagTask?.cancel()
        if let running, let stampedAt {
            stored[running] = max(.zero, stored[running]! - stampedAt.duration(to: .now))
        }
        running = nil
        stampedAt = nil
        isStopped = true
    }

    private func scheduleFlagCheck(for side: ClockSide) {
        let deadline = ContinuousClock.now + stored[side]!
        flagTask = Task { [weak self] in
            try? await Task.sleep(until: deadline, clock: .continuous)
            guard !Task.isCancelled, let self else { return }
            // Re-verify against computed remaining (guards against
            // spurious wakeups and clock adjustments).
            guard self.running == side, self.remaining(side) == .zero else { return }
            self.stop()
            self.onFlag?(side)
        }
    }
}
