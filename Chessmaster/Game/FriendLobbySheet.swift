// Chessmaster — GPL-3.0-or-later
import SwiftUI
import ChessDomain
import SupabaseSync

/// "Play a friend": pick a time control, send the invite link by text, wait
/// for the friend to open it. Also joins by code. When the friend arrives the
/// game opens app-wide (DependencyContainer.activeFriendGame).
struct FriendLobbySheet: View {
    /// An invite already created (e.g. the host tapped their own link).
    var initial: FriendGameState? = nil
    @Environment(DependencyContainer.self) private var container
    @Environment(\.dismiss) private var dismiss

    @State private var timeControl = TimeControl(minutes: 10, incrementSeconds: 0)
    @State private var colorChoice = "random"
    @State private var game: FriendGameState?
    @State private var busy = false
    @State private var errorMessage: String?
    @State private var joinCode = ""
    @State private var watchTask: Task<Void, Never>?
    @State private var pollTask: Task<Void, Never>?
    @State private var showSignIn = false

    private static let choices: [TimeControl] = [
        TimeControl(minutes: 3, incrementSeconds: 2),
        TimeControl(minutes: 5, incrementSeconds: 0),
        TimeControl(minutes: 10, incrementSeconds: 0),
        TimeControl(minutes: 15, incrementSeconds: 10),
    ]

    var body: some View {
        NavigationStack {
            Group {
                if let game {
                    waiting(game)
                } else {
                    setup
                }
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Play a Friend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(game == nil ? "Close" : "Cancel invite") { cancel() }
                }
            }
        }
        .trackScreen("FriendLobby")
        .onAppear {
            if let initial, game == nil { waitFor(initial) }
        }
        .onDisappear { stopWatching() }
        .sheet(isPresented: $showSignIn) {
            AccountSheet(sync: container.sync, subtitle: "Sign in so your friend can find you.")
        }
    }

    // MARK: - Setup

    private var setup: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Send a link by text — your friend taps it and the game starts. Friend games are unrated, and you both get the free game review afterwards.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Text("Time control").font(.headline)
            HStack(spacing: 8) {
                ForEach(Self.choices, id: \.self) { tc in
                    Button {
                        timeControl = tc
                    } label: {
                        VStack(spacing: 2) {
                            Text(tc.label).font(.headline.monospacedDigit())
                            Text(tc.category.rawValue.capitalized).font(.caption2)
                        }
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(
                            timeControl == tc ? Color.green.opacity(0.25) : Color(.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10)
                            .stroke(timeControl == tc ? Color.green : .clear, lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                }
            }

            Text("You play").font(.headline)
            Picker("You play", selection: $colorChoice) {
                Text("Random").tag("random")
                Text("White").tag("white")
                Text("Black").tag("black")
            }
            .pickerStyle(.segmented)

            Button {
                create()
            } label: {
                HStack {
                    if busy { ProgressView().tint(.white) }
                    Label("Create invite link", systemImage: "message.fill")
                }
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .controlSize(.large)
            .disabled(busy)

            if let errorMessage {
                Text(errorMessage).font(.footnote).foregroundStyle(.red)
            }

            Divider().padding(.vertical, 4)

            Text("Got a code from a friend?").font(.headline)
            HStack {
                TextField("ABC123", text: $joinCode)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.title3.monospaced())
                    .padding(10)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
                Button("Join") { container.pendingInviteCode = joinCode.uppercased(); dismiss() }
                    .buttonStyle(.bordered)
                    .disabled(joinCode.trimmingCharacters(in: .whitespaces).count < 6)
            }
        }
    }

    // MARK: - Waiting for the friend

    private func waiting(_ game: FriendGameState) -> some View {
        VStack(spacing: 18) {
            ProgressView().controlSize(.large).padding(.top, 24)
            Text("Waiting for your friend…")
                .font(.title3.bold())
            Text("The game starts as soon as they open the link. Keep this screen open.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Text(game.code)
                .font(.system(size: 34, weight: .bold, design: .monospaced))
                .kerning(4)
                .padding(.vertical, 8)
                .padding(.horizontal, 20)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))

            ShareLink(item: game.inviteURL, message: Text(FriendInvite.message(for: game))) {
                Label("Send invite", systemImage: "square.and.arrow.up")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .controlSize(.large)

            Text("🎁 If they're new to Chess AI and you finish a game, you both get a free month of Diamond.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    // MARK: - Actions

    private func create() {
        guard container.sync.isSignedIn else { showSignIn = true; return }
        busy = true
        errorMessage = nil
        let color = colorChoice == "random" ? (Bool.random() ? "white" : "black") : colorChoice
        Task {
            defer { busy = false }
            do {
                let state = try await container.sync.createFriendGame(
                    initialSeconds: Int(timeControl.initial.components.seconds),
                    incrementSeconds: Int(timeControl.increment.components.seconds),
                    hostColor: color)
                container.sync.track("friend_invite_created", ["time_control": timeControl.label])
                waitFor(state)
            } catch let error as FriendGameError {
                errorMessage = error.message
            } catch {
                errorMessage = FriendGameError.other("").message
            }
        }
    }

    private func waitFor(_ state: FriendGameState) {
        game = state
        let check: @MainActor () async -> Void = {
            guard let fresh = try? await container.sync.friendGame(id: state.id) else { return }
            game = fresh
            if fresh.status == "active" {
                stopWatching()
                container.startFriendGame(fresh)
                dismiss()
            } else if fresh.isOver {
                stopWatching()
                self.game = nil
            }
        }
        watchTask = container.sync.watchFriendGame(id: state.id) { Task { await check() } }
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(3))
                await check()
            }
        }
    }

    private func stopWatching() {
        watchTask?.cancel()
        pollTask?.cancel()
    }

    private func cancel() {
        stopWatching()
        if let game, game.status == "waiting" {
            let sync = container.sync
            Task { _ = try? await sync.endFriendGame(gameID: game.id, termination: "aborted") }
        }
        dismiss()
    }
}
