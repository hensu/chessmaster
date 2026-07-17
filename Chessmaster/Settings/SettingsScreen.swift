// Chessmaster — GPL-3.0-or-later
import SwiftUI

struct LicensesScreen: View {
    private struct Item: Identifiable {
        let name: String
        let license: String
        let url: String
        var id: String { name }
    }

    private let items = [
        Item(name: "Stockfish", license: "GPL-3.0 — chess engine, embedded",
             url: "https://github.com/official-stockfish/Stockfish"),
        Item(name: "chesskit-swift", license: "MIT — rules, PGN, FEN",
             url: "https://github.com/chesskit-app/chesskit-swift"),
        Item(name: "GRDB.swift", license: "MIT — local persistence",
             url: "https://github.com/groue/GRDB.swift"),
        Item(name: "supabase-swift", license: "MIT — auth and sync",
             url: "https://github.com/supabase/supabase-swift"),
        Item(name: "cburnett piece set", license: "GPLv2+ / CC BY-SA 3.0 — Colin M.L. Burnett",
             url: "https://github.com/lichess-org/lila"),
    ]

    var body: some View {
        List {
            Section {
                Text("Chess AI is free software licensed under the GNU General Public License v3. It embeds the Stockfish chess engine. The complete source code of this app is available at the repository below.")
                    .font(.footnote)
                Link("App source code", destination: URL(string: "https://github.com/chessmaster-app/chessmaster")!)
                    .font(.footnote.bold())
            }
            Section("Third-party components") {
                ForEach(items) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name).font(.subheadline.bold())
                        Text(item.license).font(.caption).foregroundStyle(.secondary)
                        Link(item.url, destination: URL(string: item.url)!)
                            .font(.caption2)
                    }
                }
            }
            Section("Sounds") {
                Text("All sound effects and music are original synthesized audio created for Chessmaster.")
                    .font(.footnote)
            }
        }
        .navigationTitle("Licenses")
        .navigationBarTitleDisplayMode(.inline)
    }
}
