// Chessmaster — GPL-3.0-or-later
import SwiftUI
import BoardUI
import ChessDomain

/// Solve puzzles in a category: the opponent's setup move plays itself,
/// then the player must find the solution line. Wrong tries reset the
/// position; alternative checkmates count (lichess convention).
struct PuzzleScreen: View {
    let category: PuzzleCategory
    @Environment(DependencyContainer.self) private var container
    @Environment(LearnProgress.self) private var progress
    @State private var model: PuzzleViewModel

    init(category: PuzzleCategory) {
        self.category = category
        _model = State(initialValue: PuzzleViewModel(category: category))
    }

    var body: some View {
        VStack(spacing: 12) {
            if let puzzle = model.puzzle {
                HStack {
                    Label(category.title, systemImage: category.icon)
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("Puzzle rating \(puzzle.rating)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 4)

                BoardView(
                    pieces: model.session.boardPieces,
                    orientation: model.playerColor,
                    lastMove: model.session.lastMove,
                    checkSquare: model.session.checkedKingSquare,
                    arrows: model.hintArrows,
                    canSelect: { square in
                        guard model.canPlay,
                              let piece = model.session.piece(at: square) else { return false }
                        return piece.color == model.playerColor
                    },
                    legalTargets: { model.session.legalTargets(from: $0) },
                    onMove: { from, to in
                        _ = model.session.attemptUserMove(from: from, to: to)
                    },
                    pendingPromotion: model.session.pendingPromotion,
                    onPromote: { model.session.completePromotion(to: $0) },
                    onCancelPromotion: { model.session.cancelPromotion() }
                )

                statusBanner

                Spacer(minLength: 0)

                bottomControls
            } else {
                ContentUnavailableView("No puzzles here yet",
                                       systemImage: "puzzlepiece",
                                       description: Text("Check back after the next update."))
            }
        }
        .padding(.horizontal, 8)
        .navigationTitle(category.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            model.attach(container: container, progress: progress)
            model.startNextPuzzle()
        }
        .onDisappear { model.stop() }
    }

    @ViewBuilder
    private var statusBanner: some View {
        switch model.phase {
        case .yourTurn:
            Label("\(model.playerColor == .white ? "White" : "Black") to move — find the best move",
                  systemImage: "target")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        case .wrong:
            Label("Not quite — the position is reset, try again", systemImage: "arrow.uturn.backward")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.orange)
        case .opponentThinking:
            Label("Good! Keep going…", systemImage: "checkmark")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.green)
        case .solved:
            Label("Solved! 🎉", systemImage: "checkmark.seal.fill")
                .font(.headline)
                .foregroundStyle(.green)
        case .loading:
            ProgressView().controlSize(.small)
        }
    }

