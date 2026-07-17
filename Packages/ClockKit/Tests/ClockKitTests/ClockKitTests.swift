// ClockKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Testing
@testable import ClockKit

@MainActor
@Suite struct ChessClockTests {
    @Test func timeRunsOnlyForRunningSide() async throws {
        let clock = ChessClock(initial: .seconds(10), increment: .zero)
        clock.start(.white)
        try await Task.sleep(for: .milliseconds(120))
        #expect(clock.remaining(.white) < .seconds(10))
        #expect(clock.remaining(.black) == .seconds(10))
        clock.stop()
    }

    @Test func pressAddsIncrementAndSwitchesSides() async throws {
        let clock = ChessClock(initial: .seconds(10), increment: .seconds(2))
        clock.start(.white)
        try await Task.sleep(for: .milliseconds(100))
        clock.press(.white)
        // White banked: 10s - ~0.1s + 2s increment ≈ 11.9s.
        #expect(clock.remaining(.white) > .seconds(11))
        #expect(clock.remaining(.white) < .seconds(12))
        #expect(clock.running == .black)
        clock.stop()
    }

    @Test func pressByNonRunningSideIsIgnored() {
        let clock = ChessClock(initial: .seconds(10), increment: .seconds(2))
        clock.start(.white)
        clock.press(.black)
        #expect(clock.running == .white)
        #expect(clock.remaining(.black) == .seconds(10))
        clock.stop()
    }

    @Test func flagFiresWhenTimeRunsOut() async throws {
        let clock = ChessClock(initial: .milliseconds(150), increment: .zero)
        var flagged: ClockSide?
        clock.onFlag = { flagged = $0 }
        clock.start(.white)
        try await Task.sleep(for: .milliseconds(400))
        #expect(flagged == .white)
        #expect(clock.remaining(.white) == .zero)
        #expect(clock.isStopped)
    }

    @Test func stopFreezesTime() async throws {
        let clock = ChessClock(initial: .seconds(10), increment: .zero)
        clock.start(.white)
        try await Task.sleep(for: .milliseconds(80))
        clock.stop()
        let frozen = clock.remaining(.white)
        try await Task.sleep(for: .milliseconds(80))
        #expect(clock.remaining(.white) == frozen)
        // A stopped clock cannot restart.
        clock.start(.black)
        #expect(clock.running == nil)
    }
}
