// SupabaseSync — Chessmaster
// GPL-3.0-or-later
//
// Live friend games (invite link → game). The backend row is the source of
// truth (moves + server clocks, migration 0012); every write is an RPC that
// checks turn order and time. Realtime only pokes us that the row changed —
// we then re-read it through `friend_game_state`, which adds names and the
// server's clock reading.

import Foundation
import Supabase

public struct FriendGameState: Decodable, Sendable, Hashable, Identifiable {
    public let id: UUID
    public let code: String
    public let hostID: UUID
    public let guestID: UUID?
    public let hostColor: String
    public let initialMs: Int
    public let incrementMs: Int
    /// waiting | active | finished | aborted
    public let status: String
    public let moves: [String]
    public let whiteMs: Int
    public let blackMs: Int
    /// How long the side to move has been thinking (server clock).
    public let turnElapsedMs: Int
    /// whiteWin | blackWin | draw
    public let result: String?
    public let termination: String?
    public let hostName: String
    public let guestName: String?

    enum CodingKeys: String, CodingKey {
        case id, code, status, moves, result, termination
        case hostID = "host_id"
        case guestID = "guest_id"
        case hostColor = "host_color"
        case initialMs = "initial_ms"
        case incrementMs = "increment_ms"
        case whiteMs = "white_ms"
        case blackMs = "black_ms"
        case turnElapsedMs = "turn_elapsed_ms"
        case hostName = "host_name"
        case guestName = "guest_name"
    }

    public var isOver: Bool { status == "finished" || status == "aborted" }

    /// "white"/"black" for the given player.
    public func color(of user: UUID) -> String {
        user == hostID ? hostColor : (hostColor == "white" ? "black" : "white")
    }

    public func opponentName(for user: UUID) -> String {
        user == hostID ? (guestName ?? "Friend") : hostName
    }

    /// A direct challenge the given user hasn't accepted yet.
    public func isChallenge(for user: UUID) -> Bool {
        status == "waiting" && guestID == user
    }

    public var timeControlLabel: String {
        "\(initialMs / 60_000)+\(incrementMs / 1000)"
    }

    public var inviteURL: URL {
        URL(string: "https://getchessai.com/play/?g=\(code)")!
    }
}

/// Someone the user has played before (Play a Friend → "Play again").
public struct RecentFriend: Decodable, Sendable, Hashable, Identifiable {
    public let id: UUID
    public let name: String
    public let games: Int
}

/// A free month earned by inviting a friend (App Store one-time offer code).
public struct ReferralReward: Decodable, Sendable, Hashable, Identifiable {
    public let code: String
    public let role: String
    public let assignedAt: Date
    public var id: String { code }

    /// Rewards must be claimed within this window (urgency; the App Store
    /// code itself is valid much longer).
    public static let claimWindow: TimeInterval = 30 * 86_400
    public var claimBy: Date { assignedAt.addingTimeInterval(Self.claimWindow) }

    enum CodingKeys: String, CodingKey {
        case code, role
        case assignedAt = "assigned_at"
    }

    /// Opens the App Store's redeem sheet with the code pre-filled.
    public func redeemURL(appID: String) -> URL {
        URL(string: "https://apps.apple.com/redeem?ctx=offercodes&id=\(appID)&code=\(code)")!
    }
}

public enum FriendGameError: Error, Equatable {
    case notSignedIn
    case notFound
    case gameFull
    case gameClosed
    case outOfSync
    case other(String)

    init(_ error: Error) {
        let text = String(describing: error)
        if text.contains("not_signed_in") { self = .notSignedIn }
        else if text.contains("not_found") { self = .notFound }
        else if text.contains("game_full") { self = .gameFull }
        else if text.contains("game_closed") { self = .gameClosed }
        else if text.contains("out_of_sync") || text.contains("not_your_turn") { self = .outOfSync }
        else { self = .other(error.localizedDescription) }
    }

