// SupabaseSync — Chessmaster
// GPL-3.0-or-later

import ChessDomain
import Foundation
import Observation
import PersistenceKit
import Supabase

public struct SupabaseConfig: Sendable {
    public let url: URL
    public let anonKey: String

    public init(url: URL, anonKey: String) {
        self.url = url
        self.anonKey = anonKey
    }

    /// Reads SUPABASE_URL / SUPABASE_ANON_KEY from the main bundle's
    /// Info.plist. Returns nil when the backend hasn't been configured —
    /// the app then runs fully offline.
    public static func fromMainBundle() -> SupabaseConfig? {
        guard
            let urlString = Bundle.main.object(forInfoDictionaryKey: "SUPABASE_URL") as? String,
            let key = Bundle.main.object(forInfoDictionaryKey: "SUPABASE_ANON_KEY") as? String,
            !urlString.isEmpty, !key.isEmpty,
            let url = URL(string: urlString)
        else { return nil }
        return SupabaseConfig(url: url, anonKey: key)
    }
}

/// Auth + outbox sync. Play never depends on this: games queue locally
/// (syncState = "local") and push whenever a session exists.
@Observable @MainActor
public final class SyncService {
    public enum SyncState: Sendable, Equatable {
        case notConfigured
        case signedOut
        case idle          // signed in, nothing to do
        case syncing
        case error(String)
    }

    public private(set) var state: SyncState
    public private(set) var userID: UUID?

    private let client: SupabaseClient?
    private let games: GameRepository
    private let ratingHistory: RatingHistoryRepository

    public init(
        config: SupabaseConfig?,
        games: GameRepository,
        ratingHistory: RatingHistoryRepository
    ) {
        self.games = games
        self.ratingHistory = ratingHistory
        if let config {
            client = SupabaseClient(supabaseURL: config.url, supabaseKey: config.anonKey)
            state = .signedOut
        } else {
            client = nil
            state = .notConfigured
        }
    }

    public var isConfigured: Bool { client != nil }
    public var isSignedIn: Bool { userID != nil }

    // MARK: - Feature flags / A/B experiments

    /// Resolved feature flags. Seeded from the last fetch's cache so gating
    /// is stable offline; `refreshFlags()` updates from the backend.
    public private(set) var flags: [String: Bool] =
        UserDefaults.standard.dictionary(forKey: "flags.cache") as? [String: Bool] ?? [:]

    /// Gate a feature: `if sync.flag("new_home") { ... }`. Unknown flags
    /// fall back to the given default, so code can ship before the
    /// app_config row exists.
    public func flag(_ key: String, default defaultValue: Bool = false) -> Bool {
        flags[key] ?? defaultValue
    }

    private struct AppConfigRow: Decodable, Sendable {
        let key: String
        let enabled: Bool
        let ab_split: Bool
    }

    /// Fetches app_config and resolves each flag: plain rows apply to
    /// everyone (remote toggle); ab_split rows put this install in a stable
    /// 50/50 variant. Resolved flags ride on every GA hit as user
    /// properties (flag_<key>), so cohorts are comparable in reports.
    public func refreshFlags() async {
        guard let client else { return }
        guard let rows: [AppConfigRow] = try? await client.from("app_config")
            .select("key, enabled, ab_split").execute().value
        else { return }
        var resolved: [String: Bool] = [:]
        for row in rows {
            resolved[row.key] = row.ab_split ? Self.abVariant(row.key) : row.enabled
        }
        flags = resolved
        UserDefaults.standard.set(resolved, forKey: "flags.cache")
    }

    /// Deterministic 50/50 assignment, stable per install and per flag
    /// (FNV-1a — Swift's Hashable is seeded per launch and can't be used).
    private static func abVariant(_ key: String) -> Bool {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in "\(analyticsDeviceID.uuidString):\(key)".utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return hash % 2 == 0
    }

    /// Restores an existing session (call at launch).
    public func restoreSession() async {
        guard let client else { return }
        if let session = try? await client.auth.session {
            userID = session.user.id
            state = .idle
        }
    }

