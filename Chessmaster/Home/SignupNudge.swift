// Chessmaster — GPL-3.0-or-later
import SwiftUI
import PersistenceKit
import SupabaseSync

/// The "save your progress" nudge for signed-out players. Principle:
/// prompt when they have something to lose, never when they're trying to
/// play. Triggers: the first finished game ever, or a cold start once the
/// local pile is worth saving. Skips back off exponentially (2 → 4 → 7
/// days, once per day at most), but a fresh milestone — longer streak,
/// more games banked — resets the clock, because the pitch just got
/// stronger than the one they rejected.
enum SignupNudge {
    struct Prompt: Identifiable {
        let trigger: String          // first_game | milestone | cold_start
        let streakDays: Int
        let games: Int
        let rating: Int?
        var id: String { trigger }
    }

    private static let shownKey = "nudge.lastShownAt"
    private static let skipsKey = "nudge.skips"
    private static let milestoneKey = "nudge.milestone"

    static func evaluate(games allGames: [GameRecord],
                         isSignedIn: Bool,
                         isConfigured: Bool,
                         rating: Int?) -> Prompt? {
        guard isConfigured, !isSignedIn,
              !ProcessInfo.processInfo.arguments.contains("--uitest") else { return nil }
        let finished = allGames.filter {
            $0.opponentType == "engine" && $0.termination != "aborted"
        }.count
        guard finished >= 1 else { return nil }

        let defaults = UserDefaults.standard
        let lastShown = defaults.object(forKey: shownKey) as? Date
        let streaks = Streaks.compute(games: allGames)

        // First finished game: the moment they created something to keep.
        if finished == 1, lastShown == nil {
            return record(Prompt(trigger: "first_game", streakDays: streaks.playDays,
                                 games: finished, rating: rating))
        }

        // Milestone buckets — crossing one re-arms the nudge immediately.
        let streakBucket = [14, 7, 3, 2].first { streaks.playDays >= $0 } ?? 0
        let gamesBucket = [50, 25, 10, 5].first { finished >= $0 } ?? 0
        let milestone = "s\(streakBucket)-g\(gamesBucket)"
        let newMilestone = milestone != defaults.string(forKey: milestoneKey)
            && (streakBucket > 0 || gamesBucket > 0)

        // Nothing at stake yet: stay quiet.
        guard streakBucket > 0 || gamesBucket > 0 else { return nil }

        // Frequency: at most daily; skips back off 2 → 4 → 7 days unless a
        // new milestone resets the schedule.
        if let lastShown {
            let daysSince = Date().timeIntervalSince(lastShown) / 86_400
            guard daysSince >= 1 else { return nil }
            if !newMilestone {
                let skips = defaults.integer(forKey: skipsKey)
                let required = min(7.0, pow(2.0, Double(max(1, skips))))
                guard daysSince >= required else { return nil }
            }
        }

        defaults.set(milestone, forKey: milestoneKey)
        return record(Prompt(trigger: newMilestone ? "milestone" : "cold_start",
                             streakDays: streaks.playDays, games: finished, rating: rating))
    }

    static func skipped() {
        UserDefaults.standard.set(
            UserDefaults.standard.integer(forKey: skipsKey) + 1, forKey: skipsKey)
    }

    private static func record(_ prompt: Prompt) -> Prompt {
        UserDefaults.standard.set(Date(), forKey: shownKey)
        return prompt
    }
}

/// The personalized "keep your progress" card.
struct SignupNudgeCard: View {
    let prompt: SignupNudge.Prompt
    let onSignUp: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 18) {
            Image("LaunchLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.top, 26)

            HStack(spacing: 14) {
                if prompt.streakDays >= 2 {
                    stat("🔥", "\(prompt.streakDays)-day streak")
                }
                stat("♟️", "\(prompt.games) game\(prompt.games == 1 ? "" : "s")")
                if let rating = prompt.rating {
                    stat("📈", "rating \(rating)")
                }
            }

            Text(prompt.trigger == "first_game"
                 ? "Your first game lives only on this phone."
                 : "All of it lives only on this phone.")
                .font(.headline)
            Text("Create a free account and everything follows you — new phone, reinstall, anywhere.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Button {
                onSignUp()
            } label: {
                Text("Create free account")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(Color.green.opacity(0.85), in: Capsule())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)

            Button("Not now") {
                SignupNudge.skipped()
                dismiss()
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .padding(.bottom, 20)
        }
        .presentationDetents([.height(420)])
        .presentationDragIndicator(.visible)
    }

    private func stat(_ emoji: String, _ text: String) -> some View {
        VStack(spacing: 3) {
            Text(emoji).font(.title3)
            Text(text).font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }
}
