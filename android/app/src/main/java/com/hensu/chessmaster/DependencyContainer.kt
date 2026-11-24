// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Composition root. Services are added here as milestones land. */
class DependencyContainer {
    val audio = ChessAudioPlayer()
    val games: GameRepository
    val ratingHistory: RatingHistoryRepository
    val inProgress: InProgressGameStore
    val ratings: RatingStore
    val sync: SyncService
    val entitlements = EntitlementStore()
    val learnProgress = LearnProgress()
    val coachVoice = CoachVoice()
    /**
     * Invite code from a link (getchessai.com/play/?g=…), waiting for
     * sign-in / join. RootView consumes it.
     */
    var pendingInviteCode: String? by mutableStateOf(null)
    /** The friend game on screen (RootView presents it full-screen). */
    var activeFriendGame: FriendGameLink? by mutableStateOf(null)

    fun startFriendGame(state: FriendGameState) {
        val me = sync.userID ?: return
        if (activeFriendGame?.state?.id == state.id) return
        activeFriendGame = FriendGameLink(state, me, sync)
    }

    init {
        // The app is unusable without its database; crashing at the
        // composition root beats limping along with silent data loss.
        val db = AppDatabase.open()
        games = GameRepository(db)
        ratingHistory = RatingHistoryRepository(db)
        inProgress = InProgressGameStore(db)
        ratings = RatingStore(ratingHistory)
        sync = SyncService(SupabaseConfig.fromMainBundle(), games, ratingHistory)
        // Anyone signed in syncs (free or premium); playing without an
        // account stays fully local.
        MainScope().launch {
            // Resolve sign-in FIRST — it's a fast local/session read, whereas
            // the billing entitlement refresh can be slow. Doing it first
            // stops sign-in prompts from flashing for an already-signed-in user.
            sync.restoreSession()
            entitlements.refresh()
            sync.pushPending()
        }
    }

    companion object {
        val shared: DependencyContainer by lazy { DependencyContainer() }
    }
}
