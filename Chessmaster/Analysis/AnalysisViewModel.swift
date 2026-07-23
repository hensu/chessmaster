// Chessmaster — GPL-3.0-or-later
import SwiftUI
import ChessDomain
import AnalysisKit
import BoardUI
import PersistenceKit

@Observable @MainActor
final class AnalysisViewModel {
    let game: GameRecord
    let replay: GameReplay?

    /// 0 = starting position, N = after ply N.
    var currentPly = 0
    private(set) var analysis: GameAnalysis?
    private(set) var analyzing = false
    private(set) var progress: Double = 0

    // Premium coaching state.
    private(set) var coachingReport: CoachingReport?
    private(set) var coachingLoading = false
    private(set) var coachingError: String?
    var showPaywall = false
    /// Which premium gate raised the paywall, for funnel attribution.
    private(set) var paywallSource = "analysis"
    /// Set to launch a retry-training game from a critical position.
    var trainingConfig: GameConfig?
    /// Coach's guidance shown during the replay, and what happened in the
    /// original game — for the then-vs-now comparison at the end.
    private(set) var trainingAdvice: String?
    private(set) var trainingOriginal: String?
    /// Arrows for the retry board: red = played move, green = suggestion.
    private(set) var trainingArrows: [BoardArrow] = []

    private var container: DependencyContainer?

    init(game: GameRecord) {
        self.game = game
        self.replay = GameReplay(pgn: game.pgn)
        if let json = game.analysisJSON,
           let cached = try? JSONDecoder().decode(GameAnalysis.self, from: Data(json.utf8)) {
            analysis = cached
        }
        // A previously generated coaching report loads from the local cache
        // — reopening a reviewed game never regenerates.
        if let json = game.coachingJSON,
           let cached = try? JSONDecoder().decode(CoachingReport.self, from: Data(json.utf8)) {
            coachingReport = cached
        }
        currentPly = replay?.plies.count ?? 0
    }

    func attach(container: DependencyContainer) {
        self.container = container
        restoreRemoteReportIfNeeded()
    }

    /// Reinstall / other device: the report exists server-side but not in
    /// the local cache — restore it silently (read-only; never generates).
    private func restoreRemoteReportIfNeeded() {
        guard coachingReport == nil, let container,
              container.sync.isSignedIn, game.opponentType == "engine" else { return }
        let gameID = game.id
        Task { @MainActor [weak self] in
            guard let data = await self?.container?.sync.fetchCoachingReport(gameID: gameID),
                  let report = try? JSONDecoder().decode(CoachingReport.self, from: data)
            else { return }
            self?.coachingReport = report
            self?.persistReport(report)
        }
    }

    private func persistReport(_ report: CoachingReport) {
        guard let data = try? JSONEncoder().encode(report),
              let json = String(data: data, encoding: .utf8) else { return }
        try? container?.games.updateCoaching(id: game.id, coachingJSON: json)
    }

    var isPremium: Bool { container?.entitlements.isPremium ?? false }
    var coachingAvailable: Bool { container?.sync.isConfigured ?? false }

    /// Requests the AI coaching report. Free players get one per week —
    /// the server enforces the limit, so everyone may ask.
    func requestCoaching() {
        guard let container else { return }
        guard !coachingLoading, coachingReport == nil else { return }
        container.sync.track("coaching_requested")
        coachingLoading = true
        coachingError = nil
        let gameID = game.id.lowercased()
        Task { @MainActor [weak self] in
            do {
                guard let sync = self?.container?.sync else { return }
                if !sync.isSignedIn {
                    await sync.signInAnonymously()
                }
                await sync.pushPending()   // the game must exist server-side
                let response: CoachingResponse = try await sync.invokeFunction(
                    "generate-coaching-report",
                    body: ["game_id": gameID]
                )
                self?.coachingReport = response.report.report
                if let report = response.report.report {
                    self?.persistReport(report)
                } else {
                    self?.coachingError = "The coach couldn't analyze this game."
                }
            } catch {
                if self?.isPremium == false {
                    // Free weekly review already used (or backend gate):
                    // this is the upgrade moment.
                    self?.coachingError = "You've used this week's free review — upgrade for unlimited coaching."
                    self?.paywallSource = "coaching"
                    self?.showPaywall = true
                } else {
                    // A transient backend/model hiccup: the failed report is
                    // retryable, so invite another tap rather than a dead end.
                    self?.coachingError = "The coach is taking a moment — tap to try again."
                }
            }
            self?.coachingLoading = false
        }
    }

