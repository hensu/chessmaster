// Chessmaster — GPL-3.0-or-later
import SwiftUI
import ChessDomain
import BoardUI
import PaywallKit
import PersistenceKit

struct GameScreen: View {
    @State private var model: GameViewModel
    @State private var analyzedGame: GameRecord?
    @Environment(\.dismiss) private var dismiss
    @Environment(DependencyContainer.self) private var container

    init(config: GameConfig, advice: String? = nil, originalOutcome: String? = nil,
         arrows: [BoardArrow] = []) {
        _model = State(initialValue: {
            let model = GameViewModel(config: config)
            model.trainingAdvice = advice
            model.trainingOriginal = originalOutcome
            model.trainingArrows = arrows
            return model
        }())
    }

    init(resume record: InProgressGameRecord) {
        let config = GameConfig(record: record) ?? GameConfig()
        _model = State(initialValue: GameViewModel(config: config, resume: record))
    }

    var body: some View {
        VStack(spacing: 12) {
            // Replay training: the coach's advice rides above the board.
            if let advice = model.trainingAdvice, model.session.status.isPlaying {
                VStack(alignment: .leading, spacing: 4) {
                    Label(advice, systemImage: "lightbulb.fill")
                        .font(.footnote)
                        .foregroundStyle(.primary)
                    if model.session.moveHistory.isEmpty, !model.trainingArrows.isEmpty {
                        Text("On the board: red — your move · green — Chess AI")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            }

            playerBar(for: model.orientation.opposite)

            BoardView(
                pieces: model.session.boardPieces,
                orientation: model.orientation,
                lastMove: model.session.lastMove,
                checkSquare: model.session.checkedKingSquare,
                // Retry-training: played vs suggested, until the first move.
                arrows: model.session.moveHistory.isEmpty ? model.trainingArrows : [],
                canSelect: { square in
                    guard model.session.status.isPlaying,
                          let piece = model.session.piece(at: square) else { return false }
                    return piece.color == model.session.sideToMove
                        && model.session.isUserControlled(piece.color)
                        && !model.session.opponentThinking
                },
                legalTargets: { model.session.legalTargets(from: $0) },
                onMove: { from, to in
                    _ = model.session.attemptUserMove(from: from, to: to)
                },
                pendingPromotion: model.session.pendingPromotion,
                onPromote: { model.session.completePromotion(to: $0) },
                onCancelPromotion: { model.session.cancelPromotion() }
            )

            playerBar(for: model.orientation)

            Spacer(minLength: 0)

            bottomBar
        }
        .padding(.horizontal, 8)
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.session.status.isPlaying)
        .confirmationDialog("Resign this game?", isPresented: $model.showResignConfirm, titleVisibility: .visible) {
            Button("Resign", role: .destructive) { model.session.resign() }
        }
        .sheet(isPresented: $model.showGameOver) {
            GameOverSheet(
                model: model,
                dismissGame: { dismiss() },
                onAnalyze: { analyzedGame = model.savedGameRecord }
            )
            .presentationDetents([.height(320)])
        }
        .fullScreenCover(item: $analyzedGame) { game in
            NavigationStack {
                AnalysisScreen(game: game, openAtWorstMoment: true, source: "post_game")
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Close") {
                                analyzedGame = nil
                                dismiss()
                            }
                        }
                    }
            }
            .environment(container)
        }
        .onAppear {
            model.attach(container: container)
            model.start()
        }
        // Insights ready while the game-over sheet is up: swap it for the
        // analysis view automatically (Close = skip).
        // An aborted game has nothing to show: straight back home.
        .onChange(of: model.session.status.isPlaying) { _, playing in
            if !playing, case .finished(.aborted) = model.session.status {
                dismiss()
            }
        }
        .onChange(of: model.postGameInsights?.id) {
            guard let record = model.postGameInsights,
                  !model.rematchRequested
            else { return }
            model.showGameOver = false
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(400))
                analyzedGame = record
            }
        }
        // While the post-game analysis runs, the result + a kind word
        // replace the old Done/Rematch sheet; analysis opens by itself.
        .overlay {
            if model.preparingInsights, !model.showGameOver, analyzedGame == nil {
                VStack(spacing: 12) {
                    Text(model.resultHeadline)
                        .font(.title2.bold())
                    if let change = model.ratingChange {
                        let delta = Int(change.after.rating.rounded()) - Int(change.before.rating.rounded())
                        Text(delta >= 0 ? "+\(delta)" : "\(delta)")
                            .font(.title3.bold().monospacedDigit())
                            .foregroundStyle(delta >= 0 ? .green : .red)
                    }
                    Text(model.encouragement)
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Chess AI is reviewing your game…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 4)
                }
                .padding(24)
                .frame(maxWidth: 320)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
                .shadow(radius: 20)
                .transition(.opacity)
            }
        }
    }

    /// Always-visible game controls — no hunting through menus mid-game.
    private var bottomBar: some View {
        HStack(spacing: 12) {
            Button {
                model.orientation = model.orientation.opposite
            } label: {
                Label("Flip", systemImage: "arrow.up.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            if model.session.status.isPlaying {
                if model.session.moveHistory.count < 2 {
                    Button(role: .destructive) {
                        model.session.abort()
                    } label: {
                        Label("Abort", systemImage: "xmark")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(role: .destructive) {
                        model.showResignConfirm = true
                    } label: {
                        Label("Resign", systemImage: "flag.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private func playerBar(for color: Piece.Color) -> some View {
        HStack {
            Image(systemName: model.session.isUserControlled(color) ? "person.fill" : "desktopcomputer")
                .foregroundStyle(.secondary)
            Text(model.playerName(for: color))
                .fontWeight(.semibold)
            if model.session.opponentThinking && !model.session.isUserControlled(color) && model.session.sideToMove == color {
                ProgressView().controlSize(.small)
            }
            // Captured pieces + point edge, lichess-style.
            let captured = model.capturedPieces(by: color)
            if !captured.isEmpty {
                HStack(spacing: -4) {
                    ForEach(Array(captured.enumerated()), id: \.offset) { _, kind in
                        pieceImage(kind: kind, color: color.opposite)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 15, height: 15)
                    }
                }
                .padding(.leading, 2)
            }
            let advantage = model.materialAdvantage(for: color)
            if advantage > 0 {
                Text("+\(advantage)")
                    .font(.caption.bold().monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if let clock = model.clock {
                ClockView(clock: clock, side: color.clockSide)
            }
        }
        .font(.subheadline)
        .padding(.horizontal, 4)
    }
}

struct GameOverSheet: View {
    let model: GameViewModel
    let dismissGame: () -> Void
    var onAnalyze: () -> Void = {}
    @Environment(\.dismiss) private var dismissSheet
    @Environment(DependencyContainer.self) private var container
    @State private var showPaywall = false
    @State private var showAccountSheet = false

    var body: some View {
        VStack(spacing: 16) {
            Text(model.resultHeadline)
                .font(.title2.bold())
            Text(model.resultDetail)
                .foregroundStyle(.secondary)
            Text(model.encouragement)
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            // Replay training: then vs now.
            if let original = model.trainingOriginal {
                Text(original)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }

            if let change = model.ratingChange {
                let delta = Int(change.after.rating.rounded()) - Int(change.before.rating.rounded())
                HStack(spacing: 6) {
                    Text("\(Int(change.before.rating.rounded()))")
                        .foregroundStyle(.secondary)
                    Image(systemName: "arrow.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                    Text("\(Int(change.after.rating.rounded()))")
                        .fontWeight(.semibold)
                    Text(delta >= 0 ? "+\(delta)" : "\(delta)")
                        .fontWeight(.bold)
                        .foregroundStyle(delta >= 0 ? .green : .red)
                }
                .font(.title3.monospacedDigit())
            }

            if model.preparingInsights {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Preparing your insights…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else if model.savedGameRecord != nil {
                // The hero CTA (chess.com-style): the review is the product.
                if container.entitlements.isPremium {
                    Button {
                        container.sync.track("insights_tapped", ["premium": "true"])
                        dismissSheet()
                        onAnalyze()
                    } label: {
                        Text("Game Review")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .controlSize(.large)
                    .padding(.horizontal)
                } else {
                    Button {
                        container.sync.track("insights_tapped", ["premium": "false"])
                        showPaywall = true
                    } label: {
                        Label("Game Review (Premium)", systemImage: "crown.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .controlSize(.large)
                    .padding(.horizontal)
                }
            }

            HStack(spacing: 12) {
                Button {
                    dismissSheet()
                    dismissGame()
                } label: {
                    Text("Done").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Button {
                    container.sync.track("rematch_tapped")
                    dismissSheet()
                    model.rematchRequested = true
                } label: {
                    Text("Rematch").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .padding(.horizontal)

            // Nudge account-less players to save what they just played.
            if container.sync.isConfigured, !container.sync.isSignedIn {
                Button {
                    container.sync.track("signin_nudge_tapped")
                    showAccountSheet = true
                } label: {
                    Label("Sign in to save this game", systemImage: "icloud.and.arrow.up")
                        .font(.footnote)
                }
                .padding(.top, 2)
            }
        }
        .padding(.top, 24)
        .interactiveDismissDisabled(false)
        .sheet(isPresented: $showPaywall) {
            AppPaywall(source: "game_insights")
        }
        .sheet(isPresented: $showAccountSheet) {
            AccountSheet(sync: container.sync)
        }
    }
}
