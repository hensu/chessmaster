// PersistenceKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import Testing
@testable import PersistenceKit

@Suite struct PersistenceTests {
    @Test func saveAndFetchGame() throws {
        let db = try AppDatabase.makeInMemory()
        let games = GameRepository(dbQueue: db)

        let record = GameRecord(
            startedAt: Date(timeIntervalSince1970: 1000),
            endedAt: Date(timeIntervalSince1970: 2000),
            opponentType: "engine",
            engineLevel: 3,
            playerColor: "white",
            tcInitialSeconds: 300,
            tcIncrementSeconds: 3,
            result: "whiteWin",
            termination: "checkmate",
            pgn: "1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7# 1-0",
            finalFEN: "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4",
            ratingCategory: "blitz",
            ratingBefore: 1500,
            ratingAfter: 1512
        )
        try games.save(record)

        let fetched = try games.recentGames()
        #expect(fetched.count == 1)
        #expect(fetched[0].pgn.contains("Qxf7#"))
        #expect(try games.game(id: record.id)?.engineLevel == 3)

        let stats = try games.stats()
        #expect(stats.wins == 1 && stats.losses == 0 && stats.draws == 0)
    }

    @Test func analysisUpdatePersists() throws {
        let db = try AppDatabase.makeInMemory()
        let games = GameRepository(dbQueue: db)
        let record = GameRecord(
            startedAt: .now, endedAt: .now, opponentType: "engine",
            playerColor: "black", result: "draw", pgn: "*", finalFEN: "fen"
        )
        try games.save(record)
        try games.updateAnalysis(id: record.id, analysisJSON: #"{"acc": 91}"#)
        #expect(try games.game(id: record.id)?.analysisJSON == #"{"acc": 91}"#)
    }

    @Test func ratingHistoryOrderAndLatest() throws {
        let db = try AppDatabase.makeInMemory()
        let history = RatingHistoryRepository(dbQueue: db)
        for (i, rating) in [1500.0, 1512.0, 1498.0].enumerated() {
            try history.append(RatingHistoryRecord(
                gameID: nil,
                at: Date(timeIntervalSince1970: Double(i * 100)),
                category: "blitz",
                rating: rating, deviation: 200, volatility: 0.06
            ))
        }
        try history.append(RatingHistoryRecord(
            gameID: nil, at: Date(timeIntervalSince1970: 50),
            category: "rapid", rating: 1600, deviation: 300, volatility: 0.06
        ))

        let blitz = try history.history(category: "blitz")
        #expect(blitz.map(\.rating) == [1500, 1512, 1498])

        let latest = try history.latestPerCategory()
        #expect(latest["blitz"]?.rating == 1498)
        #expect(latest["rapid"]?.rating == 1600)
    }

    @Test func inProgressGameRoundTrip() throws {
        let db = try AppDatabase.makeInMemory()
        let store = InProgressGameStore(dbQueue: db)
        #expect(try store.load() == nil)

        let snapshot = InProgressGameRecord(
            startFEN: nil, opponentType: "engine", engineLevel: 5,
            playerColor: "white", tcInitialSeconds: 180, tcIncrementSeconds: 2,
            movesUCI: "e2e4 e7e5 g1f3", whiteRemainingMs: 175000, blackRemainingMs: 178000,
            updatedAt: .now
        )
        try store.save(snapshot)
        #expect(try store.load()?.movesUCI == "e2e4 e7e5 g1f3")

        // Saving again replaces the single row.
        var updated = snapshot
        updated.movesUCI = "e2e4 e7e5 g1f3 b8c6"
        try store.save(updated)
        #expect(try store.load()?.movesUCI == "e2e4 e7e5 g1f3 b8c6")

        try store.clear()
        #expect(try store.load() == nil)
    }
}
