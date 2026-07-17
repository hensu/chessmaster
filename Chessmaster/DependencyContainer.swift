// Chessmaster — GPL-3.0-or-later
import SwiftUI
import AudioKitChess
import PaywallKit
import PersistenceKit
import SupabaseSync
import GRDB

/// Composition root. Services are added here as milestones land.
@Observable @MainActor
final class DependencyContainer {
    let audio = ChessAudioPlayer()
    let games: GameRepository
    let ratingHistory: RatingHistoryRepository
    let inProgress: InProgressGameStore
    let ratings: RatingStore
    let sync: SyncService
    let entitlements = EntitlementStore()
    let learnProgress = LearnProgress()
    let coachVoice = CoachVoice()

    init() {
        let url = URL.applicationSupportDirectory
            .appending(path: "Chessmaster/chessmaster.sqlite")
        // The app is unusable without its database; crashing at the
        // composition root beats limping along with silent data loss.
        let dbQueue = try! AppDatabase.makeQueue(at: url)
        games = GameRepository(dbQueue: dbQueue)
        ratingHistory = RatingHistoryRepository(dbQueue: dbQueue)
        inProgress = InProgressGameStore(dbQueue: dbQueue)
        ratings = RatingStore(history: ratingHistory)
        sync = SyncService(
            config: SupabaseConfig.fromMainBundle(),
            games: games,
            ratingHistory: ratingHistory
        )
        // Anyone signed in syncs (free or premium); playing without an
        // account stays fully local.
        Task { [sync, entitlements] in
            await entitlements.refresh()
            await sync.restoreSession()
            await sync.pushPending()
        }
    }
}
