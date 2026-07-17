// Chessmaster — GPL-3.0-or-later
import SwiftUI
import BoardUI
import ChessDomain

/// A guided lesson: one instruction per position; any accepted move
/// advances, anything else resets the step.
struct LessonScreen: View {
    let lesson: Lesson
    @Environment(DependencyContainer.self) private var container
    @Environment(LearnProgress.self) private var progress
    @Environment(\.dismiss) private var dismiss
    @State private var model: LessonViewModel

    init(lesson: Lesson) {
        self.lesson = lesson
        _model = State(initialValue: LessonViewModel(lesson: lesson))
    }

    var body: some View {
        VStack(spacing: 12) {
            ProgressView(value: Double(model.stepIndex), total: Double(lesson.steps.count))
                .padding(.horizontal, 4)

            banner

            BoardView(
                pieces: model.session.boardPieces,
                orientation: .white,
                lastMove: model.session.lastMove,
                checkSquare: model.session.checkedKingSquare,
                canSelect: { square in
                    guard model.phase != .finished,
                          let piece = model.session.piece(at: square) else { return false }
                    return piece.color == model.session.sideToMove
                },
                legalTargets: { model.session.legalTargets(from: $0) },
                onMove: { from, to in
                    _ = model.session.attemptUserMove(from: from, to: to)
                }
            )

            Spacer(minLength: 0)

            if model.phase == .finished {
                Button {
                    dismiss()
                } label: {
                    Text("Done")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .controlSize(.large)
                .padding(.bottom, 4)
            }
        }
        .padding(.horizontal, 8)
        .navigationTitle(lesson.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            model.attach(container: container, progress: progress)
            model.begin()
        }
        .onDisappear { model.stop() }
    }

    @ViewBuilder
    private var banner: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch model.phase {
            case .prompt:
                Label(model.currentPrompt, systemImage: "graduationcap.fill")
                    .font(.subheadline)
            case .wrong:
                Label("Not quite — try again. \(model.currentPrompt)", systemImage: "arrow.uturn.backward")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            case .success:
                Label(model.successText, systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.green)
            case .finished:
                Label("Lesson complete! 🎓", systemImage: "checkmark.seal.fill")
                    .font(.headline)
                    .foregroundStyle(.green)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}

@Observable @MainActor
final class LessonViewModel {
    enum Phase { case prompt, wrong, success, finished }

    let lesson: Lesson
    private(set) var session: GameSession
    private(set) var stepIndex = 0
    private(set) var phase: Phase = .prompt
    private(set) var successText = ""

    private var container: DependencyContainer?
    private var progress: LearnProgress?
    private var eventTask: Task<Void, Never>?

    var currentPrompt: String {
        stepIndex < lesson.steps.count ? lesson.steps[stepIndex].prompt : ""
    }

    init(lesson: Lesson) {
        self.lesson = lesson
        self.session = GameSession(config: GameConfig())
    }

    func attach(container: DependencyContainer, progress: LearnProgress) {
        self.container = container
        self.progress = progress
    }

    func begin() {
        container?.sync.track("lesson_started", ["id": lesson.id])
        loadStep()
    }

    func stop() { eventTask?.cancel() }

    private func loadStep() {
        guard stepIndex < lesson.steps.count else {
            phase = .finished
            progress?.markCompleted(lesson.id)
            container?.sync.track("lesson_completed", ["id": lesson.id])
            return
        }
        eventTask?.cancel()
        let step = lesson.steps[stepIndex]
        let newSession = GameSession(config: GameConfig(startFEN: step.fen, opponent: .humanLocal))
        session = newSession
        newSession.start()
        phase = phase == .wrong ? .wrong : .prompt
        eventTask = Task { @MainActor [weak self, weak newSession] in
            guard let events = newSession?.events else { return }
            for await event in events {
                guard let self, self.session === newSession else { return }
                if case .movePlayed = event { self.handleMovePlayed() }
            }
        }
    }

    private func handleMovePlayed() {
        guard stepIndex < lesson.steps.count,
              let played = session.moveHistory.last?.uci else { return }
        let step = lesson.steps[stepIndex]
        if step.expected.contains(String(played.prefix(4))) {
            successText = step.success
            phase = .success
            stepIndex += 1
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(1400))
                guard let self, self.phase == .success else { return }
                self.phase = .prompt
                self.loadStep()
            }
        } else {
            phase = .wrong
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(700))
                self?.loadStep()
            }
        }
    }
}
