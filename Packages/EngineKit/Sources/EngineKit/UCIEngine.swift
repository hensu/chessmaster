// EngineKit — Chessmaster
// GPL-3.0-or-later

import Foundation
import StockfishCpp

public enum UCIEngineError: Error, Sendable {
    case startFailed
    case notStarted
    case timeout(command: String)
    case noBestMove
}

/// One line of `info ...` output from a search, reduced to what the app uses.
public struct SearchInfo: Sendable {
    public var depth: Int?
    /// Centipawns from the side to move's perspective.
    public var scoreCp: Int?
    /// Mate in N (negative: getting mated).
    public var scoreMate: Int?
    /// 1-based MultiPV rank (nil when MultiPV is off).
    public var multipv: Int?
    public var pv: [String] = []
}

/// One engine candidate move from a MultiPV search.
public struct CandidateMove: Sendable {
    public let moveUCI: String
    public let scoreCp: Int?
    public let scoreMate: Int?

    /// Comparable value in centipawns; mates saturate the scale.
    public var value: Double {
        if let scoreMate { return scoreMate > 0 ? 30_000 : -30_000 }
        return Double(scoreCp ?? 0)
    }
}

public struct SearchResult: Sendable {
    public let bestMoveUCI: String
    /// Last info line seen before bestmove (deepest completed search).
    public let info: SearchInfo
}

