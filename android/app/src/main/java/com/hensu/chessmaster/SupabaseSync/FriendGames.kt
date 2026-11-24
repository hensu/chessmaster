// SupabaseSync — Chessmaster
// GPL-3.0-or-later
//
// Live friend games (invite link → game). The backend row is the source of
// truth (moves + server clocks, migration 0012); every write is an RPC that
// checks turn order and time. On iOS, Realtime only pokes us that the row changed;
// here the poke is a 2-second timer — we then re-read the row through
// `friend_game_state`, which adds names and the server's clock reading.
package com.hensu.chessmaster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class FriendGameState(
    @Serializable(with = SyncUUIDSerializer::class)
    val id: UUID,
    val code: String,
    @SerialName("host_id") @Serializable(with = SyncUUIDSerializer::class)
    val hostID: UUID,
    @SerialName("guest_id") @Serializable(with = SyncUUIDSerializer::class)
    val guestID: UUID? = null,
    @SerialName("host_color") val hostColor: String,
    @SerialName("initial_ms") val initialMs: Int,
    @SerialName("increment_ms") val incrementMs: Int,
    /** waiting | active | finished | aborted */
    val status: String,
    val moves: List<String>,
    @SerialName("white_ms") val whiteMs: Int,
    @SerialName("black_ms") val blackMs: Int,
    /** How long the side to move has been thinking (server clock). */
    @SerialName("turn_elapsed_ms") val turnElapsedMs: Int,
    /** whiteWin | blackWin | draw */
    val result: String? = null,
    val termination: String? = null,
    @SerialName("host_name") val hostName: String,
    @SerialName("guest_name") val guestName: String? = null,
) {
    val isOver: Boolean get() = status == "finished" || status == "aborted"

    /** "white"/"black" for the given player. */
    fun color(of: UUID): String =
        if (of == hostID) hostColor else (if (hostColor == "white") "black" else "white")

    fun opponentName(`for`: UUID): String =
        if (`for` == hostID) (guestName ?: "Friend") else hostName

    /** A direct challenge the given user hasn't accepted yet. */
    fun isChallenge(`for`: UUID): Boolean = status == "waiting" && guestID == `for`

    val timeControlLabel: String get() = "${initialMs / 60_000}+${incrementMs / 1000}"

    val inviteURL: String get() = "https://getchessai.com/play/?g=$code"
}

/** Someone the user has played before (Play a Friend → "Play again"). */
@Serializable
data class RecentFriend(
    @Serializable(with = SyncUUIDSerializer::class)
    val id: UUID,
    val name: String,
    val games: Int,
)

/** A free month earned by inviting a friend (a store one-time offer code). */
@Serializable
data class ReferralReward(
    val code: String,
    val role: String,
    @SerialName("assigned_at") @Serializable(with = SyncInstantSerializer::class)
    val assignedAt: Instant,
) {
    val id: String get() = code

    val claimBy: Instant get() = assignedAt.plusSeconds(claimWindow.toLong())

    /** Opens Google Play's redeem page with the code pre-filled. (`appID` is the iOS app's
     *  App Store id; Play needs only the code.) */
    @Suppress("UNUSED_PARAMETER")
    fun redeemURL(appID: String): String = "https://play.google.com/redeem?code=$code"

    companion object {
        /** Rewards must be claimed within this window (urgency; the store
         *  code itself is valid much longer). */
        const val claimWindow: Double = 30.0 * 86_400
    }
}

sealed class FriendGameError : Exception() {
    data object NotSignedIn : FriendGameError()
    data object NotFound : FriendGameError()
    data object GameFull : FriendGameError()
    data object GameClosed : FriendGameError()
    data object OutOfSync : FriendGameError()
    data class Other(val description: String) : FriendGameError()

    override val message: String
        get() = when (this) {
            NotSignedIn -> "Sign in to play friends."
            NotFound -> "That invite link isn't valid."
            GameFull -> "Someone else already joined this game."
            GameClosed -> "This invite has expired. Ask your friend for a new one."
            OutOfSync -> "The game moved on — refreshing."
            is Other -> "Couldn't reach the server. Check your connection and try again."
        }

