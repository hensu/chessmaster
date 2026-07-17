// Chessmaster — GPL-3.0-or-later
import SwiftUI
import BoardUI
import ChessDomain
import ClockKit
import AudioKitChess
import AnalysisKit
import EngineKit
import RatingKit
import PersistenceKit

@Observable @MainActor
final class GameViewModel {
    private(set) var session: GameSession
    let clock: ChessClock?
    var orientation: Piece.Color
    var showResignConfirm = false
    var showGameOver = false
    /// Replay-training context: coach's advice pinned during play, and the
    /// original game's outcome for the then-vs-now comparison at the end.
    var trainingAdvice: String?
    var trainingOriginal: String?
    /// Retry-training board arrows (red = played, green = suggested).
    var trainingArrows: [BoardArrow] = []
    /// Aborts idle games: no first move from the player within 20 seconds.
    private var firstMoveAbortTask: Task<Void, Never>?
    var rematchRequested = false
    /// Set after a rated (engine) game ends.
    private(set) var ratingChange: (before: Glicko2Rating, after: Glicko2Rating)?
    /// Set once the finished game has been persisted (drives "Analyze").
    private(set) var savedGameRecord: GameRecord?
    /// Post-game insights: set once the automatic blunder-check finishes,
    /// with the record carrying its fresh analysisJSON.
    private(set) var postGameInsights: GameRecord?
    private(set) var preparingInsights = false

    private var container: DependencyContainer?
    private var eventTask: Task<Void, Never>?
    private var lowTimeTask: Task<Void, Never>?
    private let gameID = UUID().uuidString
    private let startedAt = Date.now
    /// How this game began, for analytics: fresh start, resumed save, or
    /// mistake-retry training.
    private let startSource: String

    private var audio: ChessAudioPlayer? { container?.audio }

    /// Pass `resume` to continue a previously saved in-progress game.
    init(config: GameConfig, resume: InProgressGameRecord? = nil) {
        let session = GameSession(config: config)
        self.session = session
        self.orientation = config.playerColor
        self.startSource = resume != nil ? "resume"
            : config.startFEN != nil ? "training" : "new"

        if let resume, !resume.movesUCI.isEmpty {
            session.replay(movesUCI: resume.movesUCI.split(separator: " ").map(String.init))
        }

        if let tc = config.timeControl {
            var restored: [ClockSide: Duration] = [:]
            if let ms = resume?.whiteRemainingMs { restored[.white] = .milliseconds(ms) }
            if let ms = resume?.blackRemainingMs { restored[.black] = .milliseconds(ms) }
            let clock = ChessClock(initial: tc.initial, increment: tc.increment, restoredRemaining: restored)
            self.clock = clock
            session.clockSnapshot = { [weak clock] color in
                clock?.remaining(color.clockSide)
            }
            clock.onFlag = { [weak session] side in
                session?.flag(side == .white ? .white : .black)
            }
        } else {
            self.clock = nil
        }

        if case .engine(let level) = config.opponent {
            session.opponentProvider = EngineOpponent(
                level: level,
                blunderProbability: config.engineBlunderProbability
            )
        }
    }

    func attach(container: DependencyContainer) {
        self.container = container
    }

