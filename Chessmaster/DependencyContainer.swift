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
    /// Invite code from a link (getchessai.com/play/?g=…), waiting for
    /// sign-in / join. RootView consumes it.
    var pendingInviteCode: String?
    /// The friend game on screen (RootView presents it full-screen).
    var activeFriendGame: FriendGameLink?

    func startFriendGame(_ state: FriendGameState) {
        guard let me = sync.userID, activeFriendGame?.state.id != state.id else { return }
        activeFriendGame = FriendGameLink(state: state, myID: me, sync: sync)
    }

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
            // Resolve sign-in FIRST — it's a fast local/session read, whereas
            // the StoreKit entitlement refresh can be slow. Doing it first
            // stops sign-in prompts from flashing for an already-signed-in user.
            await sync.restoreSession()
            await entitlements.refresh()
            await sync.pushPending()
        }
    }
}
