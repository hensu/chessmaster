// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

/**
 * The one path that asks the backend for an AI coaching report — shared by
 * the Analysis CTA and the proactive first report (AutoCoach). Callers own
 * the premium check; the server enforces it again.
 */
object CoachingGenerator {
    suspend fun generate(gameID: String, sync: SyncService): CoachingReport? {
        if (!sync.isSignedIn) {
            sync.signInAnonymously()
        }
        sync.pushPending()   // the game must exist server-side
        val response: CoachingResponse = sync.invokeFunction(
            "generate-coaching-report",
            body = mapOf("game_id" to gameID.lowercase()),
        )
        return response.report.report
    }
}
