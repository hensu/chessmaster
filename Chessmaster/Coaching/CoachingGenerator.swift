// Chessmaster — GPL-3.0-or-later
import Foundation
import SupabaseSync

/// The one path that asks the backend for an AI coaching report — shared by
/// the Analysis CTA and the proactive first report (AutoCoach). Callers own
/// the premium check; the server enforces it again.
@MainActor
enum CoachingGenerator {
    static func generate(gameID: String, sync: SyncService) async throws -> CoachingReport? {
        if !sync.isSignedIn {
            await sync.signInAnonymously()
        }
        await sync.pushPending()   // the game must exist server-side
        let response: CoachingResponse = try await sync.invokeFunction(
            "generate-coaching-report",
            body: ["game_id": gameID.lowercased()]
        )
        return response.report.report
    }
}
