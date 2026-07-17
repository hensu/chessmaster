// Chessmaster — GPL-3.0-or-later
import SwiftUI
import ChessDomain
import BoardUI
import EngineKit
import PersistenceKit

struct HistoryScreen: View {
    @Environment(DependencyContainer.self) private var container
    @State private var games: [GameRecord] = []

    var body: some View {
        Group {
            if games.isEmpty {
                ContentUnavailableView(
                    "No games yet",
                    systemImage: "checkerboard.rectangle",
                    description: Text("Finished games appear here.")
                )
            } else {
                List {
                    ForEach(games) { game in
                        NavigationLink(value: game.id) {
                            GameHistoryRow(game: game)
                        }
                    }
                    .onDelete(perform: delete)
                }
                .navigationDestination(for: String.self) { gameID in
                    if let game = games.first(where: { $0.id == gameID }) {
                        AnalysisScreen(game: game)
                    }
                }
            }
        }
        .navigationTitle("History")
        .onAppear {
            games = (try? container.games.recentGames()) ?? []
        }
    }

    /// Deletes locally and (best-effort) from the player's account.
    private func delete(at offsets: IndexSet) {
        let doomed = offsets.map { games[$0] }
        games.remove(atOffsets: offsets)
        for game in doomed {
            container.sync.track("game_deleted", ["time_class": game.ratingCategory ?? "untimed"])
            try? container.games.delete(id: game.id)
            let id = game.id
            Task { await container.sync.deleteRemoteGame(id: id) }
        }
    }
}

struct GameHistoryRow: View {
    let game: GameRecord

    private var userWon: Bool? {
        switch (game.result, game.playerColor) {
        case ("draw", _): return nil
        case ("whiteWin", "white"), ("blackWin", "black"): return true
        default: return false
        }
    }

    private var opponentLabel: String {
        game.opponentType == "engine"
            ? "Level \(game.engineLevel ?? 1) · \(StrengthLevel.level(game.engineLevel ?? 1).displayName)"
            : "Two players"
    }

    private var timeControlLabel: String {
        guard let initial = game.tcInitialSeconds, let increment = game.tcIncrementSeconds else {
            return "Casual"
        }
        return "\(initial / 60)+\(increment)"
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: userWon == true ? "trophy.fill" : (userWon == false ? "xmark.circle" : "equal.circle"))
                .foregroundStyle(userWon == true ? .green : (userWon == false ? .red : .secondary))
                .frame(width: 26)

            VStack(alignment: .leading, spacing: 2) {
                Text(opponentLabel)
                    .font(.subheadline.weight(.semibold))
                HStack(spacing: 6) {
                    Text(timeControlLabel)
                    Text(game.endedAt, style: .date)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            if let before = game.ratingBefore, let after = game.ratingAfter {
                let delta = Int(after.rounded()) - Int(before.rounded())
                Text(delta >= 0 ? "+\(delta)" : "\(delta)")
                    .font(.subheadline.bold().monospacedDigit())
                    .foregroundStyle(delta >= 0 ? .green : .red)
            }
        }
    }
}

