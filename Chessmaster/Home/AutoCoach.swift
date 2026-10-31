// Chessmaster — GPL-3.0-or-later
import SwiftUI
import ChessDomain
import PersistenceKit

/// The proactive first AI report. A trial/paid player who has never seen
/// the coach is the subscriber most likely to churn — 3 of 4 trialists in
/// Oct 2026 never tapped "Get AI Coaching". So on their next app open we
/// generate the report for their latest game in the background and hand
/// them the finished result, instead of waiting for a tap.
///
/// Once per player: the first completed report (auto or manual, on any
/// device) retires it for good. Generation failures retry on later opens,
/// capped at 3 attempts so a broken game can't burn model spend forever.
enum AutoCoach {
    struct Ready: Identifiable {
        let game: GameRecord
        let report: CoachingReport
        var id: String { game.id }
    }

    private static let doneKey = "autoCoach.done"
    private static let attemptsKey = "autoCoach.attempts"
    private static let maxAttempts = 3
    /// Too-short games give the coach nothing to say.
    private static let minPlies = 16

    /// Cheap local gate — no network. Returns the game worth coaching.
    @MainActor
    static func candidate(games: [GameRecord], container: DependencyContainer) -> GameRecord? {
        let defaults = UserDefaults.standard
        guard !ProcessInfo.processInfo.arguments.contains("--uitest"),
              container.entitlements.isPremium,
              container.sync.isConfigured, container.sync.isSignedIn,
              !defaults.bool(forKey: doneKey),
              defaults.integer(forKey: attemptsKey) < maxAttempts else { return nil }
        if games.contains(where: { $0.coachingJSON != nil }) {
            defaults.set(true, forKey: doneKey)
            return nil
        }
        return games
            .filter { $0.opponentType == "engine" && $0.termination != "aborted" }
            .filter { (GameReplay(pgn: $0.pgn)?.plies.count ?? 0) >= minPlies }
            .max { $0.endedAt < $1.endedAt }
    }

    /// Confirms with the server that this player has no report anywhere,
    /// then generates one. nil = nothing to show (already coached, offline,
    /// or the model failed — a later open retries).
    @MainActor
    static func run(for game: GameRecord, container: DependencyContainer) async -> Ready? {
        let defaults = UserDefaults.standard
        switch await container.sync.hasCompletedCoachingReport() {
        case .some(true):
            defaults.set(true, forKey: doneKey)
            return nil
        case .none:
            return nil   // unknown — never spend a report on a guess
        case .some(false):
            break
        }
        defaults.set(defaults.integer(forKey: attemptsKey) + 1, forKey: attemptsKey)
        let plan = container.entitlements.plan.rawValue
        container.sync.track("auto_coach_started", ["plan": plan])
        guard let report = try? await CoachingGenerator.generate(gameID: game.id, sync: container.sync),
              let data = try? JSONEncoder().encode(report),
              let json = String(data: data, encoding: .utf8) else {
            container.sync.track("auto_coach_failed", ["plan": plan])
            return nil
        }
        try? container.games.updateCoaching(id: game.id, coachingJSON: json)
        defaults.set(true, forKey: doneKey)
        container.sync.track("auto_coach_ready", ["plan": plan])
        let updated = (try? container.games.game(id: game.id)) ?? game
        return Ready(game: updated, report: report)
    }
}

/// "Your coach reviewed your last game" — the finished report, one tap away.
struct AutoCoachCard: View {
    let ready: AutoCoach.Ready
    let onOpen: () -> Void
    @Environment(\.dismiss) private var dismiss

    private var outcome: String {
        let game = ready.game
        if game.result == "draw" { return "Draw" }
        let won = (game.result == "whiteWin" && game.playerColor == "white")
            || (game.result == "blackWin" && game.playerColor == "black")
        return won ? "Win" : "Loss"
    }

    private var opponent: String {
        ready.game.opponentType == "friend" ? "a friend" : "Level \(ready.game.engineLevel ?? 1)"
    }

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "sparkles")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.green)
                .padding(.top, 28)

            Text("Your AI coach reviewed your last game")
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Text("\(outcome) vs \(opponent) · \(ready.game.endedAt.formatted(.relative(presentation: .named)))")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)

            Text(ready.report.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(4)
                .padding(.horizontal, 24)

            Button {
                onOpen()
            } label: {
                Text("See my report")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(Color.green.opacity(0.85), in: Capsule())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .accessibilityIdentifier("autoCoachOpen")

            Button("Later") { dismiss() }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.bottom, 20)
        }
        .presentationDetents([.height(440)])
        .presentationDragIndicator(.visible)
    }
}

/// Re-engagement for subscribers who stopped using the coach: after 3
/// finished games in a row without tapping "Get AI Coaching", the next
/// game's report is generated automatically and shown (post-game analysis
/// if they open it, else the Home card). Then it's back to the button and
/// the count starts over. A manual tap resets the count.
enum CoachHabit {
    private static let skippedKey = "coachHabit.skipped"
    private static let dueKey = "coachHabit.dueGame"
    static let threshold = 3
    private static let minPlies = 16

    /// Call once per finished game (after it's saved).
    @MainActor
    static func gameFinished(_ record: GameRecord, container: DependencyContainer) {
        guard !ProcessInfo.processInfo.arguments.contains("--uitest"),
              container.entitlements.isPremium, container.sync.isConfigured,
              record.termination != "aborted",
              (GameReplay(pgn: record.pgn)?.plies.count ?? 0) >= minPlies else { return }
        let defaults = UserDefaults.standard
        let skipped = defaults.integer(forKey: skippedKey)
        if skipped >= threshold {
            defaults.set(record.id, forKey: dueKey)
            defaults.set(0, forKey: skippedKey)
            container.sync.track("coach_habit_due", ["plan": container.entitlements.plan.rawValue])
        } else {
            defaults.set(skipped + 1, forKey: skippedKey)
        }
    }

    /// The player asked for a report themselves.
    static func coachingRequested() {
        UserDefaults.standard.set(0, forKey: skippedKey)
    }

    static func isDue(_ gameID: String) -> Bool {
        UserDefaults.standard.string(forKey: dueKey) == gameID
    }

    static func clearDue() {
        UserDefaults.standard.removeObject(forKey: dueKey)
    }

    /// The due game if it still has no report (the player closed the game
    /// without opening its analysis).
    @MainActor
    static func dueGame(container: DependencyContainer) -> GameRecord? {
        guard container.entitlements.isPremium,
              let id = UserDefaults.standard.string(forKey: dueKey) else { return nil }
        guard let game = try? container.games.game(id: id), game.coachingJSON == nil else {
            clearDue()
            return nil
        }
        return game
    }

    /// Generates the due game's report for the Home card.
    @MainActor
    static func run(for game: GameRecord, container: DependencyContainer) async -> AutoCoach.Ready? {
        clearDue()
        let plan = container.entitlements.plan.rawValue
        container.sync.track("auto_coach_started", ["plan": plan, "trigger": "habit"])
        guard let report = try? await CoachingGenerator.generate(gameID: game.id, sync: container.sync),
              let data = try? JSONEncoder().encode(report),
              let json = String(data: data, encoding: .utf8) else {
            container.sync.track("auto_coach_failed", ["plan": plan, "trigger": "habit"])
            return nil
        }
        try? container.games.updateCoaching(id: game.id, coachingJSON: json)
        container.sync.track("auto_coach_ready", ["plan": plan, "trigger": "habit"])
        let updated = (try? container.games.game(id: game.id)) ?? game
        return AutoCoach.Ready(game: updated, report: report)
    }
}
