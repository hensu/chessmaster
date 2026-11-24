// SupabaseSync — Chessmaster
// GPL-3.0-or-later
//
// Wire types for the backend tables (see supabase/migrations/0001_init.sql).
package com.hensu.chessmaster

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Swift's `uuid.uuidString`: the upper-case form (GA ids and wire rows match the iOS app's). */
val UUID.syncUUIDString: String get() = toString().uppercase()

/** Swift's `UUID(uuidString:)`: null for anything that isn't a UUID. */
internal fun syncParseUUID(text: String?): UUID? =
    if (text == null) null else try { UUID.fromString(text) } catch (e: IllegalArgumentException) { null }

/** UUIDs travel as strings, upper-case like Swift's encoder writes them. */
object SyncUUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("SyncUUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.syncUUIDString)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Dates travel as ISO 8601 strings; Postgres answers with an offset ("+00:00") and microseconds. */
object SyncInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("SyncInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
}

@Serializable
data class RemoteGame(
    /** Server PK is set to the client's UUID so rating_history FKs line up
     *  and re-pushing the same game is a no-op upsert. */
    @Serializable(with = SyncUUIDSerializer::class)
    var id: UUID,
    @SerialName("user_id") @Serializable(with = SyncUUIDSerializer::class)
    var userId: UUID,
    @SerialName("client_game_id") @Serializable(with = SyncUUIDSerializer::class)
    var clientGameId: UUID,
    @SerialName("opponent_type") var opponentType: String,
    @SerialName("engine_level") var engineLevel: Int? = null,
    @SerialName("user_color") var userColor: String,
    @SerialName("time_control") var timeControl: String? = null,
    @SerialName("time_class") var timeClass: String,
    var result: String,
    var termination: String? = null,
    var pgn: String,
    var evals: String? = null,
    @SerialName("analysis_profile") var analysisProfile: String? = null,
    @SerialName("rating_before") var ratingBefore: Double? = null,
    @SerialName("rating_after") var ratingAfter: Double? = null,
    @SerialName("played_at") @Serializable(with = SyncInstantSerializer::class)
    var playedAt: Instant,
) {
    companion object {
        /** Maps a local record onto the wire shape. Returns null for rows the
         *  backend rejects (e.g. aborted hot-seat games are fine, malformed
         *  client IDs are not). */
        fun of(record: GameRecord, userId: UUID): RemoteGame? {
            val clientGameId = runCatching { UUID.fromString(record.id) }.getOrNull() ?: return null
            val initial = record.tcInitialSeconds
            val increment = record.tcIncrementSeconds
            return RemoteGame(
                id = clientGameId,
                userId = userId,
                clientGameId = clientGameId,
                // Server enum has no 'humanLocal'; hot-seat games sync as 'human'.
                opponentType = if (record.opponentType == "engine") "engine" else "human",
                engineLevel = record.engineLevel,
                userColor = record.playerColor,
                timeControl = if (initial != null && increment != null) "$initial+$increment" else null,
                timeClass = record.ratingCategory ?: timeClass(record.tcInitialSeconds, record.tcIncrementSeconds),
                result = when (record.result) {
                    "whiteWin" -> "white_win"
                    "blackWin" -> "black_win"
                    "draw" -> "draw"
                    else -> "aborted"
                },
                termination = record.termination,
                pgn = record.pgn,
                evals = record.analysisJSON,
                ratingBefore = record.ratingBefore,
                ratingAfter = record.ratingAfter,
                playedAt = record.endedAt,
            )
        }

        /** Lichess speed buckets, duplicated from TimeControl.category so this
         *  package doesn't depend on the chess domain. */
        internal fun timeClass(initialSeconds: Int?, incrementSeconds: Int?): String {
            val initial = initialSeconds ?: return "classical"
            val estimate = initial + 40 * (incrementSeconds ?: 0)
            return when {
                estimate < 180 -> "bullet"
                estimate < 480 -> "blitz"
                estimate < 1500 -> "rapid"
                else -> "classical"
            }
        }
    }
}

@Serializable
data class RemoteRatingHistory(
    @SerialName("user_id") @Serializable(with = SyncUUIDSerializer::class)
    var userId: UUID,
    @SerialName("game_id") @Serializable(with = SyncUUIDSerializer::class)
    var gameId: UUID? = null,
    @SerialName("time_class") var timeClass: String,
    var rating: Double,
    var rd: Double,
    var volatility: Double,
    @SerialName("created_at") @Serializable(with = SyncInstantSerializer::class)
    var createdAt: Instant,
) {
    constructor(record: RatingHistoryRecord, userId: UUID) : this(
        userId = userId,
        gameId = syncParseUUID(record.gameID),
        timeClass = record.category,
        rating = record.rating,
        rd = record.deviation,
        volatility = record.volatility,
        createdAt = record.at,
    )
}