    /// Anonymous account: full sync without any credentials; can be linked
    /// to Sign in with Apple later.
    public func signInAnonymously() async {
        guard let client else { return }
        do {
            let session = try await client.auth.signInAnonymously()
            await completeSignIn(userID: session.user.id, method: "anonymous")
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    /// Native Sign in with Apple: pass the identity token + nonce from
    /// ASAuthorizationAppleIDCredential.
    public func signInWithApple(idToken: String, nonce: String) async {
        guard let client else { return }
        do {
            let session = try await client.auth.signInWithIdToken(
                credentials: .init(provider: .apple, idToken: idToken, nonce: nonce)
            )
            await completeSignIn(userID: session.user.id, method: "apple")
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    /// The OAuth callback scheme; must be registered in the app's
    /// Info.plist and in the Supabase dashboard's redirect URLs.
    public static let oauthRedirectURL = URL(string: "chessmaster://auth-callback")!

    /// Google sign-in via the system web auth session (PKCE flow).
    public func signInWithGoogle() async {
        guard let client else { return }
        do {
            let session = try await client.auth.signInWithOAuth(
                provider: .google,
                redirectTo: Self.oauthRedirectURL
            ) { webSession in
                webSession.prefersEphemeralWebBrowserSession = false
            }
            await completeSignIn(userID: session.user.id, method: "google_web")
        } catch is CancellationError {
            // User closed the sheet; not an error state.
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    /// Native Google Sign-In: exchange the Google ID token for a session.
    public func signInWithGoogleIDToken(idToken: String, accessToken: String?) async {
        guard let client else { return }
        do {
            let session = try await client.auth.signInWithIdToken(
                credentials: .init(provider: .google, idToken: idToken, accessToken: accessToken)
            )
            await completeSignIn(userID: session.user.id, method: "google_native")
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    /// Password sign-in — only used by the UI-test fixture account.
    public func signInWithPassword(email: String, password: String) async {
        guard let client else { return }
        do {
            let session = try await client.auth.signIn(email: email, password: password)
            await completeSignIn(userID: session.user.id, method: "password")
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    /// Email sign-in, step 1: send a 6-digit code.
    public func sendEmailCode(to email: String) async throws {
        guard let client else { throw SyncError.notConfigured }
        try await client.auth.signInWithOTP(email: email, shouldCreateUser: true)
    }

    /// Email sign-in, step 2: verify the code.
    public func verifyEmailCode(email: String, code: String) async -> Bool {
        guard let client else { return false }
        do {
            let response = try await client.auth.verifyOTP(email: email, token: code, type: .email)
            guard let session = response.session else {
                state = .error("Verification did not return a session.")
                return false
            }
            await completeSignIn(userID: session.user.id, method: "email")
            return true
        } catch {
            state = .error("That code didn't work — check it and try again.")
            return false
        }
    }

    /// The signed-in account's email, if any (shown in the account UI).
    public var accountEmail: String? {
        get async {
            guard let client else { return nil }
            return try? await client.auth.session.user.email
        }
    }

    private func completeSignIn(userID: UUID, method: String) async {
        self.userID = userID
        track("signed_in", ["method": method])
        state = .idle
        await pullRemote()
        await pushPending()
    }

    // MARK: - Analytics

    /// Stable anonymous install identifier — ties pre-signup funnel events
    /// to the account the player eventually creates.
    public static let analyticsDeviceID: UUID = {
        let key = "analytics.deviceID"
        if let stored = UserDefaults.standard.string(forKey: key),
           let id = UUID(uuidString: stored) {
            return id
        }
        let id = UUID()
        UserDefaults.standard.set(id.uuidString, forKey: key)
        return id
    }()

    /// User-scoped GA properties attached to every hit ("premium" etc.).
    /// The app layer keeps these current; signed_in is derived here.
    public var userProperties: [String: String] = [:]

    /// Fire-and-forget product event, sent to Google Analytics only —
    /// nothing is stored in the backend. Never blocks or surfaces errors.
    public func track(_ name: String, _ props: [String: String] = [:]) {
        guard !ProcessInfo.processInfo.arguments.contains("--uitest") else { return }
        sendToGoogleAnalytics(name: name, props: props)
    }

    /// GA4 Measurement Protocol — plain HTTPS, no SDK (the Firebase
    /// Analytics binary is closed-source and GPL-incompatible with the
    /// Stockfish-derived client). Session params make events count toward
    /// GA4 realtime/engagement reports.
    private static let gaSessionID = String(Int(Date().timeIntervalSince1970))

    private func sendToGoogleAnalytics(name: String, props: [String: String]) {
        guard
            let measurementID = Bundle.main.object(forInfoDictionaryKey: "GA_MEASUREMENT_ID") as? String,
            let apiSecret = Bundle.main.object(forInfoDictionaryKey: "GA_API_SECRET") as? String,
            !measurementID.isEmpty, !apiSecret.isEmpty,
            var components = URLComponents(string: "https://www.google-analytics.com/mp/collect")
        else { return }
        components.queryItems = [
            .init(name: "measurement_id", value: measurementID),
            .init(name: "api_secret", value: apiSecret),
        ]
        guard let url = components.url else { return }

        var params: [String: Any] = [
            "session_id": Self.gaSessionID,
            "engagement_time_msec": "100",
        ]
        for (key, value) in props { params[key] = String(value.prefix(100)) }
        var body: [String: Any] = [
            "client_id": Self.analyticsDeviceID.uuidString,
            "events": [["name": name, "params": params]],
        ]
        if let userID {
            body["user_id"] = userID.uuidString
        }
        // GA caps user-property values at 36 chars and names at 24 —
        // keep flag keys to 19 chars or shorter.
        var properties: [String: [String: String]] = [
            "signed_in": ["value": isSignedIn ? "true" : "false"],
        ]
        for (key, value) in flags {
            properties["flag_\(String(key.prefix(19)))"] = ["value": value ? "true" : "false"]
        }
        for (key, value) in userProperties {
            properties[key] = ["value": String(value.prefix(36))]
        }
        body["user_properties"] = properties
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = data
        URLSession.shared.dataTask(with: request).resume()
    }

    /// Fetches an existing, completed coaching report for a game — read-only
    /// (RLS: own reports), never triggers generation. Returns the report
    /// JSON, or nil when no report exists yet.
    public func fetchCoachingReport(gameID: String) async -> Data? {
        guard let client, isSignedIn else { return nil }
        do {
            let response = try await client.from("coaching_reports")
                .select("report")
                .eq("game_id", value: gameID.lowercased())
                .eq("status", value: "complete")
                .limit(1)
                .execute()
            let rows = try JSONSerialization.jsonObject(with: response.data) as? [[String: Any]]
            guard let report = rows?.first?["report"], !(report is NSNull) else { return nil }
            return try JSONSerialization.data(withJSONObject: report)
        } catch {
            return nil
        }
    }

    /// Registers a purchased subscription server-side (best-effort; the
    /// App Store webhook is the durable path, this is the instant unlock).
    public func verifySubscription(jws: String) async {
        struct Response: Decodable, Sendable {}
        if !isSignedIn { await signInAnonymously() }
        guard isSignedIn else { return }
        do {
            let _: Response = try await invokeFunction(
                "verify-subscription",
                body: ["transaction_jws": jws]
            )
        } catch {
            // Local StoreKit-config transactions can't verify against Apple;
            // real purchases retry on next launch.
        }
    }

    public enum SignUpResult { case signedIn, confirmEmail, failed }

    /// Creates an email+password account. When the project requires email
    /// confirmation, the session arrives only after the user confirms.
    public func signUpWithPassword(email: String, password: String) async -> SignUpResult {
        guard let client else { return .failed }
        do {
            let response = try await client.auth.signUp(email: email, password: password)
            if let session = response.session {
                await completeSignIn(userID: session.user.id, method: "password_signup")
                return .signedIn
            }
            return .confirmEmail
        } catch {
            return .failed
        }
    }

    public func sendPasswordReset(to email: String) async -> Bool {
        guard let client else { return false }
        do {
            try await client.auth.resetPasswordForEmail(email)
            return true
        } catch {
            return false
        }
    }

    public func signOut() async {
        guard let client else { return }
        track("signed_out")   // before userID clears, so the hit carries it
        try? await client.auth.signOut()
        userID = nil
        state = .signedOut
    }

    // MARK: - Edge Functions

    /// Invokes a backend Edge Function with the caller's session JWT and
    /// decodes the JSON response.
    public func invokeFunction<Body: Encodable & Sendable, Result: Decodable & Sendable>(
        _ name: String,
        body: Body
    ) async throws -> Result {
        guard let client else { throw SyncError.notConfigured }
        return try await client.functions.invoke(
            name,
            options: FunctionInvokeOptions(body: body)
        )
    }

    public enum SyncError: Error {
        case notConfigured
    }

    // MARK: - Push

    /// Pushes locally finished games and rating rows (append-only upserts).
    /// One bad row must not strand the rest of the queue.
    /// Removes a game server-side (best-effort; RLS restricts to own rows).
    public func deleteRemoteGame(id: String) async {
        guard let client, isSignedIn else { return }
        try? await client.from("games").delete().eq("id", value: id.lowercased()).execute()
    }

    public func pushPending() async {
        guard let client, let userID else { return }
        state = .syncing
        var rowFailures = 0
        do {
            let pendingGames = try games.pendingSyncGames()
            for record in pendingGames {
                guard let remote = RemoteGame(record: record, userId: userID) else {
                    try games.markGameSynced(id: record.id)   // never retryable
                    continue
                }
                do {
                    try await client.from("games")
                        .upsert(remote, onConflict: "id")
                        .execute()
                    try games.markGameSynced(id: record.id)
                } catch {
                    rowFailures += 1   // stays pending; retried next push
                }
            }

            let syncedGameIDs = Set(
                try games.recentGames()
                    .filter { $0.syncState == "synced" }
                    .map { $0.id.lowercased() }
            )
            for record in try ratingHistory.pendingSync() {
                var remote = RemoteRatingHistory(record: record, userId: userID)
                // Drop dangling FKs (game failed to sync or was local-only).
                if let gameId = remote.gameId,
                   !syncedGameIDs.contains(gameId.uuidString.lowercased()) {
                    remote.gameId = nil
                }
                do {
                    try await client.from("rating_history").insert(remote).execute()
                    if let id = record.id {
                        try ratingHistory.markSynced(id: id)
                    }
                } catch {
                    rowFailures += 1
                }
            }
            state = rowFailures == 0 ? .idle : .error("\(rowFailures) items didn't sync; will retry.")
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    // MARK: - Pull

    // Class scope, not function-local: Swift 6.0 (CI's Xcode 16) rejects
    // local types in cross-isolation results that newer compilers accept.
    private struct PulledGame: Codable, Sendable {
        var id: UUID
        var opponentType: String
        var engineLevel: Int?
        var userColor: String
        var timeControl: String?
        var timeClass: String
        var result: String
        var termination: String?
        var pgn: String
        var evals: String?
        var ratingBefore: Double?
        var ratingAfter: Double?
        var playedAt: Date

        enum CodingKeys: String, CodingKey {
            case id
            case opponentType = "opponent_type"
            case engineLevel = "engine_level"
            case userColor = "user_color"
            case timeControl = "time_control"
            case timeClass = "time_class"
            case result, termination, pgn, evals
            case ratingBefore = "rating_before"
            case ratingAfter = "rating_after"
            case playedAt = "played_at"
        }
    }

    /// Restores games/rating history from the backend (sign-in on a fresh
    /// install). Local rows win on ID collision.
    public func pullRemote() async {
        guard let client, userID != nil else { return }
        state = .syncing
        do {
            let pulled: [PulledGame] = try await client.from("games")
                .select()
                .order("played_at", ascending: false)
                .limit(500)
                .execute()
                .value

            let localIDs = Set(try games.recentGames(limit: 10_000).map { $0.id.lowercased() })
            for remote in pulled where !localIDs.contains(remote.id.uuidString.lowercased()) {
                var tcInitial: Int?
                var tcIncrement: Int?
                if let tc = remote.timeControl {
                    let parts = tc.split(separator: "+").compactMap { Int($0) }
                    if parts.count == 2 { tcInitial = parts[0]; tcIncrement = parts[1] }
                }
                let record = GameRecord(
                    id: remote.id.uuidString,
                    startedAt: remote.playedAt,
                    endedAt: remote.playedAt,
                    opponentType: remote.opponentType == "engine" ? "engine" : "humanLocal",
                    engineLevel: remote.engineLevel,
                    playerColor: remote.userColor,
                    tcInitialSeconds: tcInitial,
                    tcIncrementSeconds: tcIncrement,
                    result: {
                        switch remote.result {
                        case "white_win": "whiteWin"
                        case "black_win": "blackWin"
                        case "draw": "draw"
                        default: "aborted"
                        }
                    }(),
                    termination: remote.termination,
                    pgn: remote.pgn,
                    finalFEN: GameReplay(pgn: remote.pgn)?.plies.last?.fenAfter ?? "",
                    ratingCategory: remote.ratingBefore != nil ? remote.timeClass : nil,
                    ratingBefore: remote.ratingBefore,
                    ratingAfter: remote.ratingAfter,
                    syncState: "synced",
                    analysisJSON: remote.evals
                )
                try games.save(record)
            }
            state = .idle
        } catch {
            state = .error(error.localizedDescription)
        }
    }
}
