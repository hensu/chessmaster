// Chessmaster — GPL-3.0-or-later
//
// Live friend games. The backend row (SupabaseSync/FriendGames.swift) owns
// the moves and clocks; FriendGameLink follows it (Realtime + a slow safety
// poll) and hands the friend's moves to GameSession as an OpponentProvider,
// so GameViewModel/GameScreen run friend games like any other.

import Foundation
import ChessDomain
import ClockKit
import SupabaseSync

@Observable @MainActor
final class FriendGameLink {
    private(set) var state: FriendGameState
    let myID: UUID
    let sync: SyncService
    /// Called after every fresh server state (clock sync, remote endings).
    var onState: ((FriendGameState) -> Void)?

    private var watchTask: Task<Void, Never>?
    private var pollTask: Task<Void, Never>?
    private var waiters: [Int: CheckedContinuation<String, Error>] = [:]

    init(state: FriendGameState, myID: UUID, sync: SyncService) {
        self.state = state
        self.myID = myID
        self.sync = sync
    }

    var myColor: Piece.Color { state.color(of: myID) == "black" ? .black : .white }
    var opponentName: String { state.opponentName(for: myID) }

    var config: GameConfig {
        GameConfig(
            playerColor: myColor,
            timeControl: TimeControl(
                initial: .milliseconds(state.initialMs),
                increment: .milliseconds(state.incrementMs)),
            opponent: .friend)
    }

    func start() {
        guard watchTask == nil else { return }
        watchTask = sync.watchFriendGame(id: state.id) { [weak self] in
            Task { await self?.refresh() }
        }
        // Realtime can drop (backgrounding, flaky network): re-read the row
        // every few seconds too. Cheap, and it makes the game self-healing.
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard let self, !self.state.isOver else { return }
                await self.refresh()
            }
        }
    }

    func stop() {
        watchTask?.cancel()
        pollTask?.cancel()
        watchTask = nil
        pollTask = nil
        for waiter in waiters.values { waiter.resume(throwing: CancellationError()) }
        waiters = [:]
    }

    func refresh() async {
        guard let fresh = try? await sync.friendGame(id: state.id) else { return }
        apply(fresh)
    }

    func apply(_ fresh: FriendGameState) {
        // Never step backwards (an older response landing late).
        guard fresh.moves.count >= state.moves.count || fresh.isOver else { return }
        state = fresh
        for (ply, waiter) in waiters where ply <= fresh.moves.count {
            waiters[ply] = nil
            waiter.resume(returning: fresh.moves[ply - 1])
        }
        onState?(fresh)
    }

    /// The friend's move at `ply` (1-based), once it exists.
    func move(atPly ply: Int) async throws -> String {
        if ply <= state.moves.count { return state.moves[ply - 1] }
        return try await withCheckedThrowingContinuation { waiters[ply] = $0 }
    }

    func send(ply: Int, uci: String) async {
        do {
            apply(try await sync.sendFriendMove(gameID: state.id, ply: ply, uci: uci))
        } catch {
            // Out of sync or offline: the server copy wins.
            await refresh()
        }
    }

    func end(termination: String) async {
        if let fresh = try? await sync.endFriendGame(gameID: state.id, termination: termination) {
            apply(fresh)
        }
    }

    /// A clock hit zero locally. The server decides; retry briefly while
    /// its clock catches up with ours (network latency).
    func claimTimeout() async {
        for _ in 0..<8 {
            await end(termination: "timeout")
            if state.isOver { return }
            try? await Task.sleep(for: .seconds(1))
        }
    }
}

/// Feeds the friend's moves into GameSession.
struct FriendOpponent: OpponentProvider {
    let link: FriendGameLink

    func nextMove(fen: String, remainingTime: Duration?) async throws -> OpponentMove {
        // Ply about to be played, from the FEN's side to move + move number.
        let fields = fen.split(separator: " ")
        let fullMove = fields.count > 5 ? Int(fields[5]) ?? 1 : 1
        let ply = (fullMove - 1) * 2 + (fields.count > 1 && fields[1] == "b" ? 2 : 1)
        let uci = try await link.move(atPly: ply)
        guard let move = OpponentMove(uci: uci) else { throw CancellationError() }
        return move
    }
}

extension FriendGameState {
    /// Server result → domain result, for endings the board can't see
    /// (resignation, timeout, abort).
    var domainResult: GameResult? {
        if status == "aborted" { return .aborted }
        guard status == "finished", let result else { return nil }
        let winner: Piece.Color? = result == "whiteWin" ? .white : result == "blackWin" ? .black : nil
        switch (winner, termination) {
        case (let w?, "resignation"): return .win(w, .resignation)
        case (let w?, "timeout"): return .win(w, .timeout)
        case (let w?, _): return .win(w, .checkmate)
        default: return .draw(GameResult.DrawReason(rawValue: termination ?? "") ?? .agreement)
        }
    }
}

/// Invite links: https://getchessai.com/play/?g=CODE (universal link) or
/// chessmaster://play?g=CODE (the web page's "Open in app" button).
enum FriendInvite {
    static func code(from url: URL) -> String? {
        let isWeb = url.host?.hasSuffix("getchessai.com") == true && url.path.hasPrefix("/play")
        let isScheme = url.scheme == "chessmaster" && url.host == "play"
        guard isWeb || isScheme,
              let code = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "g" })?.value,
              !code.isEmpty
        else { return nil }
        return code.uppercased()
    }

    static func message(for state: FriendGameState) -> String {
        let minutes = state.initialMs / 60_000
        let inc = state.incrementMs / 1000
        return "Play me at chess! \(minutes)+\(inc) on Chess AI ♟️ Finish the game and we both get a free month of Diamond. \(state.inviteURL.absoluteString)"
    }
}

#if DEBUG
/// Screenshot-only sample data: `--preview-friend-reward` shows a reward
/// card on Home, `--preview-friend-waiting` opens the lobby's waiting screen.
enum FriendPreview {
    static var reward: ReferralReward? {
        guard ProcessInfo.processInfo.arguments.contains("--preview-friend-reward") else { return nil }
        return decode(#"{"code":"PREVIEW","role":"inviter","assigned_at":"\#(Date.now.ISO8601Format())"}"#)
    }

    static var waitingGame: FriendGameState? {
        guard ProcessInfo.processInfo.arguments.contains("--preview-friend-waiting") else { return nil }
        return decode(#"""
        {"id":"\#(UUID().uuidString)","code":"K7QX2M","host_id":"\#(UUID().uuidString)","guest_id":null,
         "host_color":"white","initial_ms":600000,"increment_ms":0,"status":"waiting","moves":[],
         "white_ms":600000,"black_ms":600000,"turn_elapsed_ms":0,"result":null,"termination":null,
         "host_name":"Raj","guest_name":null}
        """#)
    }

    private static func decode<T: Decodable>(_ json: String) -> T? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(T.self, from: Data(json.utf8))
    }
}
#endif
