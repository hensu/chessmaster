// Chessmaster — GPL-3.0-or-later
//
// Live friend games. The backend row (SupabaseSync/FriendGames.kt) owns
// the moves and clocks; FriendGameLink follows it (a 2-second poll; there is
// no Realtime on Android) and hands the friend's moves to GameSession as an
// OpponentProvider, so GameViewModel/GameScreen run friend games like any other.

package com.hensu.chessmaster

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FriendGameLink(state: FriendGameState, val myID: UUID, val sync: SyncService) {
    var state: FriendGameState by mutableStateOf(state)
        private set
    /** Called after every fresh server state (clock sync, remote endings). */
    var onState: ((FriendGameState) -> Unit)? = null

    private var pollTask: Job? = null
    private val waiters = mutableMapOf<Int, CancellableContinuation<String>>()
    private val scope = MainScope()

    val myColor: Piece.Color get() = if (state.color(myID) == "black") Piece.Color.black else Piece.Color.white
    val opponentName: String get() = state.opponentName(myID)

    val config: GameConfig
        get() = GameConfig(
            playerColor = myColor,
            timeControl = TimeControl(
                initial = state.initialMs.milliseconds,
                increment = state.incrementMs.milliseconds,
            ),
            opponent = GameConfig.Opponent.Friend,
        )

    fun start() {
        if (pollTask != null) return
        // iOS gets a Realtime poke when the row changes, plus a slow safety
        // poll. Here the poll is the whole link: re-read the row every two
        // seconds while the game is live. Cheap, and it makes the game
        // self-healing (backgrounding, flaky network).
        pollTask = scope.launch {
            while (isActive) {
                delay(2.seconds)
                if (state.isOver) return@launch
                refresh()
            }
        }
    }

    fun stop() {
        pollTask?.cancel()
        pollTask = null
        val pending = waiters.values.toList()
        waiters.clear()
        for (waiter in pending) waiter.cancel(CancellationException())
    }

    suspend fun refresh() {
        val fresh = try {
            sync.friendGame(state.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        apply(fresh)
    }

    fun apply(fresh: FriendGameState) {
        // Never step backwards (an older response landing late).
        if (!(fresh.moves.size >= state.moves.size || fresh.isOver)) return
        state = fresh
        for ((ply, waiter) in waiters.toList()) {
            if (ply <= fresh.moves.size) {
                waiters.remove(ply)
                waiter.resume(fresh.moves[ply - 1])
            }
        }
        onState?.invoke(fresh)
    }

    /** The friend's move at `ply` (1-based), once it exists. */
    suspend fun move(atPly: Int): String {
        if (atPly <= state.moves.size) return state.moves[atPly - 1]
        return suspendCancellableCoroutine { continuation ->
            waiters[atPly] = continuation
            continuation.invokeOnCancellation {
                if (waiters[atPly] === continuation) waiters.remove(atPly)
            }
        }
    }

    suspend fun send(ply: Int, uci: String) {
        try {
            apply(sync.sendFriendMove(state.id, ply, uci))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Out of sync or offline: the server copy wins.
            refresh()
        }
    }

    suspend fun end(termination: String) {
        val fresh = try {
            sync.endFriendGame(state.id, termination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        apply(fresh)
    }

    /**
     * A clock hit zero locally. The server decides; retry briefly while
     * its clock catches up with ours (network latency).
     */
    suspend fun claimTimeout() {
        repeat(8) {
            end("timeout")
            if (state.isOver) return
            delay(1.seconds)
        }
    }
}

/** Feeds the friend's moves into GameSession. */
class FriendOpponent(val link: FriendGameLink) : OpponentProvider {
    override suspend fun nextMove(fen: String, remainingTime: Duration?): OpponentMove {
        // Ply about to be played, from the FEN's side to move + move number.
        val fields = fen.split(" ").filter { it.isNotEmpty() }
        val fullMove = if (fields.size > 5) fields[5].toIntOrNull() ?: 1 else 1
        val ply = (fullMove - 1) * 2 + (if (fields.size > 1 && fields[1] == "b") 2 else 1)
        val uci = link.move(ply)
        return OpponentMove.of(uci) ?: throw CancellationException()
    }
}

/**
 * Server result → domain result, for endings the board can't see
 * (resignation, timeout, abort).
 */
val FriendGameState.domainResult: GameResult?
    get() {
        if (status == "aborted") return GameResult.Aborted
        val result = result
        if (status != "finished" || result == null) return null
        val winner: Piece.Color? =
            if (result == "whiteWin") Piece.Color.white else if (result == "blackWin") Piece.Color.black else null
        return when {
            winner != null && termination == "resignation" -> GameResult.Win(winner, GameResult.Termination.resignation)
            winner != null && termination == "timeout" -> GameResult.Win(winner, GameResult.Termination.timeout)
            winner != null -> GameResult.Win(winner, GameResult.Termination.checkmate)
            else -> GameResult.Draw(GameResult.DrawReason.fromRaw(termination ?: "") ?: GameResult.DrawReason.agreement)
        }
    }

/**
 * Invite links: https://getchessai.com/play/?g=CODE (app link) or
 * chessmaster://play?g=CODE (the web page's "Open in app" button).
 */
object FriendInvite {
    fun code(from: String): String? {
        val url = runCatching { Uri.parse(from) }.getOrNull() ?: return null
        val isWeb = url.host?.endsWith("getchessai.com") == true && url.path?.startsWith("/play") == true
        val isScheme = url.scheme == "chessmaster" && url.host == "play"
        if (!(isWeb || isScheme)) return null
        val code = runCatching { url.getQueryParameter("g") }.getOrNull()
        if (code.isNullOrEmpty()) return null
        return code.uppercase()
    }

    fun message(state: FriendGameState): String {
        val minutes = state.initialMs / 60_000
        val inc = state.incrementMs / 1000
        return "Play me at chess! $minutes+$inc on Chess AI ♟️ Finish the game and we both get a free month of Diamond. ${state.inviteURL}"
    }
}

/**
 * Screenshot-only sample data (debug builds): `--preview-friend-reward` shows a reward
 * card on Home, `--preview-friend-waiting` opens the lobby's waiting screen.
 */
object FriendPreview {
    val reward: ReferralReward?
        get() {
            if (!BuildConfig.DEBUG || !App.arguments.contains("--preview-friend-reward")) return null
            val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            return decode("""{"code":"PREVIEW","role":"inviter","assigned_at":"$now"}""")
        }

    val waitingGame: FriendGameState?
        get() {
            if (!BuildConfig.DEBUG || !App.arguments.contains("--preview-friend-waiting")) return null
            return decode(
                """
                {"id":"${UUID.randomUUID()}","code":"K7QX2M","host_id":"${UUID.randomUUID()}","guest_id":null,
                 "host_color":"white","initial_ms":600000,"increment_ms":0,"status":"waiting","moves":[],
                 "white_ms":600000,"black_ms":600000,"turn_elapsed_ms":0,"result":null,"termination":null,
                 "host_name":"Raj","guest_name":null}
                """.trimIndent()
            )
        }

    private inline fun <reified T> decode(json: String): T? =
        runCatching { AppJson.decodeFromString<T>(json) }.getOrNull()
}
