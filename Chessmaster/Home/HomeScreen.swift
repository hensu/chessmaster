// Chessmaster — GPL-3.0-or-later
import SwiftUI
import BoardUI
import ChessDomain
import ChessKit
import EngineKit
import PersistenceKit
import RatingKit
import SupabaseSync

/// Lichess-style quick pairing: a grid of time-control tiles. Tapping a
/// tile starts a game against Stockfish immediately — no opponent picker.
struct HomeScreen: View {
    @State private var pendingConfig: GameConfig?
    @State private var resumeRecord: InProgressGameRecord?
    @State private var pendingResume: InProgressGameRecord?
    @State private var showCustom = false
    @State private var streaks = Streaks(playDays: 0, wins: 0)
    /// The brief "get ready" card between tapping a tile and the board.
    @State private var matchCard: (config: GameConfig, title: String, subtitle: String, note: String?)?
    @AppStorage("play.engineLevel") private var engineLevel = 3
    @AppStorage("play.levelMode") private var levelMode = "auto"
    @AppStorage("play.colorChoice") private var colorChoice = "random"
    // Session-only overrides for "this game"; Profile holds the defaults.
    @State private var levelOverride: Int?
    @State private var colorOverride: String?
    @State private var signupPrompt: SignupNudge.Prompt?
    @State private var nudgeAccepted = false
    @State private var showNudgeAccountSheet = false
    /// Proactive first AI report (see AutoCoach): generated in the
    /// background, presented only while Home is on screen.
    @State private var autoCoachRunning = false
    @State private var autoCoachPending: AutoCoach.Ready?
    @State private var autoCoachShown: AutoCoach.Ready?
    @State private var autoCoachAccepted = false
    @State private var autoCoachGame: GameRecord?
    @State private var isVisible = false
    /// Friend games: the invite sheet, games still open on the server, and
    /// free months earned by inviting (App Store offer codes).
    @State private var showFriendLobby = false
    @State private var openFriendGames: [FriendGameState] = []
    @State private var waitingInvite: FriendGameState?
    @State private var rewards: [ReferralReward] = []
    @Environment(DependencyContainer.self) private var container

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    private var autoStartConfig: GameConfig? {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--autostart-engine-game") {
            return GameConfig(opponent: .engine(level: 1))
        }
        if arguments.contains("--autostart-game") {
            return GameConfig(opponent: .humanLocal)
        }
        return nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let record = resumeRecord {
                    Button {
                        pendingResume = record
                    } label: {
                        HStack {
                            Label("Continue game", systemImage: "play.circle.fill")
                                .font(.headline)
                            Spacer()
                            Text("\(record.movesUCI.split(separator: " ").count) moves")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        .padding()
                        .background(Color(.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                }

                ForEach(rewards) { reward in
                    rewardCard(reward)
                }

                ForEach(openFriendGames) { game in
                    friendGameCard(game)
                }

                ratingsRow

                if streaks.playDays >= 2 || streaks.wins >= 2 {
                    HStack(spacing: 10) {
                        if streaks.playDays >= 2 {
                            streakChip("🔥", "\(streaks.playDays)-day streak")
                        }
                        if streaks.wins >= 2 {
                            streakChip("🏆", "\(streaks.wins) wins in a row")
                        }
                    }
                }

                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(TimeControl.presets, id: \.self) { tc in
                        tile(top: tc.label, bottom: tc.category.rawValue.capitalized) {
                            start(timeControl: tc)
                        }
                    }
                    tile(top: "∞", bottom: "Untimed") {
                        start(timeControl: nil)
                    }
                    tile(top: "Custom", bottom: "Time control") {
                        showCustom = true
                    }
                }

                Button {
                    container.sync.track("friend_lobby_opened")
                    showFriendLobby = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "person.2.fill")
                            .font(.title3)
                            .foregroundStyle(.green)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Play a Friend").font(.headline)
                            Text("Send a link by text · live game")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding()
                    .background(Color(.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("playFriend")

                gameOptionsBar
            }
            .padding(.horizontal)
            .padding(.bottom, 12)
        }
        .background(Color(.systemGroupedBackground))
        .overlay {
            if let card = matchCard {
                ZStack {
                    Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
                    VStack(spacing: 14) {
                        pieceImage(kind: .knight, color: .white)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 64, height: 64)
                            .padding(16)
                            .background(
                                Color(red: 0xB5 / 255, green: 0x88 / 255, blue: 0x63 / 255),
                                in: RoundedRectangle(cornerRadius: 20)
                            )
                        Text(card.title)
                            .font(.system(size: 34, weight: .bold).monospacedDigit())
                        Text(card.subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if let note = card.note {
                            Label(note, systemImage: note.hasPrefix("Level up")
                                ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(note.hasPrefix("Level up") ? .green : .orange)
                        }
                    }
                    .padding(32)
                    .background(Color(.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: 24))
                    .shadow(radius: 24)
                }
                .transition(.opacity.combined(with: .scale(scale: 0.92)))
            }
        }
        .navigationTitle("Chess AI")
        .trackScreen("Home")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showCustom) {
            CustomTimeSheet { tc in
                showCustom = false
                start(timeControl: tc)
            }
        }
        .navigationDestination(item: $pendingConfig) { config in
            GameScreen(config: config)
        }
        .navigationDestination(item: $pendingResume) { record in
            GameScreen(resume: record)
        }
        .onAppear {
            resumeRecord = try? container.inProgress.load()
            let games = (try? container.games.recentGames()) ?? []
            streaks = Streaks.compute(games: games)
            if let config = autoStartConfig, pendingConfig == nil {
                pendingConfig = config
            }
            // Screenshots / UI tests: open the Play a Friend sheet directly.
            if ProcessInfo.processInfo.arguments.contains("--open-friend-lobby") {
                showFriendLobby = true
            }
            evaluateSignupNudge()
            isVisible = true
            evaluateAutoCoach()
        }
        .onDisappear { isVisible = false }
        .task(id: container.activeFriendGame == nil && !showFriendLobby) {
            await refreshFriendState()
        }
        .sheet(isPresented: $showFriendLobby) {
            FriendLobbySheet()
                .environment(container)
        }
        .sheet(item: $waitingInvite) { game in
            FriendLobbySheet(initial: game)
                .environment(container)
        }
        // The nudge waits for the session restore to settle; when it does,
        // re-check (onAppear may have run while auth was still unknown).
        .onChange(of: container.sync.sessionRestored) { _, _ in
            evaluateSignupNudge()
            evaluateAutoCoach()
        }
        // Entitlements resolve asynchronously at launch, and a purchase can
        // land mid-session — either can make the first report due.
        .onChange(of: container.entitlements.plan) { _, _ in evaluateAutoCoach() }
        .onChange(of: container.sync.isSignedIn) { _, _ in evaluateAutoCoach() }
        .sheet(item: $autoCoachShown, onDismiss: {
            if autoCoachAccepted {
                autoCoachAccepted = false
            } else {
                container.sync.track("auto_coach_later")
            }
        }) { ready in
            AutoCoachCard(ready: ready) {
                container.sync.track("auto_coach_opened")
                autoCoachAccepted = true
                autoCoachShown = nil
                autoCoachGame = ready.game
            }
        }
        .navigationDestination(item: $autoCoachGame) { game in
            AnalysisScreen(game: game, source: "auto_coach", scrollToCoaching: true)
        }
        .sheet(item: $signupPrompt, onDismiss: {
            if nudgeAccepted {
                nudgeAccepted = false
                showNudgeAccountSheet = true
            } else {
                container.sync.track("signup_nudge_skipped")
            }
        }) { prompt in
            SignupNudgeCard(prompt: prompt) {
                container.sync.track("signup_nudge_accepted", ["trigger": prompt.trigger])
                nudgeAccepted = true
                signupPrompt = nil
            }
        }
        .sheet(isPresented: $showNudgeAccountSheet) {
            AccountSheet(sync: container.sync)
        }
    }

    /// Save-your-progress nudge: only once the session restore has settled,
    /// and only when signed out AND there's something on this phone worth
    /// keeping (see SignupNudge). Gating on `sessionRestored` is what stops
    /// the nudge from flashing at an already-signed-in user at launch.
    private func evaluateSignupNudge() {
        guard container.sync.sessionRestored,
              pendingConfig == nil, signupPrompt == nil else { return }
        let games = (try? container.games.recentGames()) ?? []
        guard let prompt = SignupNudge.evaluate(
            games: games,
            isSignedIn: container.sync.isSignedIn,
            isConfigured: container.sync.isConfigured,
            rating: establishedRating()
        ) else { return }
        signupPrompt = prompt
        container.sync.track("signup_nudge_shown", ["trigger": prompt.trigger])
    }

    /// Kicks off the proactive first report when due, and presents a
    /// finished one once Home is visible with nothing else on top.
    private func evaluateAutoCoach() {
        presentAutoCoachIfReady()
        guard container.sync.sessionRestored, !autoCoachRunning, autoCoachPending == nil,
              let game = AutoCoach.candidate(
                games: (try? container.games.recentGames()) ?? [], container: container)
        else { return }
        autoCoachRunning = true
        Task {
            autoCoachPending = await AutoCoach.run(for: game, container: container)
            autoCoachRunning = false
            presentAutoCoachIfReady()
        }
    }

    private func presentAutoCoachIfReady() {
        guard let ready = autoCoachPending, isVisible,
              pendingConfig == nil, pendingResume == nil, signupPrompt == nil,
              autoCoachShown == nil else { return }
        autoCoachPending = nil
        autoCoachShown = ready
        container.sync.track("auto_coach_shown")
    }

    // MARK: - Per-game overrides (defaults live in Profile)

    private var effectiveLevelLabel: String {
        if let levelOverride {
            return StrengthLevel.level(levelOverride).displayName
        }
        if levelMode == "manual" {
            return StrengthLevel.level(engineLevel).displayName
        }
        return "Auto · \(StrengthLevel.level(resolvedLevel(for: nil).level).displayName)"
    }

    private var effectiveColorLabel: String {
        (colorOverride ?? colorChoice).capitalized
    }

    /// Level + color for THIS game, pinned under the tiles.
    private var gameOptionsBar: some View {
        VStack(spacing: 6) {
            HStack {
                Menu {
                    Button {
                        levelOverride = nil
                    } label: {
                        if levelOverride == nil && levelMode == "auto" {
                            Label("Auto (recommended)", systemImage: "checkmark")
                        } else {
                            Text("Auto (recommended)")
                        }
                    }
                    Picker("Level", selection: Binding(
                        get: { levelOverride ?? (levelMode == "manual" ? engineLevel : -1) },
                        set: { levelOverride = $0 }
                    )) {
                        ForEach(1...10, id: \.self) { n in
                            Text("\(StrengthLevel.level(n).displayName) · Level \(n)").tag(n)
                        }
                    }
                } label: {
                    optionChip(icon: "dial.medium", label: effectiveLevelLabel)
                }

                Spacer()

                Menu {
                    Picker("Your color", selection: Binding(
                        get: { colorOverride ?? colorChoice },
                        set: { colorOverride = $0 }
                    )) {
                        Text("Random").tag("random")
                        Text("White").tag("white")
                        Text("Black").tag("black")
                    }
                } label: {
                    optionChip(icon: "circle.lefthalf.filled", label: effectiveColorLabel)
                }
            }
            Text("For this game — change defaults in Profile.")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(.top, 2)
    }

    // MARK: - Friends

    private func refreshFriendState() async {
        guard container.sync.isSignedIn else { return }
        openFriendGames = await container.sync.openFriendGames()
        rewards = await container.sync.unredeemedReferralRewards()
    }

    private func friendGameCard(_ game: FriendGameState) -> some View {
        let me = container.sync.userID ?? game.hostID
        let waiting = game.status == "waiting"
        return Button {
            if waiting {
                waitingInvite = game
            } else {
                container.startFriendGame(game)
            }
        } label: {
            HStack {
                Label(waiting ? "Invite waiting · \(game.code)" : "Your game vs \(game.opponentName(for: me))",
                      systemImage: waiting ? "hourglass" : "person.2.fill")
                    .font(.headline)
                Spacer()
                Text(waiting ? "Open" : "Resume")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.green)
            }
            .padding()
            .background(Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private func rewardCard(_ reward: ReferralReward) -> some View {
        Button {
            container.sync.track("referral_redeem_tapped", ["role": reward.role])
            UIApplication.shared.open(reward.redeemURL(appID: "6790178402"))
            Task {
                await container.sync.markReferralRedeemed(code: reward.code)
                rewards.removeAll { $0.code == reward.code }
            }
        } label: {
            HStack(spacing: 12) {
                Text("🎁").font(.title2)
                VStack(alignment: .leading, spacing: 2) {
                    Text("You earned a free month of Diamond")
                        .font(.headline)
                    Text("For playing a friend. Tap to redeem.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()
            .background(Color.green.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private func optionChip(icon: String, label: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
            Text(label)
        }
        .font(.subheadline.weight(.medium))
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(Color(.secondarySystemGroupedBackground), in: Capsule())
    }

    // MARK: - Pieces

    private func streakChip(_ emoji: String, _ label: String) -> some View {
        HStack(spacing: 6) {
            Text(emoji)
            Text(label)
                .font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color(.secondarySystemGroupedBackground), in: Capsule())
    }

    private func tile(top: String, bottom: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Text(top)
                    // One weight/size for every tile; the few longer labels
                    // (15+10, 30+20, Custom) shrink slightly instead of
                    // switching to a smaller, lighter font.
                    .font(.title2.bold().monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text(bottom)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 78)
            .background(Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private var ratingsRow: some View {
        HStack(spacing: 10) {
            ForEach(TimeControl.Category.allCases, id: \.self) { category in
                VStack(spacing: 2) {
                    if let rating = container.ratings.ratings[category] {
                        Text("\(Int(rating.rating.rounded()))")
                            .font(.headline.monospacedDigit())
                    } else {
                        Text("—")
                            .font(.headline)
                            .foregroundStyle(.tertiary)
                    }
                    Text(category.rawValue.capitalized)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    private func start(timeControl: TimeControl?) {
        // Deterministic color for UI tests; random breaks board assertions.
        let uiTesting = ProcessInfo.processInfo.arguments.contains("--uitest")
        let choice = uiTesting ? "white" : (colorOverride ?? colorChoice)
        let color: Piece.Color = switch choice {
        case "white": .white
        case "black": .black
        default: Bool.random() ? .white : .black
        }
        let recommended = resolvedLevel(for: timeControl?.category)
        let level = levelOverride ?? recommended.level
        let config = GameConfig(
            playerColor: color,
            timeControl: timeControl,
            opponent: .engine(level: level),
            // A manual pick means the player wants that exact strength.
            engineBlunderProbability: levelOverride == nil ? recommended.blunderProbability : 0
        )
        if uiTesting {
            pendingConfig = config
            return
        }

        // The "get ready" beat: haptic, match card, then the board.
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        let title = timeControl?.label ?? "Untimed"
        let subtitle = "Level \(level) \(StrengthLevel.level(level).displayName) · You play \(color == .white ? "White" : "Black")"
        // Tell the player when Auto promoted or eased them since their last
        // rated game (training games don't count — their level is separate).
        var note: String?
        if levelOverride == nil, levelMode == "auto",
           let previous = ((try? container.games.recentGames(limit: 10)) ?? [])
               .first(where: { $0.opponentType == "engine" && $0.termination != "aborted" && $0.ratingCategory != nil })?
               .engineLevel,
           previous != level {
            note = level > previous
                ? "Level up! Promoted to \(StrengthLevel.level(level).displayName)"
                : "Easing off — down to \(StrengthLevel.level(level).displayName)"
        }
        withAnimation(.spring(duration: 0.25)) {
            matchCard = (config, title, subtitle, note)
        }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(900))
            pendingConfig = config
            try? await Task.sleep(for: .milliseconds(400))
            withAnimation { matchCard = nil }
        }
    }

    /// The player's best established rating — only shown on the nudge when
    /// it's a number worth bragging about.
    private func establishedRating() -> Int? {
        container.ratings.ratings.values
            .filter { $0.deviation < 250 }
            .map { Int($0.rating.rounded()) }
            .filter { $0 >= 400 }
            .max()
    }

    private func resolvedLevel(for category: TimeControl.Category?) -> AdaptiveLevel.Recommendation {
        guard levelMode == "auto" else {
            return .init(level: engineLevel, blunderProbability: 0)
        }
        // Untimed games share the classical bucket (that's how they're rated),
        // so Auto level is tracked per category, classical standing in for both.
        let cat = category ?? .classical
        let rating = container.ratings.rating(for: cat)
        let recent = (try? container.games.recentGames(limit: 20)) ?? []
        return AdaptiveLevel.level(for: cat, rating: rating, recentGames: recent)
    }
}

extension InProgressGameRecord: @retroactive Identifiable {
    public var id: Date { updatedAt }
}

/// Minimal sheet for a custom time control — everything else has defaults.
struct CustomTimeSheet: View {
    let onStart: (TimeControl) -> Void
    @State private var minutes = 5
    @State private var increment = 3
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Stepper("Minutes: \(minutes)", value: $minutes, in: 1...120)
                Stepper("Increment: \(increment)s", value: $increment, in: 0...60)
                Button {
                    onStart(TimeControl(minutes: minutes, incrementSeconds: increment))
                } label: {
                    Text("Start game")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("Custom game")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
