// ChessDomain — Chessmaster
// GPL-3.0-or-later

import ChessKit
import Foundation
import Observation

/// The outcome of attempting a move on the board.
public enum MoveOutcome: Sendable, Equatable {
    case played
    /// Pawn reached the last rank; call `completePromotion(to:)`.
    case needsPromotion
    case illegal
}

/// A piece with an identity that is stable across moves, so the board UI
/// can animate a piece sliding rather than tearing down and recreating views.
public struct BoardPiece: Identifiable, Hashable, Sendable {
    public let id: Int
    public var kind: Piece.Kind
    public let color: Piece.Color
    public var square: Square

    public init(id: Int, kind: Piece.Kind, color: Piece.Color, square: Square) {
        self.id = id
        self.kind = kind
        self.color = color
        self.square = square
    }
}

/// Central game state machine. Owns the live board (rules), the game record
/// (PGN), the move history, and drives the opponent. UI observes it; audio
/// and persistence subscribe to `events`.
@Observable @MainActor
public final class GameSession {
    public let config: GameConfig

    public private(set) var status: GameStatus = .idle
    public private(set) var boardPieces: [BoardPiece] = []
    public private(set) var moveHistory: [PlayedMove] = []
    public private(set) var lastMove: (from: Square, to: Square)?
    /// Square of a king currently in check, for the red highlight.
    public private(set) var checkedKingSquare: Square?
    /// Set while a promotion picker must be shown.
    public private(set) var pendingPromotion: (square: Square, color: Piece.Color)?
    /// True while the opponent is choosing a move.
    public private(set) var opponentThinking = false

    public var events: AsyncStream<GameEvent> { eventStream }

    /// Set by the app when the opponent is an engine (M2+).
    public var opponentProvider: (any OpponentProvider)?

    private var board: Board
    private var record: Game
    private var recordIndex: MoveTree.Index
    private var pendingPromotionMove: Move?
    private let eventStream: AsyncStream<GameEvent>
    private let eventContinuation: AsyncStream<GameEvent>.Continuation
    private var opponentTask: Task<Void, Never>?

    public init(config: GameConfig) {
        self.config = config
        let position = config.startFEN.flatMap(Position.init(fen:)) ?? .standard
        self.board = Board(position: position)
        var tags: Game.Tags? = nil
        if let fen = config.startFEN {
            var t = Game.Tags()
            t.fen = fen
            t.setUp = "1"
            tags = t
        }
        let record = Game(startingWith: position, tags: tags)
        self.record = record
        self.recordIndex = record.startingIndex
        (eventStream, eventContinuation) = AsyncStream.makeStream(of: GameEvent.self, bufferingPolicy: .unbounded)
        rebuildBoardPieces()
    }

    private func rebuildBoardPieces() {
        boardPieces = board.position.pieces.enumerated().map { index, piece in
            BoardPiece(id: nextPieceID + index, kind: piece.kind, color: piece.color, square: piece.square)
        }
        nextPieceID += boardPieces.count
    }

    private var nextPieceID = 0

    private func applyToBoardPieces(_ move: Move) {
        if case .capture(let captured) = move.result {
            // For en passant the captured pawn is not on `move.end`.
            boardPieces.removeAll { $0.square == captured.square }
        }
        if case .castle(let castling) = move.result {
            moveBoardPiece(from: castling.kingStart, to: castling.kingEnd)
            moveBoardPiece(from: castling.rookStart, to: castling.rookEnd)
        } else {
            moveBoardPiece(from: move.start, to: move.end)
        }
        if let promoted = move.promotedPiece,
           let i = boardPieces.firstIndex(where: { $0.square == move.end }) {
            boardPieces[i].kind = promoted.kind
        }
    }

    private func moveBoardPiece(from: Square, to: Square) {
        guard let i = boardPieces.firstIndex(where: { $0.square == from }) else { return }
        boardPieces[i].square = to
    }

    // MARK: - Reading state

    public var pieces: [Piece] { board.position.pieces }
    public var sideToMove: Piece.Color { board.position.sideToMove }
    public var currentFEN: String { board.position.fen }

    /// Whether the given color is controlled by the local user.
    public func isUserControlled(_ color: Piece.Color) -> Bool {
        switch config.opponent {
        case .humanLocal: true
        case .engine: color == config.playerColor
        }
    }

    public func legalTargets(from square: Square) -> [Square] {
        guard status.isPlaying, pendingPromotion == nil else { return [] }
        return board.legalMoves(forPieceAt: square)
    }

    public func piece(at square: Square) -> Piece? {
        board.position.piece(at: square)
    }

    // MARK: - Lifecycle

    /// Replays previously played moves (UCI) into an idle session — used to
    /// resume a saved game. Returns false if any move fails to apply.
    @discardableResult
    public func replay(movesUCI: [String]) -> Bool {
        guard status == .idle else { return false }
        suppressEvents = true
        defer { suppressEvents = false }
        for uci in movesUCI {
            guard let move = OpponentMove(uci: uci),
                  performMove(from: move.from, to: move.to, promotion: move.promotion ?? .queen) == .played
            else { return false }
        }
        return true
    }

    private var suppressEvents = false

    public func start() {
        guard status == .idle else { return }
        status = .playing
        eventContinuation.yield(.gameStarted)
        driveOpponentIfNeeded()
    }

    public func resign(_ color: Piece.Color? = nil) {
        guard status.isPlaying else { return }
        let resigning = color ?? config.playerColor
        finish(.win(resigning.opposite, .resignation))
    }

