// Chessmaster — GPL-3.0-or-later
import SwiftUI
import Charts
import ChessDomain
import AnalysisKit
import BoardUI
import PaywallKit
import PersistenceKit

struct AnalysisScreen: View {
    @State private var model: AnalysisViewModel
    @Environment(DependencyContainer.self) private var container
    private let openAtWorstMoment: Bool
    private let source: String
    /// onAppear re-fires when the training cover dismisses; count one open.
    @State private var trackedOpen = false

    init(game: GameRecord, openAtWorstMoment: Bool = false, source: String = "history") {
        _model = State(initialValue: AnalysisViewModel(game: game))
        self.openAtWorstMoment = openAtWorstMoment
        self.source = source
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 12) {
                    HStack(alignment: .top, spacing: 6) {
                        EvalBarView(whitePercent: model.evalBarPercent)
                        BoardView(
                            pieces: model.currentBoardPieces,
                            orientation: model.game.playerColor == "black" ? .black : .white,
                            lastMove: model.lastMove,
                            arrows: model.boardArrows,
                            interactive: false
                        )
                    }
                    .padding(.horizontal, 8)

                    if !model.userMistakes.isEmpty {
                        mistakeReview(scrollProxy: proxy)
                    }

                    replayControls

                    analysisSection

                    coachingSection
                        .id("coaching")

                    moveList
                }
                .padding(.vertical, 8)
            }
        }
        .navigationTitle("Analysis")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                // Exports the game as standard PGN text — the recipient can
                // import it on lichess/chess.com. The AI coaching report is
                // NOT included (it lives in the recipient-less backend).
                ShareLink(
                    item: model.game.pgn,
                    preview: SharePreview("Your game (PGN) — opens on lichess & chess.com")
                ) {
                    Label("Share game (PGN)", systemImage: "square.and.arrow.up")
                }
                // Counts share-sheet opens (ShareLink has no tap callback);
                // completed exports aren't observable.
                .simultaneousGesture(TapGesture().onEnded {
                    container.sync.track("pgn_exported")
                })
            }
        }
        .onAppear {
            model.attach(container: container)
            if !trackedOpen {
                trackedOpen = true
                container.sync.track("analysis_opened", ["source": source])
            }
            if openAtWorstMoment {
                model.startGuidedReview()
            }
        }
        // The coach reads each note aloud as the player steps through
        // moments (mutable via the speaker button or Profile → Sound).
        .onChange(of: model.mistakeIndex) { speakCurrentNote() }
        .onChange(of: model.coachingReport) { _, report in
            if report != nil { speakCurrentNote() }
        }
        .onChange(of: model.trainingConfig) { _, config in
            if config != nil { container.coachVoice.stop() }
        }
        .onDisappear { container.coachVoice.stop() }
        .sheet(isPresented: $model.showPaywall) {
            AppPaywall(source: model.paywallSource)
        }
        .fullScreenCover(item: $model.trainingConfig) { config in
            NavigationStack {
                GameScreen(config: config,
                           advice: model.trainingAdvice,
                           originalOutcome: model.trainingOriginal,
                           arrows: model.trainingArrows)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("End training") { model.trainingConfig = nil }
                        }
                    }
            }
            .environment(container)
        }
    }

    /// Guided walkthrough of the player's mistakes: step through each one
    /// (board shows played-in-red vs best-in-green), finish with the coach.
    private func mistakeReview(scrollProxy: ScrollViewProxy) -> some View {
        VStack(spacing: 8) {
            VStack(spacing: 3) {
                if let index = model.mistakeIndex {
                    let mistake = model.userMistakes[index]
                    Text("Key moment \(index + 1) of \(model.userMistakes.count)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 10) {
                        Text("\(mistake.san)\(mistake.classification.glyph)")
                            .font(.headline.monospaced())
                            .foregroundStyle(.red)
                        if let best = mistake.bestMoveUCI {
                            Label(formatUCI(best), systemImage: "arrow.turn.up.right")
                                .font(.headline.monospaced())
                                .foregroundStyle(.green)
                        }
                    }
                    if let drop = model.dropFor(ply: mistake.ply), drop >= 1 {
                        Text("ply \(mistake.ply) · cost you \(Int(drop.rounded()))% win chance")
                            .font(.caption2)
                            .foregroundStyle(.orange)
                    }
                } else {
                    Text("\(model.userMistakes.count) key moments in this game")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Review them") { model.startGuidedReview() }
                        .font(.subheadline.weight(.semibold))
                }
            }

            // Big, unmistakable stepper (chess.com-style): Back small,
            // Next carries the walkthrough.
            if model.mistakeIndex != nil {
                HStack(spacing: 10) {
                    Button {
                        model.stepMistake(by: -1)
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.body.weight(.semibold))
                            .frame(width: 44)
                    }
                    .buttonStyle(.bordered)
                    .disabled((model.mistakeIndex ?? 0) == 0)

                    Button {
                        model.stepMistake(by: 1)
                    } label: {
                        Text("Next")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .disabled(model.mistakeIndex == model.userMistakes.count - 1)
                }
                .controlSize(.large)
            }

            // The coach's note for THIS mistake, inline once the report exists.
            if let index = model.mistakeIndex,
               let note = model.coachNote(forPly: model.userMistakes[index].ply) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        pieceImage(kind: .knight, color: .white)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 20, height: 20)
                            .padding(5)
                            .background(Color(red: 0xB5 / 255, green: 0x88 / 255, blue: 0x63 / 255),
                                        in: Circle())
                            .scaleEffect(container.coachVoice.speaking ? 1.1 : 1)
                            .animation(container.coachVoice.speaking
                                ? .easeInOut(duration: 0.45).repeatForever(autoreverses: true)
                                : .easeOut(duration: 0.2), value: container.coachVoice.speaking)
                        Text("Coach")
                            .font(.caption.bold())
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button {
                            container.coachVoice.enabled.toggle()
                            if container.coachVoice.enabled { speakCurrentNote() }
                        } label: {
                            Image(systemName: container.coachVoice.enabled
                                ? "speaker.wave.2.fill" : "speaker.slash.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Coach voice")
                    }
                    Text(note.whatWentWrong)
                        .font(.footnote)
                    Label(note.betterPlan, systemImage: "lightbulb")
                        .font(.footnote)
                        .foregroundStyle(.green)
                    Text(note.theme.replacingOccurrences(of: "_", with: " "))
                        .font(.caption2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.orange.opacity(0.15), in: Capsule())
                        .foregroundStyle(.orange)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 4)
            } else if model.coachingReport != nil, let index = model.mistakeIndex {
                // The report exists but skipped this (smaller) mistake:
                // fall back to the engine's preference so no flagged move
                // is ever noteless.
                if let best = model.evalFor(ply: model.userMistakes[index].ply)?.bestMoveUCI {
                    Label("Chess AI suggested \(best.prefix(2))→\(best.dropFirst(2).prefix(2)) here.",
                          systemImage: "lightbulb")
                        .font(.footnote)
                        .foregroundStyle(.green)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 4)
                }
            } else if model.coachingReport == nil, !model.coachingLoading,
                      model.mistakeIndex != nil {
                // The single coaching entry point, right where the player
                // is looking.
                Button {
                    model.requestCoaching()
                } label: {
                    Label(
                        "Ask Chess AI",
                        systemImage: "sparkles"
                    )
                    .font(.subheadline.weight(.medium))
                }
                .buttonStyle(.bordered)
            } else if model.coachingLoading {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Chess AI is reviewing…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            // Quiet inline action — discoverable here, never shouting.
            if let index = model.mistakeIndex {
                Button {
                    model.startTraining(atPly: model.userMistakes[index].ply)
                } label: {
                    Label(
                        model.isPremium ? "Replay from here" : "Replay from here (Premium)",
                        systemImage: "arrow.counterclockwise"
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }

            if model.mistakeIndex == model.userMistakes.count - 1, model.coachingReport != nil {
                Button {
                    withAnimation { scrollProxy.scrollTo("coaching", anchor: .top) }
                } label: {
                    Label("Chess AI's overall assessment", systemImage: "text.alignleft")
                        .font(.subheadline)
                }
            }
        }
        .padding(12)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }

    private func speakCurrentNote() {
        guard let index = model.mistakeIndex,
              index < model.userMistakes.count,
              let note = model.coachNote(forPly: model.userMistakes[index].ply)
        else { return }
        container.coachVoice.speak("\(note.whatWentWrong) \(note.betterPlan)")
    }

    /// "g7g6" → "g7→g6" (readable without SAN reconstruction).
    private func formatUCI(_ uci: String) -> String {
        guard uci.count >= 4 else { return uci }
        return "\(uci.prefix(2))→\(uci.dropFirst(2).prefix(2))"
    }

    @ViewBuilder
    private var coachingSection: some View {
        VStack(spacing: 10) {
            if let report = model.coachingReport {
                CoachingReportView(report: report) { ply in
                    container.sync.track("coach_note_jump", ["ply": String(ply)])
                    model.jump(to: ply)
                }
            } else if model.coachingLoading {
                VStack(spacing: 6) {
                    ProgressView()
                    Text("Chess AI is reviewing the game…")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
            } else if model.analysis != nil {
                if let message = model.coachingError {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
                // The mistake card carries the "Ask Chess AI" entry point;
                // this one only appears for games with no flagged mistakes.
                if model.userMistakes.isEmpty, model.coachingAvailable || !model.isPremium {
                    Button {
                        model.requestCoaching()
                    } label: {
                        Label(
                            "Ask Chess AI",
                            systemImage: "sparkles"
                        )
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(.horizontal)
    }

    private var replayControls: some View {
        HStack(spacing: 24) {
            Button { model.jump(to: 0) } label: { Image(systemName: "backward.end.fill") }
                .disabled(model.currentPly == 0)
            Button { model.jump(to: model.currentPly - 1) } label: { Image(systemName: "chevron.left") }
                .disabled(model.currentPly == 0)
            Text("\(model.currentPly)/\(model.plyCount)")
                .font(.footnote.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 50)
            Button { model.jump(to: model.currentPly + 1) } label: { Image(systemName: "chevron.right") }
                .disabled(model.currentPly == model.plyCount)
            Button { model.jump(to: model.plyCount) } label: { Image(systemName: "forward.end.fill") }
                .disabled(model.currentPly == model.plyCount)
        }
        .font(.title3)
        .buttonStyle(.bordered)
    }

    @ViewBuilder
    private var analysisSection: some View {
        if let analysis = model.analysis {
            VStack(spacing: 10) {
                // Live winning chances at the scrubbed position; each side's
                // whole-game accuracy rides along as the sublabel.
                HStack {
                    winCard(side: "White", winPercent: model.evalBarPercent,
                            accuracy: analysis.accuracyWhite)
                    winCard(side: "Black", winPercent: 100 - model.evalBarPercent,
                            accuracy: analysis.accuracyBlack)
                }

                evalGraph(analysis)

                if !analysis.criticalPlies.isEmpty {
                    criticalStrip(analysis)
                }
            }
            .padding(.horizontal)
        } else if model.analyzing {
            VStack(spacing: 6) {
                ProgressView(value: model.progress)
                Text("Analyzing with Stockfish…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal)
        } else {
            Button {
                model.runAnalysis()
            } label: {
                Label("Analyze game", systemImage: "waveform.path.ecg")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal)
        }
    }

    private func winCard(side: String, winPercent: Double, accuracy: Double) -> some View {
        VStack(spacing: 2) {
            Text("\(Int(winPercent.rounded()))%")
                .font(.headline.monospacedDigit())
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.15), value: Int(winPercent.rounded()))
            Text("\(side) · accuracy \(Int(accuracy.rounded()))%")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }

    private func evalGraph(_ analysis: GameAnalysis) -> some View {
        Chart {
            ForEach(analysis.plies) { ply in
                AreaMark(
                    x: .value("Ply", ply.ply),
                    y: .value("Win%", ply.winPercentWhite)
                )
                .foregroundStyle(.linearGradient(
                    colors: [.white.opacity(0.85), .white.opacity(0.4)],
                    startPoint: .top, endPoint: .bottom))
            }
            RuleMark(y: .value("Equal", 50))
                .lineStyle(StrokeStyle(lineWidth: 0.5, dash: [3]))
                .foregroundStyle(.secondary)
            if model.currentPly > 0 {
                RuleMark(x: .value("Current", model.currentPly))
                    .foregroundStyle(.blue)
            }
        }
        .chartYScale(domain: 0...100)
        .chartYAxis(.hidden)
        .frame(height: 90)
        .background(Color.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 8))
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Rectangle().fill(.clear).contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                let origin = geometry[proxy.plotFrame!].origin
                                if let ply: Int = proxy.value(atX: value.location.x - origin.x) {
                                    model.jump(to: ply)
                                }
                            }
                    )
            }
        }
    }

    private func criticalStrip(_ analysis: GameAnalysis) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(analysis.criticalPlies) { ply in
                    Button {
                        model.showMistake(atPly: ply.ply)
                    } label: {
                        VStack(spacing: 2) {
                            Text("\(ply.san)\(ply.classification.glyph)")
                                .font(.subheadline.bold().monospaced())
                            Text("move \((ply.ply + 1) / 2)")
                                .font(.caption2)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(
                            (ply.classification == .blunder ? Color.red : Color.orange).opacity(0.15),
                            in: RoundedRectangle(cornerRadius: 8)
                        )
                        .foregroundStyle(ply.classification == .blunder ? .red : .orange)
                    }
                }
            }
        }
    }

    private var moveList: some View {
        LazyVGrid(
            columns: [GridItem(.fixed(36)), GridItem(.flexible()), GridItem(.flexible())],
            alignment: .leading, spacing: 4
        ) {
            if let replay = model.replay {
                ForEach(0..<((replay.plies.count + 1) / 2), id: \.self) { row in
                    let whitePly = row * 2 + 1
                    let blackPly = row * 2 + 2
                    Text("\(row + 1).")
                        .font(.callout.monospaced())
                        .foregroundStyle(.secondary)
                    moveCell(replay: replay, ply: whitePly)
                    if blackPly <= replay.plies.count {
                        moveCell(replay: replay, ply: blackPly)
                    } else {
                        Text("")
                    }
                }
            }
        }
        .padding(.horizontal)
    }

    @ViewBuilder
    private func moveCell(replay: GameReplay, ply: Int) -> some View {
        let info = replay.plies[ply - 1]
        let eval = model.evalFor(ply: ply)
        let isFlagged = eval?.classification == .blunder || eval?.classification == .mistake
        Button {
            // Flagged moves open in "played vs best" view; others just jump.
            if isFlagged {
                model.showMistake(atPly: ply)
            } else {
                model.jump(to: ply)
            }
        } label: {
            HStack(spacing: 3) {
                Text(info.san + (eval?.classification.glyph ?? ""))
                    .font(.callout.monospaced())
                    .fontWeight(model.currentPly == ply ? .bold : .regular)
                    .foregroundStyle(glyphColor(eval?.classification))
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(cellBackground(ply: ply, classification: eval?.classification),
                        in: RoundedRectangle(cornerRadius: 4))
        }
        .buttonStyle(.plain)
    }

    /// Flagged moves stay visibly tinted in the move list; the current ply
    /// wins so scrubbing is always legible.
    private func cellBackground(ply: Int, classification: MoveClassification?) -> Color {
        if model.currentPly == ply { return Color.accentColor.opacity(0.25) }
        switch classification {
        case .blunder: return Color.red.opacity(0.14)
        case .mistake: return Color.orange.opacity(0.14)
        default: return .clear
        }
    }

    private func glyphColor(_ classification: MoveClassification?) -> Color {
        switch classification {
        case .blunder: Color(red: 0xDF / 255, green: 0x53 / 255, blue: 0x53 / 255)
        case .mistake: Color(red: 0xE6 / 255, green: 0x9F / 255, blue: 0x00 / 255)
        case .inaccuracy: Color(red: 0x56 / 255, green: 0xB4 / 255, blue: 0xE9 / 255)
        default: .primary
        }
    }
}

/// Lichess-style vertical eval bar: white fills from the bottom.
struct EvalBarView: View {
    let whitePercent: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .bottom) {
                Rectangle().fill(Color(white: 0.15))
                Rectangle()
                    .fill(Color(white: 0.95))
                    .frame(height: proxy.size.height * whitePercent / 100)
                    .animation(.easeOut(duration: 0.2), value: whitePercent)
            }
            .clipShape(RoundedRectangle(cornerRadius: 3))
        }
        .frame(width: 12)
        .aspectRatio(contentMode: .fit)
    }
}