    companion object {
        internal fun of(error: Throwable): FriendGameError {
            if (error is FriendGameError) return error
            val text = error.toString()
            return when {
                text.contains("not_signed_in") -> NotSignedIn
                text.contains("not_found") -> NotFound
                text.contains("game_full") -> GameFull
                text.contains("game_closed") -> GameClosed
                text.contains("out_of_sync") || text.contains("not_your_turn") -> OutOfSync
                else -> Other(error.localizedMessage ?: text)
            }
        }
    }
}

private suspend fun SyncService.friendRPC(fn: String, params: JsonObject): FriendGameState {
    val client = client
    if (client == null || !isSignedIn) throw FriendGameError.NotSignedIn
    try {
        return AppJson.decodeFromString(FriendGameState.serializer(), client.rpc(fn, params))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw FriendGameError.of(e)
    }
}

/** `opponent`: challenge someone already played (they accept from Home)
 *  instead of an open invite link. */
suspend fun SyncService.createFriendGame(
    initialSeconds: Int, incrementSeconds: Int, hostColor: String, opponent: UUID? = null,
): FriendGameState = friendRPC("friend_game_create", buildJsonObject {
    put("p_initial_ms", initialSeconds * 1000)
    put("p_increment_ms", incrementSeconds * 1000)
    put("p_host_color", hostColor)
    put("p_opponent", opponent?.let { JsonPrimitive(it.syncUUIDString) } ?: JsonNull)
})

suspend fun SyncService.recentFriends(): List<RecentFriend> {
    val client = client
    if (client == null || !isSignedIn) return emptyList()
    return try {
        AppJson.decodeFromString(ListSerializer(RecentFriend.serializer()), client.rpc("friend_recent_opponents"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun SyncService.joinFriendGame(code: String): FriendGameState =
    friendRPC("friend_game_join", buildJsonObject { put("p_code", code) })

suspend fun SyncService.friendGame(id: UUID): FriendGameState =
    friendRPC("friend_game_state", buildJsonObject { put("p_id", id.syncUUIDString) })

suspend fun SyncService.sendFriendMove(gameID: UUID, ply: Int, uci: String): FriendGameState =
    friendRPC("friend_game_move", buildJsonObject {
        put("p_id", gameID.syncUUIDString)
        put("p_ply", ply)
        put("p_uci", uci)
    })

/** `termination`: resignation | timeout | aborted | checkmate | a draw reason. */
suspend fun SyncService.endFriendGame(gameID: UUID, termination: String): FriendGameState =
    friendRPC("friend_game_end", buildJsonObject {
        put("p_id", gameID.syncUUIDString)
        put("p_result", JsonNull)
        put("p_termination", termination)
    })

/** Games the user is still in (active, or an invite waiting for a friend). */
suspend fun SyncService.openFriendGames(): List<FriendGameState> {
    val client = client
    if (client == null || !isSignedIn) return emptyList()
    return try {
        AppJson.decodeFromString(ListSerializer(FriendGameState.serializer()), client.rpc("friend_games_open"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

/** Pokes `onChange` (on the main thread) so the caller re-reads the game. iOS is poked by
 *  Realtime when the row changes; Android has no Realtime, so this pokes every 2 seconds
 *  until cancelled. Cancel the returned job to unsubscribe. */
@Suppress("UNUSED_PARAMETER")
fun SyncService.watchFriendGame(id: UUID, onChange: () -> Unit): Job? {
    if (client == null) return null
    return MainScope().launch {
        while (isActive) {
            delay(2000)
            onChange()
        }
    }
}

// MARK: - Referral rewards

suspend fun SyncService.unredeemedReferralRewards(): List<ReferralReward> {
    val client = client
    if (client == null || !isSignedIn) return emptyList()
    val since = Instant.now().minusSeconds(ReferralReward.claimWindow.toLong()).truncatedTo(ChronoUnit.SECONDS)
    return try {
        val text = client.select("referral_codes", "code, role, assigned_at", listOf(
            "redeemed_at" to "is.null",
            "assigned_at" to "gte.$since",
        ))
        AppJson.decodeFromString(ListSerializer(ReferralReward.serializer()), text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun SyncService.markReferralRedeemed(code: String) {
    val client = client
    if (client == null || !isSignedIn) return
    try {
        client.rpc("referral_code_mark_redeemed", buildJsonObject { put("p_code", code) })
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