    public var message: String {
        switch self {
        case .notSignedIn: "Sign in to play friends."
        case .notFound: "That invite link isn't valid."
        case .gameFull: "Someone else already joined this game."
        case .gameClosed: "This invite has expired. Ask your friend for a new one."
        case .outOfSync: "The game moved on — refreshing."
        case .other: "Couldn't reach the server. Check your connection and try again."
        }
    }
}

extension SyncService {
    private func rpc<Params: Encodable & Sendable>(_ fn: String, _ params: Params) async throws -> FriendGameState {
        guard let client, isSignedIn else { throw FriendGameError.notSignedIn }
        do {
            return try await client.rpc(fn, params: params).execute().value
        } catch {
            throw FriendGameError(error)
        }
    }

    /// `opponent`: challenge someone already played (they accept from Home)
    /// instead of an open invite link.
    public func createFriendGame(initialSeconds: Int, incrementSeconds: Int, hostColor: String,
                                 opponent: UUID? = nil) async throws -> FriendGameState {
        try await rpc("friend_game_create", [
            "p_initial_ms": AnyJSON.integer(initialSeconds * 1000),
            "p_increment_ms": AnyJSON.integer(incrementSeconds * 1000),
            "p_host_color": AnyJSON.string(hostColor),
            "p_opponent": opponent.map { AnyJSON.string($0.uuidString) } ?? AnyJSON.null,
        ])
    }

    public func recentFriends() async -> [RecentFriend] {
        guard let client, isSignedIn else { return [] }
        return (try? await client.rpc("friend_recent_opponents").execute().value) ?? []
    }

    public func joinFriendGame(code: String) async throws -> FriendGameState {
        try await rpc("friend_game_join", ["p_code": code])
    }

    public func friendGame(id: UUID) async throws -> FriendGameState {
        try await rpc("friend_game_state", ["p_id": id.uuidString])
    }

    public func sendFriendMove(gameID: UUID, ply: Int, uci: String) async throws -> FriendGameState {
        try await rpc("friend_game_move", [
            "p_id": AnyJSON.string(gameID.uuidString),
            "p_ply": AnyJSON.integer(ply),
            "p_uci": AnyJSON.string(uci),
        ])
    }

    /// `termination`: resignation | timeout | aborted | checkmate | a draw reason.
    public func endFriendGame(gameID: UUID, termination: String) async throws -> FriendGameState {
        try await rpc("friend_game_end", [
            "p_id": AnyJSON.string(gameID.uuidString),
            "p_result": AnyJSON.null,
            "p_termination": AnyJSON.string(termination),
        ])
    }

    /// Games the user is still in (active, or an invite waiting for a friend).
    public func openFriendGames() async -> [FriendGameState] {
        guard let client, isSignedIn else { return [] }
        return (try? await client.rpc("friend_games_open").execute().value) ?? []
    }

    /// Pokes `onChange` whenever the game row changes. Cancel the returned
    /// task to unsubscribe.
    public func watchFriendGame(id: UUID, onChange: @escaping @MainActor @Sendable () -> Void) -> Task<Void, Never>? {
        guard let client else { return nil }
        return Task {
            let channel = client.channel("friend-game-\(id.uuidString.lowercased())")
            let changes = channel.postgresChange(
                UpdateAction.self, schema: "public", table: "friend_games",
                filter: .eq("id", value: id.uuidString.lowercased()))
            await channel.subscribe()
            for await _ in changes {
                if Task.isCancelled { break }
                await onChange()
            }
            await client.removeChannel(channel)
        }
    }

    // MARK: - Referral rewards

    public func unredeemedReferralRewards() async -> [ReferralReward] {
        guard let client, isSignedIn else { return [] }
        let since = Date.now.addingTimeInterval(-ReferralReward.claimWindow)
        return (try? await client.from("referral_codes")
            .select("code, role, assigned_at")
            .is("redeemed_at", value: nil)
            .gte("assigned_at", value: since.ISO8601Format())
            .execute().value) ?? []
    }

    public func markReferralRedeemed(code: String) async {
        guard let client, isSignedIn else { return }
        _ = try? await client.rpc("referral_code_mark_redeemed", params: ["p_code": code]).execute()
    }
}