/// Serialized access to the single embedded Stockfish instance.
/// The process-wide stdio redirection in the bridge means there can only
/// ever be one engine; this actor is that engine's owner.
public actor UCIEngine {
    public static let shared = UCIEngine()

    private var started = false
    private var commandFD: Int32 = -1
    private var currentOptions: [String: String] = [:]

    // Line mailbox: one detached reader task pushes engine output here;
    // `nextLine` consumers pop (or park a continuation when empty).
    private var pendingLines: [String] = []
    private var waiter: (id: UUID, continuation: CheckedContinuation<String?, Never>)?
    private var readerTask: Task<Void, Never>?

    private init() {}

    // MARK: - Lifecycle

    /// Starts the engine thread and completes the UCI handshake.
    /// Safe to call repeatedly.
    public func startIfNeeded(evalFileBig: URL, evalFileSmall: URL) async throws {
        guard !started else { return }
        guard cm_stockfish_start() == 0 else { throw UCIEngineError.startFailed }
        let handle = FileHandle(fileDescriptor: cm_stockfish_output_fd(), closeOnDealloc: false)
        commandFD = cm_stockfish_command_fd()
        readerTask = Task.detached { [weak self] in
            do {
                for try await line in handle.bytes.lines {
                    await self?.deliver(line)
                }
            } catch {}
            await self?.deliver(nil)
        }
        started = true

        send("uci")
        _ = try await waitFor(timeoutMs: 5000, command: "uci") { $0 == "uciok" }
        send("setoption name EvalFile value \(evalFileBig.path)")
        send("setoption name EvalFileSmall value \(evalFileSmall.path)")
        send("setoption name Threads value 2")
        send("setoption name Hash value 32")
        try await sync()
    }

    /// Blocks until the engine has processed everything sent so far.
    public func sync() async throws {
        send("isready")
        _ = try await waitFor(timeoutMs: 10000, command: "isready") { $0 == "readyok" }
    }

    public func newGame() async throws {
        try ensureStarted()
        send("ucinewgame")
        try await sync()
    }

    /// Interrupts a running search (e.g. when backgrounding).
    public func stopSearch() {
        guard started else { return }
        send("stop")
    }

    // MARK: - Options

    public func apply(options: [String: String]) async throws {
        try ensureStarted()
        var changed = false
        for (name, value) in options.sorted(by: { $0.key < $1.key }) where currentOptions[name] != value {
            send("setoption name \(name) value \(value)")
            currentOptions[name] = value
            changed = true
        }
        if changed { try await sync() }
    }

    // MARK: - Search

    /// Search a position given by FEN. `movetimeMs` bounds wall time;
    /// `depth` optionally caps depth (whichever stops first).
    public func search(fen: String, movetimeMs: Int, depth: Int? = nil) async throws -> SearchResult {
        try ensureStarted()
        send("position fen \(fen)")
        var go = "go movetime \(movetimeMs)"
        if let depth { go += " depth \(depth)" }
        send(go)

        var lastInfo = SearchInfo()
        let line = try await waitFor(timeoutMs: movetimeMs + 10000, command: go) { line in
            if line.hasPrefix("info "), let parsed = Self.parseInfo(line) {
                lastInfo = parsed
            }
            return line.hasPrefix("bestmove")
        }
        let parts = line.split(separator: " ")
        guard parts.count >= 2, parts[1] != "(none)" else { throw UCIEngineError.noBestMove }
        return SearchResult(bestMoveUCI: String(parts[1]), info: lastInfo)
    }

    /// MultiPV search: the engine's top candidate moves with evaluations,
    /// deepest iteration wins. MultiPV is reset to 1 afterwards so other
    /// consumers (analysis, single-move search) are unaffected.
    public func searchCandidates(
        fen: String, movetimeMs: Int, depth: Int? = nil, count: Int
    ) async throws -> [CandidateMove] {
        try ensureStarted()
        try await apply(options: ["MultiPV": "\(count)"])
        defer { Task { try? await self.apply(options: ["MultiPV": "1"]) } }

        send("position fen \(fen)")
        var go = "go movetime \(movetimeMs)"
        if let depth { go += " depth \(depth)" }
        send(go)

        var byRank: [Int: SearchInfo] = [:]
        _ = try await waitFor(timeoutMs: movetimeMs + 10000, command: go) { line in
            if line.hasPrefix("info "), let parsed = Self.parseInfo(line),
               !parsed.pv.isEmpty {
                byRank[parsed.multipv ?? 1] = parsed
            }
            return line.hasPrefix("bestmove")
        }
        return byRank.keys.sorted().compactMap { rank in
            guard let info = byRank[rank], let move = info.pv.first else { return nil }
            return CandidateMove(moveUCI: move, scoreCp: info.scoreCp, scoreMate: info.scoreMate)
        }
    }

    // MARK: - Plumbing

    private func ensureStarted() throws {
        guard started else { throw UCIEngineError.notStarted }
    }

    private func send(_ command: String) {
        let data = Array((command + "\n").utf8)
        data.withUnsafeBufferPointer { buffer in
            var remaining = buffer.count
            var pointer = buffer.baseAddress!
            while remaining > 0 {
                let written = write(commandFD, pointer, remaining)
                guard written > 0 else { return }
                remaining -= written
                pointer += written
            }
        }
    }

    /// Reads lines (feeding each to `handle`) until it returns true;
    /// returns the matching line. Throws on timeout.
    private func waitFor(
        timeoutMs: Int,
        command: String,
        handle: (String) -> Bool
    ) async throws -> String {
        let deadline = ContinuousClock.now + .milliseconds(timeoutMs)
        while ContinuousClock.now < deadline {
            guard let line = try await nextLine(before: deadline) else { break }
            if handle(line) { return line }
        }
        throw UCIEngineError.timeout(command: command)
    }

    /// Called by the reader task for every output line (nil = EOF).
    private func deliver(_ line: String?) {
        if let waiter {
            self.waiter = nil
            waiter.continuation.resume(returning: line)
        } else if let line {
            pendingLines.append(line)
        }
    }

    private func nextLine(before deadline: ContinuousClock.Instant) async throws -> String? {
        if !pendingLines.isEmpty { return pendingLines.removeFirst() }
        let id = UUID()
        return await withCheckedContinuation { continuation in
            waiter = (id, continuation)
            Task { [weak self] in
                try? await Task.sleep(until: deadline, clock: .continuous)
                await self?.expireWaiter(id: id)
            }
        }
    }

    /// Resolves a parked waiter with nil if it is still waiting at its deadline.
    private func expireWaiter(id: UUID) {
        guard let waiter, waiter.id == id else { return }
        self.waiter = nil
        waiter.continuation.resume(returning: nil)
    }

    static func parseInfo(_ line: String) -> SearchInfo? {
        var info = SearchInfo()
        let tokens = line.split(separator: " ").map(String.init)
        var i = 0
        while i < tokens.count {
            switch tokens[i] {
            case "depth" where i + 1 < tokens.count:
                info.depth = Int(tokens[i + 1]); i += 2
            case "multipv" where i + 1 < tokens.count:
                info.multipv = Int(tokens[i + 1]); i += 2
            case "score" where i + 2 < tokens.count:
                if tokens[i + 1] == "cp" { info.scoreCp = Int(tokens[i + 2]) }
                if tokens[i + 1] == "mate" { info.scoreMate = Int(tokens[i + 2]) }
                i += 3
            case "pv":
                info.pv = Array(tokens[(i + 1)...])
                i = tokens.count
            default:
                i += 1
            }
        }
        return info.depth == nil && info.scoreCp == nil && info.scoreMate == nil && info.pv.isEmpty
            ? nil : info
    }
}