    /// Starts a retry-training game from the position before the given ply
    /// (premium). The player replays their own critical moment vs Stockfish.
    func startTraining(atPly ply: Int) {
        guard let container else { return }
        guard container.entitlements.isPremium else {
            paywallSource = "training"
            showPaywall = true
            return
        }
        guard let replay else { return }
        let fen = replay.fenBefore(ply: ply)

        // Arm the replay with the coach's advice and the original outcome.
        let playedSAN = (ply - 1 < replay.plies.count) ? replay.plies[ply - 1].san : ""
        if let note = coachNote(forPly: ply) {
            trainingAdvice = "You played \(playedSAN). Chess AI suggests \(note.betterMove): \(note.betterPlan)"
        } else if let best = evalFor(ply: ply)?.bestMoveUCI {
            trainingAdvice = "You played \(playedSAN). Chess AI suggested \(best.prefix(2))→\(best.dropFirst(2).prefix(2))."
        } else {
            trainingAdvice = playedSAN.isEmpty ? nil : "You played \(playedSAN) here — find a better move."
        }
        // Board arrows for the retry: red = the move you played,
        // green = Chess AI's suggestion. Shown until the first new move.
        var arrows: [BoardArrow] = []
        if ply - 1 < replay.plies.count, let played = OpponentMove(uci: replay.plies[ply - 1].uci) {
            arrows.append(BoardArrow(from: played.from, to: played.to, color: .red))
        }
        if let bestUCI = evalFor(ply: ply)?.bestMoveUCI, let best = OpponentMove(uci: bestUCI) {
            arrows.append(BoardArrow(from: best.from, to: best.to, color: .green))
        }
        trainingArrows = arrows
        let userIsWhite = game.playerColor != "black"
        let userWon = (game.result == "white_win" && userIsWhite)
            || (game.result == "black_win" && !userIsWhite)
        let outcome = game.result == "draw" ? "drew" : (userWon ? "won" : "went on to lose")
        var original = "Original game: you played \(playedSAN) here"
        if let drop = dropFor(ply: ply), drop >= 1 {
            original += " (−\(Int(drop.rounded()))% win chance)"
        }
        original += " and \(outcome)."
        trainingOriginal = original
        let rating = container.ratings.rating(
            for: TimeControl.Category(rawValue: game.ratingCategory ?? "") ?? .blitz
        ).rating
        let level = max(1, min(10, Int((rating / 300).rounded())))
        trainingConfig = GameConfig(
            startFEN: fen,
            playerColor: game.playerColor == "black" ? .black : .white,
            timeControl: nil,
            opponent: .engine(level: level)
        )
        container.sync.track("training_started", ["from_ply": String(ply), "level": String(level)])
    }

    var plyCount: Int { replay?.plies.count ?? 0 }

    var currentFEN: String {
        guard let replay else { return game.finalFEN }
        return currentPly == 0 ? replay.startFEN : replay.plies[currentPly - 1].fenAfter
    }

    /// Stable-identity pieces for the current ply, so stepping animates
    /// the moved piece instead of refreshing the whole board. Falls back
    /// to FEN-derived pieces when the PGN failed to parse.
    var currentBoardPieces: [BoardPiece] {
        replay?.boardPieces(atPly: currentPly) ?? boardPieces(fromFEN: game.finalFEN)
    }

    var lastMove: (from: Square, to: Square)? {
        guard let replay, currentPly > 0 else { return nil }
        let uci = replay.plies[currentPly - 1].uci
        guard let move = OpponentMove(uci: uci) else { return nil }
        return (move.from, move.to)
    }

    /// White's win probability at the current ply (50 when unanalyzed).
    var evalBarPercent: Double {
        guard let analysis else { return 50 }
        if currentPly == 0 { return 50 }
        return analysis.plies[min(currentPly, analysis.plies.count) - 1].winPercentWhite
    }

    func evalFor(ply: Int) -> PlyEval? {
        guard let analysis, ply >= 1, ply <= analysis.plies.count else { return nil }
        return analysis.plies[ply - 1]
    }

    func jump(to ply: Int) {
        currentPly = max(0, min(plyCount, ply))
        // Keep the guided review in step with wherever the board actually is.
        mistakeIndex = userMistakes.firstIndex { $0.ply == currentPly + 1 }
    }

    /// Positions the board just before a flagged move so the arrows show
    /// "you played this (red), better was this (green)".
    func showMistake(atPly ply: Int) {
        jump(to: ply - 1)
    }

    /// Jumps to the game's worst moment (biggest classification first).
    func jumpToWorstMoment() {
        guard let analysis else { return }
        let worst = analysis.plies.first { $0.classification == .blunder }
            ?? analysis.criticalPlies.first
        if let worst { showMistake(atPly: worst.ply) }
    }

    // MARK: - Guided mistake review

