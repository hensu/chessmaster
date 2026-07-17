// Chessmaster — GPL-3.0-or-later
import SwiftUI

/// Renders the whole-game coaching narrative: summary, phases, weaknesses,
/// study tips. Per-move detail lives on the review stepper above.
struct CoachingReportView: View {
    let report: CoachingReport
    var onJump: (Int) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Coach's report", systemImage: "brain.head.profile")
                .font(.headline)

            Text(report.summary)
                .font(.subheadline)

            // The game, phase by phase — the structure players think in.
            Text("Game phases")
                .font(.subheadline.bold())
            phaseRow("Opening", report.phaseAssessment.opening)
            phaseRow("Middlegame", report.phaseAssessment.middlegame)
            if let endgame = report.phaseAssessment.endgame, !endgame.isEmpty {
                phaseRow("Endgame", endgame)
            }

            if !report.weaknesses.isEmpty {
                HStack(spacing: 6) {
                    ForEach(report.weaknesses, id: \.self) { weakness in
                        Text(weakness.replacingOccurrences(of: "_", with: " "))
                            .font(.caption2)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.red.opacity(0.12), in: Capsule())
                            .foregroundStyle(.red)
                    }
                }
            }

            if !report.studyTips.isEmpty {
                Text("What to practice")
                    .font(.subheadline.bold())
                ForEach(report.studyTips, id: \.self) { tip in
                    Label(tip, systemImage: "checkmark.circle")
                        .font(.footnote)
                }
            }

            Label(report.encouragement, systemImage: "hand.thumbsup")
                .font(.footnote)
                .foregroundStyle(.green)
        }
        .padding()
        .background(Color.secondary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
    }

    private func phaseRow(_ phase: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(phase)
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            Text(text)
                .font(.footnote)
        }
    }
}