    private var bottomControls: some View {
        HStack(spacing: 10) {
            if model.phase == .solved {
                Button {
                    model.startNextPuzzle()
                } label: {
                    Text("Next Puzzle")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .controlSize(.large)
            } else {
                Button {
                    model.showHint()
                } label: {
                    Label("Hint", systemImage: "lightbulb")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(model.phase != .yourTurn)
            }
        }
        .padding(.bottom, 4)
    }
}

@Observable @MainActor
final class PuzzleViewModel {
    enum Phase { case loading, yourTurn, wrong, opponentThinking, solved }

    let category: PuzzleCategory
    private(set) var puzzle: Puzzle?
    private(set) var session: GameSession
    private(set) var playerColor: Piece.Color = .white
    private(set) var phase: Phase = .loading
    private(set) var hintArrows: [BoardArrow] = []

    private var container: DependencyContainer?
    private var progress: LearnProgress?
    private var queue: [Puzzle] = []
    private var solutionIndex = 0
    private var expectingAutoMove = false
    private var attempts = 0
    private var usedHint = false
    private var eventTask: Task<Void, Never>?

    var canPlay: Bool {
        (phase == .yourTurn || phase == .wrong) && session.sideToMove == playerColor
    }

    init(category: PuzzleCategory) {
        self.category = category
        self.session = GameSession(config: GameConfig())
    }

    func attach(container: DependencyContainer, progress: LearnProgress) {
        self.container = container
        self.progress = progress
        if queue.isEmpty {
            let all = LearnContent.puzzles(in: category)
            let unsolved = all.filter { !(progress.solvedPuzzles.contains($0.id)) }
            queue = unsolved.isEmpty ? all : unsolved
        }
    }

    func stop() { eventTask?.cancel() }

    func startNextPuzzle() {
        guard !queue.isEmpty else { puzzle = nil; return }
        puzzle = queue.removeFirst()
        solutionIndex = 0
        attempts = 0
        usedHint = false
        guard let puzzle else { return }
        // The FEN's side to move plays the setup move; the player is the
        // other side.
        let setupMover: Piece.Color = puzzle.fen.split(separator: " ").dropFirst().first == "w" ? .white : .black
        playerColor = setupMover.opposite
        container?.sync.track("puzzle_started", ["category": category.rawValue, "id": puzzle.id])
        loadPosition(replaying: [])
        phase = .loading
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            self?.playAutoMove()
        }
    }

    func showHint() {
        guard let puzzle, solutionIndex < puzzle.moves.count,
              let move = OpponentMove(uci: puzzle.moves[solutionIndex]) else { return }
        usedHint = true
        hintArrows = [BoardArrow(from: move.from, to: move.to, color: .green)]
        container?.sync.track("puzzle_hint_used", ["category": category.rawValue])
    }

    /// (Re)builds the session at fen + the solved prefix and re-subscribes.
    private func loadPosition(replaying prefix: [String]) {
        guard let puzzle else { return }
        eventTask?.cancel()
        hintArrows = []
        let newSession = GameSession(config: GameConfig(startFEN: puzzle.fen, opponent: .humanLocal))
        if !prefix.isEmpty { newSession.replay(movesUCI: prefix) }
        session = newSession
        newSession.start()
        eventTask = Task { @MainActor [weak self, weak newSession] in
            guard let events = newSession?.events else { return }
            for await event in events {
                guard let self, self.session === newSession else { return }
                if case .movePlayed = event { self.handleMovePlayed() }
            }
        }
    }

    private func playAutoMove() {
        guard let puzzle, solutionIndex < puzzle.moves.count,
              let move = OpponentMove(uci: puzzle.moves[solutionIndex]) else { return }
        expectingAutoMove = true
        let outcome = session.attemptUserMove(from: move.from, to: move.to)
        if outcome == .needsPromotion {
            session.completePromotion(to: move.promotion ?? .queen)
        } else if outcome == .illegal {
            // Malformed puzzle (shouldn't happen with the curated set):
            // skip it rather than trap the player.
            expectingAutoMove = false
            startNextPuzzle()
        }
    }

    private func handleMovePlayed() {
        guard let puzzle else { return }
        if expectingAutoMove {
            expectingAutoMove = false
            solutionIndex += 1
            phase = .yourTurn
            return
        }
        guard let played = session.moveHistory.last?.uci else { return }
        let expected = solutionIndex < puzzle.moves.count ? puzzle.moves[solutionIndex] : ""
        var playerWon = false
        if case .finished(let result) = session.status,
           case .win(let winner, _) = result, winner == playerColor {
            playerWon = true   // alternative mates count, lichess-style
        }
        if played == expected || playerWon {
            solutionIndex += 1
            hintArrows = []
            if solutionIndex >= puzzle.moves.count || playerWon {
                phase = .solved
                progress?.markSolved(puzzle.id)
                container?.sync.track("puzzle_solved", [
                    "category": category.rawValue, "id": puzzle.id,
                    "attempts": String(attempts + 1),
                    "hint": usedHint ? "true" : "false",
                ])
            } else {
                phase = .opponentThinking
                Task { @MainActor [weak self] in
                    try? await Task.sleep(for: .milliseconds(450))
                    self?.playAutoMove()
                }
            }
        } else {
            attempts += 1
            phase = .wrong
            let prefix = Array(puzzle.moves.prefix(solutionIndex))
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(700))
                self?.loadPosition(replaying: prefix)
            }
        }
    }
}