    /// The review list: the player's flagged moves PLUS their biggest
    /// win-probability losses, so a lost game always has a real review even
    /// when it was lost by drift rather than one blunder. Game order.
    var userMistakes: [PlyEval] {
        guard let analysis else { return [] }
        let userIsWhite = game.playerColor != "black"
        let (dropByPly, reviewableByPly) = reviewSignals()

        let userPlies = analysis.plies.filter { $0.moverIsWhite == userIsWhite }
        var chosen = Set(userPlies.filter { $0.classification != .good }.map(\.ply))
        let target = 5
        if chosen.count < target {
            let fallback = userPlies
                .filter { !chosen.contains($0.ply) }
                .filter { reviewableByPly[$0.ply] == true && (dropByPly[$0.ply] ?? 0) >= 2 }
                .sorted { (dropByPly[$0.ply] ?? 0) > (dropByPly[$1.ply] ?? 0) }
            for e in fallback.prefix(target - chosen.count) { chosen.insert(e.ply) }
        }
        return userPlies.filter { chosen.contains($0.ply) }
    }

    /// Win% the move at `ply` cost the player ("−5% win chance" captions).
    func dropFor(ply: Int) -> Double? { reviewSignals().drops[ply] }

    /// Per-user-ply win% drop and whether the position was still contested.
    private func reviewSignals() -> (drops: [Int: Double], reviewable: [Int: Bool]) {
        guard let analysis else { return ([:], [:]) }
        let userIsWhite = game.playerColor != "black"
        var drops: [Int: Double] = [:]
        var reviewable: [Int: Bool] = [:]
        var prevWhite = 50.0
        for e in analysis.plies {
            if e.moverIsWhite == userIsWhite {
                let before = userIsWhite ? prevWhite : 100 - prevWhite
                let after = userIsWhite ? e.winPercentWhite : 100 - e.winPercentWhite
                drops[e.ply] = max(0, before - after)
                reviewable[e.ply] = before >= 10 && before <= 95
            }
            prevWhite = e.winPercentWhite
        }
        return (drops, reviewable)
    }

    /// The coach's note for a specific mistake, once the report exists.
    func coachNote(forPly ply: Int) -> CoachingReport.KeyMoment? {
        coachingReport?.keyMoments.first { $0.ply == ply }
    }

    /// Current step in the guided review, nil when not on a mistake.
    var mistakeIndex: Int?

    /// Starts the walkthrough at the player's first mistake.
    func startGuidedReview() {
        container?.sync.track("mistake_review_started")
        guard let first = userMistakes.first else {
            jumpToWorstMoment()
            return
        }
        showMistake(atPly: first.ply)
    }

    func stepMistake(by delta: Int) {
        guard let index = mistakeIndex else {
            startGuidedReview()
            return
        }
        let next = index + delta
        guard userMistakes.indices.contains(next) else { return }
        showMistake(atPly: userMistakes[next].ply)
    }

    /// Arrows for the current position: when the *next* ply is a flagged
    /// move, draw the played move in red and the engine's move in green.
    var boardArrows: [BoardArrow] {
        guard let replay,
              let eval = evalFor(ply: currentPly + 1),
              userMistakes.contains(where: { $0.ply == eval.ply })
        else { return [] }
        var arrows: [BoardArrow] = []
        if let played = OpponentMove(uci: replay.plies[currentPly].uci) {
            arrows.append(BoardArrow(from: played.from, to: played.to, color: .red))
        }
        if let bestUCI = eval.bestMoveUCI, let best = OpponentMove(uci: bestUCI) {
            arrows.append(BoardArrow(from: best.from, to: best.to, color: .green))
        }
        return arrows
    }

    func runAnalysis(movetimeMs: Int = 120) {
        guard !analyzing, analysis == nil,
              let big = StockfishNets.big, let small = StockfishNets.small else { return }
        analyzing = true
        progress = 0
        let pgn = game.pgn
        let gameID = game.id
        let progressHandler: @Sendable (Int, Int) -> Void = { [weak self] done, total in
            Task { @MainActor in
                self?.progress = Double(done) / Double(total)
            }
        }
        Task { @MainActor [weak self] in
            let analyzer = GameAnalyzer(evalFileBig: big, evalFileSmall: small)
            do {
                let result = try await analyzer.analyze(
                    pgn: pgn, movetimeMs: movetimeMs, onProgress: progressHandler)
                guard let self else { return }
                self.analysis = result
                self.analyzing = false
                if let data = try? JSONEncoder().encode(result) {
                    try? self.container?.games.updateAnalysis(
                        id: gameID,
                        analysisJSON: String(decoding: data, as: UTF8.self)
                    )
                }
            } catch {
                self?.analyzing = false
            }
        }
    }
}
