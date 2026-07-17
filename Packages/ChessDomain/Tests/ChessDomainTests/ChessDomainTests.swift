// ChessDomain — Chessmaster
// GPL-3.0-or-later

import Testing
@testable import ChessDomain

@MainActor
@Suite struct GameSessionTests {
    private func session(fen: String? = nil) -> GameSession {
        let s = GameSession(config: GameConfig(startFEN: fen, opponent: .humanLocal))
        s.start()
        return s
    }

    private func move(_ s: GameSession, _ uci: String) -> MoveOutcome {
        let m = OpponentMove(uci: uci)!
        return s.attemptUserMove(from: m.from, to: m.to)
    }

    @Test func scholarsMateEndsInCheckmate() {
        let s = session()
        for uci in ["e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"] {
            #expect(move(s, uci) == .played)
        }
        #expect(s.status == .finished(.win(.white, .checkmate)))
        #expect(s.moveHistory.last?.san == "Qxf7#")
    }

    @Test func illegalMoveRejected() {
        let s = session()
        #expect(move(s, "e2e5") == .illegal)
        #expect(s.moveHistory.isEmpty)
    }

    @Test func castlingIsRecorded() {
        let s = session()
        for uci in ["e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5", "e1g1"] {
            #expect(move(s, uci) == .played)
        }
        #expect(s.moveHistory.last?.san == "O-O")
        #expect(s.moveHistory.last?.isCastle == true)
    }

    @Test func enPassantCaptures() {
        let s = session()
        for uci in ["e2e4", "a7a6", "e4e5", "d7d5"] {
            #expect(move(s, uci) == .played)
        }
        #expect(move(s, "e5d6") == .played)
        #expect(s.moveHistory.last?.isCapture == true)
        #expect(s.piece(at: Square("d5")) == nil)
    }

    @Test func promotionRequiresChoice() {
        // White pawn on a7 ready to promote.
        let s = session(fen: "8/P6k/8/8/8/8/8/K7 w - - 0 1")
        #expect(move(s, "a7a8") == .needsPromotion)
        #expect(s.pendingPromotion?.color == .white)
        s.completePromotion(to: .queen)
        #expect(s.pendingPromotion == nil)
        #expect(s.moveHistory.last?.isPromotion == true)
        #expect(s.piece(at: Square("a8"))?.kind == .queen)
    }

    @Test func stalemateIsDraw() {
        // Kh8 vs Kg6+Qe7: after Qf7 black is not in check and has no legal move.
        let s = session(fen: "7k/4Q3/6K1/8/8/8/8/8 w - - 0 1")
        #expect(move(s, "e7f7") == .played)
        #expect(s.status == .finished(.draw(.stalemate)))
    }

    @Test func resignationEndsGame() {
        let s = session()
        #expect(move(s, "e2e4") == .played)
        s.resign(.black)
        #expect(s.status == .finished(.win(.white, .resignation)))
    }

    @Test func pgnContainsMovesAndResult() {
        let s = session()
        for uci in ["e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"] {
            _ = move(s, uci)
        }
        let pgn = s.pgn
        #expect(pgn.contains("Qxf7#"))
        #expect(pgn.contains("1-0"))
    }
}

@Suite struct TimeControlTests {
    @Test func categories() {
        #expect(TimeControl(minutes: 1, incrementSeconds: 0).category == .bullet)
        #expect(TimeControl(minutes: 5, incrementSeconds: 0).category == .blitz)
        #expect(TimeControl(minutes: 10, incrementSeconds: 0).category == .rapid)
        #expect(TimeControl(minutes: 30, incrementSeconds: 0).category == .classical)
    }

    @Test func labels() {
        #expect(TimeControl(minutes: 3, incrementSeconds: 2).label == "3+2")
        #expect(TimeControl(minutes: 10, incrementSeconds: 0).pgnTag == "600+0")
    }
}

@Suite struct PGNClockAnnotatorTests {
    @Test func insertsClockComments() {
        let history = [
            PlayedMove(ply: 1, san: "e4", uci: "e2e4", mover: .white, isCapture: false,
                       isCastle: false, isPromotion: false, givesCheck: false,
                       clockRemaining: .seconds(299)),
            PlayedMove(ply: 2, san: "e5", uci: "e7e5", mover: .black, isCapture: false,
                       isCastle: false, isPromotion: false, givesCheck: false,
                       clockRemaining: .seconds(298)),
        ]
        let annotated = PGNClockAnnotator.annotate(pgn: "1. e4 e5 *", history: history)
        #expect(annotated == "1. e4 {[%clk 0:04:59]} e5 {[%clk 0:04:58]} *")
    }

    @Test func noClockNoChange() {
        let history = [
            PlayedMove(ply: 1, san: "e4", uci: "e2e4", mover: .white, isCapture: false,
                       isCastle: false, isPromotion: false, givesCheck: false)
        ]
        #expect(PGNClockAnnotator.annotate(pgn: "1. e4 *", history: history) == "1. e4 *")
    }
}

@Suite struct GameReplayBoardPiecesTests {
    // Covers a capture, kingside castling, and quiet moves.
    private let pgn = "1. e4 d5 2. exd5 Nf6 3. Nf3 Nxd5 4. Be2 e5 5. O-O *"

    @Test func idsAreStableAcrossPlies() throws {
        let replay = try #require(GameReplay(pgn: pgn))
        for ply in 0..<replay.plies.count {
            let before = replay.boardPieces(atPly: ply)
            let after = replay.boardPieces(atPly: ply + 1)
            let beforeByID = Dictionary(uniqueKeysWithValues: before.map { ($0.id, $0) })
            // Every surviving piece keeps its id; at most two change square
            // (two only when castling).
            let movedIDs = after.filter { beforeByID[$0.id]?.square != $0.square }.map(\.id)
            #expect(movedIDs.count <= 2, "ply \(ply + 1) moved \(movedIDs.count) pieces")
            #expect(after.allSatisfy { beforeByID[$0.id] != nil }, "ply \(ply + 1) invented a piece")
        }
    }

    @Test func captureRemovesExactlyTheCapturedPiece() throws {
        let replay = try #require(GameReplay(pgn: pgn))
        let before = replay.boardPieces(atPly: 2)   // after 1...d5
        let after = replay.boardPieces(atPly: 3)    // after 2. exd5
        #expect(before.count == 32)
        #expect(after.count == 31)
        let afterIDs = Set(after.map(\.id))
        let capturedIDs = before.filter { !afterIDs.contains($0.id) }
        #expect(capturedIDs.count == 1)
        #expect(capturedIDs.first?.kind == .pawn)
        #expect(capturedIDs.first?.color == .black)
    }

    @Test func castlingMovesKingAndRookByIdentity() throws {
        let replay = try #require(GameReplay(pgn: pgn))
        let before = replay.boardPieces(atPly: 8)   // before 5. O-O
        let after = replay.boardPieces(atPly: 9)    // after 5. O-O
        let kingID = try #require(before.first { $0.kind == .king && $0.color == .white }?.id)
        let rookID = try #require(before.first { $0.square == .h1 }?.id)
        #expect(after.first { $0.id == kingID }?.square == .g1)
        #expect(after.first { $0.id == rookID }?.square == .f1)
    }
}
