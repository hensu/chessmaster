// Chessmaster — GPL-3.0-or-later
import SwiftUI
import ClockKit

struct ClockView: View {
    let clock: ChessClock
    let side: ClockSide

    private var isActive: Bool { clock.running == side }

    var body: some View {
        // 0.1s cadence normally; 0.05s under 10s to render tenths smoothly.
        TimelineView(.periodic(from: .now, by: clock.remaining(side) < .seconds(10) ? 0.05 : 0.1)) { _ in
            let remaining = clock.remaining(side)
            let low = remaining < .seconds(10)
            Text(Self.format(remaining))
                .font(.title3.weight(.bold).monospacedDigit())
                .foregroundStyle(low && isActive ? Color.white : .primary)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(
                    RoundedRectangle(cornerRadius: 6)
                        .fill(low && isActive ? Color.red : (isActive ? Color.green.opacity(0.25) : Color.secondary.opacity(0.12)))
                )
                .opacity(isActive || clock.isStopped ? 1 : 0.6)
        }
    }

    static func format(_ duration: Duration) -> String {
        let totalMs = duration.components.seconds * 1000
            + Int64(duration.components.attoseconds / 1_000_000_000_000_000)
        let totalSeconds = totalMs / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        if totalSeconds < 10 {
            let tenths = (totalMs % 1000) / 100
            return String(format: "%d:%02d.%d", minutes, seconds, tenths)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}
