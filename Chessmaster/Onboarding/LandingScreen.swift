// Chessmaster — GPL-3.0-or-later
import SwiftUI
import AVFoundation
import BoardUI
import ChessDomain

/// Cal AI-style landing: a device-framed looping demo video, a big headline,
/// Get Started into the quiz, and a Sign In path for existing users. Shown
/// once, before the quiz, on first launch. The demo clip is bundled as
/// Resources/Onboarding/landing-demo.mp4; until it exists the frame shows a
/// live-looking static board instead.
struct LandingScreen: View {
    @Environment(DependencyContainer.self) private var container
    let onGetStarted: () -> Void
    /// Existing user signed in from here — skip the quiz and the paywall.
    let onSignedIn: () -> Void

    @State private var showSignIn = false

    private static let demoURL = Bundle.main.url(
        forResource: "landing-demo", withExtension: "mp4")

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)

            phoneFrame
                .frame(maxHeight: .infinity)

            Text("Chess coaching\nmade easy")
                .font(.system(size: 34, weight: .bold))
                .multilineTextAlignment(.center)
                .padding(.top, 28)

            CapsuleCTA(label: "Get Started", enabled: true) {
                container.sync.track("landing_get_started")
                onGetStarted()
            }
            .padding(.top, 18)

            HStack(spacing: 4) {
                Text("Already have an account?")
                    .foregroundStyle(.secondary)
                Button("Sign In") {
                    container.sync.track("landing_signin_tapped")
                    showSignIn = true
                }
                .fontWeight(.semibold)
                .buttonStyle(.plain)
            }
            .font(.subheadline)
            .padding(.bottom, 10)
            .accessibilityIdentifier("landingSignIn")
        }
        .onAppear { container.sync.track("landing_viewed") }
        .sheet(isPresented: $showSignIn) {
            AccountSheet(sync: container.sync)
                .onDisappear {
                    if container.sync.isSignedIn { onSignedIn() }
                }
        }
    }

    /// Rounded black "device" bezel holding the demo loop (or the board).
    private var phoneFrame: some View {
        ZStack {
            if let url = Self.demoURL {
                LoopingVideoView(url: url)
            } else {
                placeholderBoard
            }
        }
        .aspectRatio(9.0 / 17.5, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 34))
        .padding(6)
        .background(Color.black, in: RoundedRectangle(cornerRadius: 40))
        .padding(.horizontal, 64)
    }

    /// Static mid-game position with a coach's suggestion arrow — stands in
    /// for the demo video and still reads as "the app, playing".
    private var placeholderBoard: some View {
        // Italian Game middlegame — visually busy without looking random.
        let session = GameSession(config: GameConfig(
            startFEN: "r1bq1rk1/pppp1ppp/2n2n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 1",
            opponent: .humanLocal))
        return VStack {
            Spacer()
            BoardView(
                pieces: session.boardPieces,
                arrows: [BoardArrow(from: .c1, to: .g5, color: .green.opacity(0.8))],
                showsCoordinates: false,
                interactive: false
            )
            Spacer()
        }
        .background(Color(.systemGray6))
    }
}

/// Muted, aspect-filling, endlessly looping video (AVPlayerLooper).
private struct LoopingVideoView: UIViewRepresentable {
    let url: URL

    final class PlayerContainerView: UIView {
        let queuePlayer = AVQueuePlayer()
        var looper: AVPlayerLooper?
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerContainerView {
        let view = PlayerContainerView()
        view.playerLayer.player = view.queuePlayer
        view.playerLayer.videoGravity = .resizeAspectFill
        view.queuePlayer.isMuted = true
        view.looper = AVPlayerLooper(
            player: view.queuePlayer, templateItem: AVPlayerItem(url: url))
        view.queuePlayer.play()
        return view
    }

    func updateUIView(_ view: PlayerContainerView, context: Context) {}
}
