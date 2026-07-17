// Chessmaster — GPL-3.0-or-later
import SwiftUI
import Charts
import ChessDomain
import EngineKit
import PaywallKit
import PersistenceKit

struct ProfileScreen: View {
    @Environment(DependencyContainer.self) private var container
    @State private var category: TimeControl.Category = .blitz
    @State private var pickedDefaultCategory = false
    @State private var history: [RatingHistoryRecord] = []
    @State private var stats: (wins: Int, draws: Int, losses: Int) = (0, 0, 0)
    @State private var showPaywall = false
    @State private var paywallSource = "profile_upgrade"
    @State private var showAccountSheet = false
    @State private var accountEmail: String?
    @State private var playerReview: PlayerReview?
    @State private var reviewLoading = false
    @State private var reviewMessage: String?
    @AppStorage("play.engineLevel") private var engineLevel = 3
    @AppStorage("play.levelMode") private var levelMode = "auto"
    @AppStorage("play.colorChoice") private var colorChoice = "random"
    @AppStorage("appearance") private var appearance = "dark"

    var body: some View {
        @Bindable var audio = container.audio
        @Bindable var voice = container.coachVoice
        List {
            Section("Rating") {
                Picker("Category", selection: $category) {
                    ForEach(TimeControl.Category.allCases, id: \.self) {
                        Text($0.rawValue.capitalized)
                    }
                }
                .pickerStyle(.segmented)

                if history.isEmpty {
                    Text("Play rated games against Stockfish to build your rating history.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Chart(Array(history.enumerated()), id: \.offset) { index, record in
                        LineMark(
                            x: .value("Game", index + 1),
                            y: .value("Rating", record.rating)
                        )
                        .interpolationMethod(.monotone)
                        AreaMark(
                            x: .value("Game", index + 1),
                            y: .value("Rating", record.rating)
                        )
                        .foregroundStyle(.linearGradient(
                            colors: [.accentColor.opacity(0.25), .clear],
                            startPoint: .top, endPoint: .bottom))
                    }
                    .chartYScale(domain: .automatic(includesZero: false))
                    .frame(height: 180)
                    .padding(.vertical, 6)

                    if let last = history.last {
                        HStack {
                            Text("Current")
                            Spacer()
                            Text("\(Int(last.rating.rounded())) ± \(Int(last.deviation.rounded()))")
                                .font(.headline.monospacedDigit())
                        }
                    }
                }
            }

            Section("Coach's overview") {
                if let review = playerReview {
                    Text(review.headline)
                        .font(.subheadline.weight(.semibold))
                    ForEach(review.recurringFlaws) { flaw in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(flaw.theme.replacingOccurrences(of: "_", with: " "))
                                .font(.caption.bold())
                                .foregroundStyle(.red)
                            Text(flaw.evidence)
                                .font(.footnote)
                            Label(flaw.fix, systemImage: "lightbulb")
                                .font(.footnote)
                                .foregroundStyle(.green)
                        }
                        .padding(.vertical, 2)
                    }
                    ForEach(review.focusPlan.indices, id: \.self) { i in
                        Label(review.focusPlan[i], systemImage: "\(i + 1).circle")
                            .font(.footnote)
                    }
                    Text(review.encouragement)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else if reviewLoading {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Your coach is reviewing your recent games…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    if let message = reviewMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Button {
                        requestPlayerReview()
                    } label: {
                        Label(
                            container.entitlements.plan == .diamond
                                ? "How can I improve?"
                                : "How can I improve? (Diamond)",
                            systemImage: "chart.line.uptrend.xyaxis"
                        )
                    }
                }
            }

            Section("Record vs Stockfish") {
                HStack {
                    statView(count: stats.wins, label: "Wins", color: .green)
                    statView(count: stats.draws, label: "Draws", color: .secondary)
                    statView(count: stats.losses, label: "Losses", color: .red)
                }
                .padding(.vertical, 4)
            }

            Section("Game") {
                Picker("Stockfish strength", selection: Binding(
                    get: { levelMode == "auto" ? 0 : engineLevel },
                    set: { newValue in
                        if newValue == 0 {
                            levelMode = "auto"
                        } else {
                            engineLevel = newValue
                            levelMode = "manual"
                        }
                        container.sync.track("strength_changed", [
                            "mode": levelMode,
                            "level": levelMode == "auto" ? "auto" : String(engineLevel),
                        ])
                    }
                )) {
                    Text("Auto — adapts to you").tag(0)
                    ForEach(1...10, id: \.self) { n in
                        Text("\(StrengthLevel.level(n).displayName) · Level \(n)").tag(n)
                    }
                }

                Picker("Your color", selection: $colorChoice) {
                    Text("Random").tag("random")
                    Text("White").tag("white")
                    Text("Black").tag("black")
                }
            }

            Section("Sound") {
                Toggle("Sound effects", isOn: $audio.soundsEnabled)
                Toggle("Background music", isOn: $audio.musicEnabled)
                Toggle("Coach voice in Game Review", isOn: $voice.enabled)
            }

            Section("Appearance") {
                Picker("Theme", selection: $appearance) {
                    Text("Dark").tag("dark")
                    Text("Light").tag("light")
                    Text("System").tag("system")
                }
            }

            Section("Membership") {
                switch container.entitlements.plan {
                case .diamond:
                    Label("Diamond — advanced AI coaching", systemImage: "crown.fill")
                        .foregroundStyle(.yellow)
                case .platinum:
                    Label("Platinum active", systemImage: "crown.fill")
                        .foregroundStyle(.secondary)
                    Button {
                        paywallSource = "profile_upgrade"
                        showPaywall = true
                    } label: {
                        Label("Upgrade to Diamond", systemImage: "crown")
                    }
                case .free:
                    Button {
                        paywallSource = "profile_upgrade"
                        showPaywall = true
                    } label: {
                        Label("Upgrade — Platinum or Diamond", systemImage: "crown")
                    }
                }
            }

            Section("About") {
                NavigationLink("Licenses & attributions") {
                    LicensesScreen()
                }
                LabeledContent("Version", value: Bundle.main.object(
                    forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
            }
            Section("Account") {
                switch container.sync.state {
                case .notConfigured:
                    EmptyView()
                case .signedOut:
                    Button {
                        showAccountSheet = true
                    } label: {
                        Label("Sign in or create account", systemImage: "person.crop.circle.badge.plus")
                    }
                case .idle, .syncing:
                    Label(accountEmail ?? "Your account",
                          systemImage: "person.crop.circle.badge.checkmark")
                    Button("Sign out", role: .destructive) {
                        Task { await container.sync.signOut() }
                    }
                case .error(let message):
                    Label(message, systemImage: "exclamationmark.icloud")
                        .foregroundStyle(.red)
                        .font(.footnote)
                    Button("Retry") {
                        Task { await container.sync.pushPending() }
                    }
                }
            }
        }
        .navigationTitle("Profile")
        .onAppear(perform: reload)
        .task { accountEmail = await container.sync.accountEmail }
        .onChange(of: category) { reload() }
        .sheet(isPresented: $showPaywall) {
            AppPaywall(source: paywallSource)
        }
        .sheet(isPresented: $showAccountSheet) {
            AccountSheet(sync: container.sync)
        }
    }

    private func statView(count: Int, label: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(count)")
                .font(.title3.bold().monospacedDigit())
                .foregroundStyle(color)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    /// Cross-game review: recurring flaws, strengths, focus plan (premium).
    private func requestPlayerReview() {
        guard container.entitlements.plan == .diamond else {
            paywallSource = "player_review"
            showPaywall = true
            return
        }
        reviewLoading = true
        reviewMessage = nil
        container.sync.track("player_review_requested")
        Task { @MainActor in
            defer { reviewLoading = false }
            do {
                let response: PlayerReviewResponse = try await container.sync.invokeFunction(
                    "generate-player-review", body: [String: String]()
                )
                if let report = response.review.report {
                    playerReview = report
                } else {
                    reviewMessage = "The coach couldn't build your overview yet."
                }
            } catch {
                reviewMessage = "Play a few more games with coaching first — the overview needs at least 2 coached games."
            }
        }
    }

    private func reload() {
        // Land on the player's own category: most frequent recently,
        // most recent as tie-break. Only once — manual picks stick.
        if !pickedDefaultCategory {
            pickedDefaultCategory = true
            let recent = ((try? container.games.recentGames(limit: 30)) ?? [])
                .compactMap { $0.ratingCategory }
                .compactMap { TimeControl.Category(rawValue: $0) }
            var counts: [TimeControl.Category: Int] = [:]
            for cat in recent { counts[cat, default: 0] += 1 }
            let maxCount = counts.values.max() ?? 0
            // Most frequent wins; ties go to the most recently played
            // (recent is newest-first, so the first hit is the tiebreak).
            if let primary = recent.first(where: { counts[$0] == maxCount }) {
                category = primary
            }
        }
        history = (try? container.ratingHistory.history(category: category.rawValue)) ?? []
        stats = (try? container.games.stats()) ?? (0, 0, 0)
    }
}