    /// Called by the clock (M3) when a side runs out of time.
    public func flag(_ color: Piece.Color) {
        guard status.isPlaying else { return }
        finish(.win(color.opposite, .timeout))
    }

    public func abort() {
        guard status.isPlaying, moveHistory.count < 2 else { return }
        finish(.aborted)
    }

    // MARK: - Moves

    /// Attempt a move by the local user (board input).
    @discardableResult
    public func attemptUserMove(from: Square, to: Square) -> MoveOutcome {
        guard status.isPlaying,
              pendingPromotion == nil,
              isUserControlled(sideToMove),
              !opponentThinking
        else { return .illegal }
        return performMove(from: from, to: to, promotion: nil)
    }

    public func completePromotion(to kind: Piece.Kind) {
        guard let move = pendingPromotionMove else { return }
        pendingPromotionMove = nil
        pendingPromotion = nil
        let completed = board.completePromotion(of: move, to: kind)
        commit(move: completed)
    }

    public func cancelPromotion() {
        // ChessKit has already moved the pawn; undoing means rebuilding the
        // board from the record's position, which predates the pawn push.
        guard pendingPromotionMove != nil else { return }
        pendingPromotionMove = nil
        pendingPromotion = nil
        if let position = record.positions[recordIndex] {
            board = Board(position: position)
            rebuildBoardPieces()
        }
    }

    private func performMove(from: Square, to: Square, promotion: Piece.Kind?) -> MoveOutcome {
        guard let move = board.move(pieceAt: from, to: to) else { return .illegal }

        if case .promotion(let promoMove) = board.state {
            if let promotion {
                let completed = board.completePromotion(of: promoMove, to: promotion)
                commit(move: completed)
                return .played
            }
            pendingPromotionMove = promoMove
            pendingPromotion = (square: promoMove.end, color: promoMove.piece.color)
            return .needsPromotion
        }

        commit(move: move)
        return .played
    }

    private func commit(move: Move) {
        recordIndex = record.make(move: move, from: recordIndex)
        applyToBoardPieces(move)

        let mover = move.piece.color
        var isCastle = false
        if case .castle = move.result { isCastle = true }
        var isCapture = false
        if case .capture = move.result { isCapture = true }

        var uci = move.start.notation + move.end.notation
        if let promoted = move.promotedPiece {
            uci += promoted.kind.notation.lowercased()
        }

        let played = PlayedMove(
            ply: moveHistory.count + 1,
            san: move.san,
            uci: uci,
            mover: mover,
            isCapture: isCapture,
            isCastle: isCastle,
            isPromotion: move.promotedPiece != nil,
            givesCheck: move.checkState == .check || move.checkState == .checkmate,
            clockRemaining: clockSnapshot?(mover)
        )
        moveHistory.append(played)
        lastMove = (from: move.start, to: move.end)
        updateCheckHighlight()
        if !suppressEvents {
            eventContinuation.yield(.movePlayed(played))
        }

        if let result = terminalResult() {
            finish(result)
        } else {
            driveOpponentIfNeeded()
        }
    }

    /// Injected by the clock layer (M3): returns the mover's remaining time.
    public var clockSnapshot: (@MainActor (Piece.Color) -> Duration?)?

    private func terminalResult() -> GameResult? {
        switch board.state {
        case .checkmate(let color):
            return .win(color.opposite, .checkmate)
        case .draw(let reason):
            let mapped: GameResult.DrawReason = switch reason {
            case .agreement: .agreement
            case .fiftyMoves: .fiftyMoves
            case .insufficientMaterial: .insufficientMaterial
            case .repetition: .repetition
            case .stalemate: .stalemate
            }
            return .draw(mapped)
        case .active, .check, .promotion:
            return nil
        }
    }

    private func updateCheckHighlight() {
        switch board.state {
        case .check(let color), .checkmate(let color):
            checkedKingSquare = board.position.pieces
                .first { $0.kind == .king && $0.color == color }?.square
        default:
            checkedKingSquare = nil
        }
    }

    private func finish(_ result: GameResult) {
        opponentTask?.cancel()
        opponentThinking = false
        status = .finished(result)
        record.tags.result = result.pgnResult
        eventContinuation.yield(.gameEnded(result))
        eventContinuation.finish()
    }

    // MARK: - Opponent

    private func driveOpponentIfNeeded() {
        guard status.isPlaying,
              !isUserControlled(sideToMove),
              let provider = opponentProvider
        else { return }

        opponentThinking = true
        let fen = currentFEN
        opponentTask = Task { [weak self] in
            do {
                let move = try await provider.nextMove(fen: fen, remainingTime: nil)
                guard let self, !Task.isCancelled else { return }
                self.opponentThinking = false
                _ = self.performMove(from: move.from, to: move.to, promotion: move.promotion ?? .queen)
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.opponentThinking = false
            }
        }
    }

    // MARK: - Export

    /// PGN of the game so far, with headers and [%clk] comments when timed.
    public var pgn: String {
        var tags = record.tags
        tags.event = "Chess AI game"
        tags.site = "Chess AI iOS"
        if let tc = config.timeControl { tags.timeControl = tc.pgnTag }
        if case .finished(let result) = status { tags.result = result.pgnResult }
        var game = record
        game.tags = tags
        return PGNClockAnnotator.annotate(pgn: game.pgn, history: moveHistory)
    }
}