    func start() {
        guard session.status == .idle else { return }
        eventTask = Task { [weak self] in
            guard let events = self?.session.events else { return }
            for await event in events {
                guard let self else { return }
                self.handle(event)
            }
        }
        session.start()

        // Lichess-style abort: a game nobody starts playing shouldn't count.
        // Training replays are exempt (thinking there is the whole point).
        if !isTraining {
            firstMoveAbortTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(20))
                guard let self, self.session.status.isPlaying else { return }
                let userMoved = self.session.moveHistory.contains {
                    self.session.isUserControlled($0.mover)
                }
                if !userMoved { self.session.abort() }
            }
        }
    }

    private func handle(_ event: GameEvent) {
        switch event {
        case .gameStarted:
            audio?.play(.gameStart)
            clock?.start(session.sideToMove.clockSide)
            startLowTimeWatcher()
            container?.sync.track("game_start", analyticsGameProps)
        case .movePlayed(let move):
            clock?.press(move.mover.clockSide)
            audio?.play(soundEffect(for: move))
            if move.isCapture || move.givesCheck {
                UIImpactFeedbackGenerator(style: move.givesCheck ? .heavy : .medium)
                    .impactOccurred()
            }
            saveInProgressSnapshot()
            if session.isUserControlled(move.mover) {
                firstMoveAbortTask?.cancel()
            }
        case .gameEnded(let result):
            clock?.stop()
            lowTimeTask?.cancel()
            firstMoveAbortTask?.cancel()
            audio?.play(.gameEnd)
            try? container?.inProgress.clear()
            if result != .aborted {
                applyRating(for: result)
                saveFinishedGame(result: result)
                var props = analyticsGameProps
                props["result"] = normalizedResult(result)
                props["termination"] = result.terminationString
                props["rated"] = ratingChange != nil ? "true" : "false"
                props["moves"] = String(session.moveHistory.count)
                props["duration_s"] = String(Int(Date.now.timeIntervalSince(startedAt)))
                container?.sync.track("game_finished", props)
                if let games = try? container?.games.recentGames() {
                    StreakNotifier.refresh(games: games)
                }
                // Decisive games go straight to the analysis review; the
                // sheet only appears when no auto-analysis is coming.
                showGameOver = !preparingInsights
            }
        }
    }

    private func saveInProgressSnapshot() {
        guard session.status.isPlaying, let container else { return }
        try? container.inProgress.save(makeInProgressRecord(session: session, clock: clock))
    }

    private func saveFinishedGame(result: GameResult) {
        guard let container else { return }
        let config = session.config
        let record = GameRecord(
            id: gameID,
            startedAt: startedAt,
            endedAt: .now,
            opponentType: config.opponentTypeString,
            engineLevel: config.engineLevelValue,
            playerColor: config.playerColor == .black ? "black" : "white",
            tcInitialSeconds: config.timeControl.map { Int($0.initial.components.seconds) },
            tcIncrementSeconds: config.timeControl.map { Int($0.increment.components.seconds) },
            result: result.resultString,
            termination: result.terminationString,
            pgn: session.pgn,
            finalFEN: session.currentFEN,
            ratingCategory: ratingChange != nil ? (config.timeControl?.category ?? .classical).rawValue : nil,
            ratingBefore: ratingChange?.before.rating,
            ratingAfter: ratingChange?.after.rating
        )
        try? container.games.save(record)
        savedGameRecord = record
        // History row references the game row — must come after the save.
        if ratingChange != nil {
            container.ratings.recordHistory(
                gameID: gameID,
                category: config.timeControl?.category ?? .classical
            )
        }
        // Signed-in users sync every finished game, free or premium.
        if container.sync.isSignedIn {
            Task { [weak container] in
                await container?.sync.pushPending()
            }
        }
        runPostGameAnalysis(record: record)
    }

    /// Premium: runs the on-device blunder-check automatically after every
    /// real game, so insights are ready to show without digging for them.
    private func runPostGameAnalysis(record: GameRecord) {
        guard container?.entitlements.isPremium == true,
              !isTraining,
              session.moveHistory.count >= 4,
              let big = StockfishNets.big, let small = StockfishNets.small
        else { return }
        preparingInsights = true
        let pgn = record.pgn
        Task { @MainActor [weak self] in
            defer { self?.preparingInsights = false }
            let analyzer = GameAnalyzer(evalFileBig: big, evalFileSmall: small)
            guard let analysis = try? await analyzer.analyze(pgn: pgn, movetimeMs: 250),
                  let data = try? JSONEncoder().encode(analysis)
            else {
                self?.showGameOver = true   // analysis failed: fall back
                return
            }
            guard let self, let container = self.container else { return }
            try? container.games.updateAnalysis(
                id: record.id,
                analysisJSON: String(decoding: data, as: UTF8.self)
            )
            self.postGameInsights = try? container.games.game(id: record.id)
        }
    }

    /// Engine games are rated; the level's calibrated estimate stands in
    /// as the opponent's rating (lichess model, one period per game).
    private func applyRating(for result: GameResult) {
        // Training games (custom start position) are unrated.
        guard session.config.startFEN == nil else { return }
        guard case .engine(let level) = session.config.opponent,
              let ratings = container?.ratings else { return }
        let score: Double
        switch result {
        case .win(let color, _): score = session.isUserControlled(color) ? 1 : 0
        case .draw: score = 0.5
        case .aborted: return
        }
        let category = session.config.timeControl?.category ?? .classical
        ratingChange = ratings.applyGame(
            category: category,
            opponentRating: Double(StrengthLevel.level(level).ratingEstimate),
            score: score
        )
    }

    private func soundEffect(for move: PlayedMove) -> SoundEffect {
        if move.givesCheck { return .check }
        if move.isPromotion { return .promote }
        if move.isCastle { return .castle }
        if move.isCapture { return .capture }
        return .move
    }

    /// Plays a single warning tick when the user's clock first dips under 10s.
    private func startLowTimeWatcher() {
        guard let clock else { return }
        let userSide = session.config.playerColor.clockSide
        lowTimeTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, self.session.status.isPlaying else { return }
                if clock.remaining(userSide) < .seconds(10), clock.running == userSide {
                    self.audio?.play(.lowTime)
                    return
                }
            }
        }
    }

    var title: String {
        if let tc = session.config.timeControl {
            return "\(tc.label) \(tc.category.rawValue.capitalized)"
        }
        return "Casual"
    }

    func playerName(for color: Piece.Color) -> String {
        switch session.config.opponent {
        case .humanLocal:
            return color == .white ? "White" : "Black"
        case .engine(let level):
            return session.isUserControlled(color)
                ? "You"
                : "Level \(level) · \(StrengthLevel.level(level).displayName)"
        }
    }

    /// True when this is a retry-training game (custom start vs engine).
    var isTraining: Bool {
        session.config.startFEN != nil && session.config.opponent != .humanLocal
    }

    // MARK: - Material

    private static let pieceValues: [Piece.Kind: Int] = [
        .pawn: 1, .knight: 3, .bishop: 3, .rook: 5, .queen: 9,
    ]
    private static let initialCounts: [Piece.Kind: Int] = [
        .pawn: 8, .knight: 2, .bishop: 2, .rook: 2, .queen: 1,
    ]

    /// Opponent pieces this side has won so far, biggest first.
    func capturedPieces(by color: Piece.Color) -> [Piece.Kind] {
        var remaining: [Piece.Kind: Int] = [:]
        for piece in session.boardPieces where piece.color == color.opposite {
            remaining[piece.kind, default: 0] += 1
        }
        return Self.initialCounts
            .sorted { Self.pieceValues[$0.key] ?? 0 > Self.pieceValues[$1.key] ?? 0 }
            .flatMap { kind, initial in
                Array(repeating: kind, count: max(0, initial - remaining[kind, default: 0]))
            }
    }

    /// Point edge for this side from the pieces still on the board
    /// (positive = ahead; promotion-safe, unlike counting captures).
    func materialAdvantage(for color: Piece.Color) -> Int {
        session.boardPieces.reduce(0) { total, piece in
            let value = Self.pieceValues[piece.kind] ?? 0
            return total + (piece.color == color ? value : -value)
        }
    }

    /// Shared dimensions for game_start / game_finished.
    private var analyticsGameProps: [String: String] {
        let config = session.config
        return [
            "source": startSource,
            "time_class": config.timeControl?.category.rawValue ?? "untimed",
            "time_control": config.timeControl?.label ?? "untimed",
            "level": config.engineLevelValue.map(String.init) ?? "human",
            "level_mode": UserDefaults.standard.string(forKey: "play.levelMode") ?? "auto",
            "color": config.playerColor == .black ? "black" : "white",
            "rated": config.startFEN == nil && config.opponent != .humanLocal ? "true" : "false",
            "training": isTraining ? "true" : "false",
        ]
    }

    /// "win"/"loss"/"draw" from the player's side for engine games;
    /// hot-seat games keep the absolute result.
    private func normalizedResult(_ result: GameResult) -> String {
        let raw = result.resultString
        guard session.config.opponent != .humanLocal else { return raw }
        switch raw {
        case "whiteWin": return session.config.playerColor == .white ? "win" : "loss"
        case "blackWin": return session.config.playerColor == .black ? "win" : "loss"
        default: return raw
        }
    }

    /// Training verdict from the game result: winning the retry (or holding
    /// a draw) counts as applying the lesson.
    var trainingPassed: Bool? {
        guard isTraining, case .finished(let result) = session.status else { return nil }
        switch result {
        case .win(let color, _): return session.isUserControlled(color)
        case .draw: return true
        case .aborted: return nil
        }
    }

    /// A human word with the result — praise for wins, a lift for losses.
    var encouragement: String {
        let headline = resultHeadline
        let pool: [String]
        if headline.contains("won") || headline.contains("passed") {
            pool = [
                "Beautiful chess — you outplayed the AI. 👏",
                "Clean win. Your training is paying off.",
                "That's how it's done. Keep the momentum!",
            ]
        } else if headline.contains("lost") || headline.contains("Not this time") {
            pool = [
                "Good fight. One lesson from this game and it was worth it.",
                "Every loss is a lesson — see where it turned.",
                "Chess AI is relentless — you're getting closer every game.",
            ]
        } else {
            pool = [
                "Holding the AI to a draw is solid chess.",
                "A hard-earned half point — nice defending.",
            ]
        }
        return pool[abs(headline.hashValue % pool.count)]
    }

    var resultHeadline: String {
        guard case .finished(let result) = session.status else { return "" }
        if let passed = trainingPassed {
            return passed ? "Training passed! 🎉" : "Not this time"
        }
        switch result {
        case .win(let color, _):
            if session.config.opponent == .humanLocal {
                return color == .white ? "White wins" : "Black wins"
            }
            return session.isUserControlled(color) ? "You won!" : "You lost"
        case .draw: return "Draw"
        case .aborted: return "Game aborted"
        }
    }

    var resultDetail: String {
        guard case .finished(let result) = session.status else { return "" }
        switch result {
        case .win(_, .checkmate): return "by checkmate"
        case .win(_, .resignation): return "by resignation"
        case .win(_, .timeout): return "on time"
        case .draw(.stalemate): return "by stalemate"
        case .draw(.repetition): return "by threefold repetition"
        case .draw(.fiftyMoves): return "by the fifty-move rule"
        case .draw(.insufficientMaterial): return "by insufficient material"
        case .draw(.agreement): return "by agreement"
        case .aborted: return ""
        }
    }
}

extension Piece.Color {
    var clockSide: ClockSide { self == .white ? .white : .black }
}
