// Chessmaster — GPL-3.0-or-later
import SwiftUI
import BoardUI
import ChessDomain
import ChessKit

/// Cal AI-style onboarding: one question per screen (back arrow + progress
/// bar, big left-aligned headline, option cards, pinned capsule CTA), a
/// computed "chess score" reveal, a promise, then account creation. The
/// quiz runs once; sign-in greets every signed-out launch after that.
struct WelcomeScreen: View {
    @Environment(DependencyContainer.self) private var container
    let onDone: () -> Void

    @AppStorage("onboarding.quizDone") private var quizDone = false
    @AppStorage("onboarding.selfRating") private var selfRating = 0
    @AppStorage("onboarding.goal") private var goal = ""

    private enum Step: Int, CaseIterable {
        case goal, experience, rating, opening, midgame, endgame, hero, rivals
        case computing, score, promise, signup, missingOut
    }
    @State private var step: Step = .goal

    // Quiz answers.
    @State private var experience = ""
    @State private var sliderRating = 800.0
    @State private var notSure = true
    @State private var opening = ""
    @State private var midgame = ""
    @State private var endgame = ""
    @State private var hero = ""
    @State private var rivals = ""

    // Computing animation.
    @State private var computeProgress = 0.0
    @State private var computedScore = 0

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            switch step {
            case .goal:
                quiz(title: "Do you want to get better at chess?", selection: $goal, options: [
                    ("flame.fill", "Yes — that's why I'm here", "improve"),
                    ("gamecontroller.fill", "I just want to play", "play"),
                ]) { advance(to: .experience) }
            case .experience:
                quiz(title: "How long have you been playing?", selection: $experience, options: [
                    ("leaf.fill", "I'm new to chess", "new"),
                    ("clock.fill", "A year or two", "some"),
                    ("crown.fill", "Many years", "years"),
                ]) { advance(to: .rating) }
            case .rating:
                ratingPage
            case .opening:
                quiz(title: "What's your go-to first move?",
                     subtitle: "This helps calibrate your chess score.",
                     selection: $opening, options: [
                    ("arrow.up.circle.fill", "e4 — king's pawn", "e4"),
                    ("arrow.up.circle", "d4 — queen's pawn", "d4"),
                    ("sparkles", "Nf3, c4, or something spicy", "hypermodern"),
                    ("questionmark.circle", "No idea what those mean", "none"),
                ]) { advance(to: .midgame) }
            case .midgame:
                quiz(title: "How does your middlegame feel?", selection: $midgame, options: [
                    ("exclamationmark.triangle.fill", "I keep losing pieces", "loses"),
                    ("arrow.left.arrow.right", "I trade evenly, then drift", "trades"),
                    ("target", "I make plans and attack", "plans"),
                ]) { advance(to: .endgame) }
            case .endgame:
                quiz(title: "And endgames?", selection: $endgame, options: [
                    ("questionmark.circle", "What's an endgame?", "what"),
                    ("checkmark.circle", "I can mate with a queen", "basic"),
                    ("trophy.fill", "I convert winning positions", "converts"),
                ]) { advance(to: .hero) }
            case .hero:
                quiz(title: "Which player do you vibe with?",
                     subtitle: "We'll shape your coach's advice around your style.",
                     selection: $hero, options: [
                    ("globe", "Magnus — universal, plays everything", "magnus"),
                    ("bolt.fill", "Tal — attack, always attack", "tal"),
                    ("scalemass", "Capablanca — clean and simple", "capablanca"),
                    ("person.fill", "My own style", "own"),
                ]) { advance(to: .rivals) }
            case .rivals:
                quiz(title: "Who do you want to beat?", selection: $rivals, options: [
                    ("person.2.fill", "My friends", "friends"),
                    ("network", "Everyone online", "online"),
                    ("figure.mind.and.body", "Myself — I just want to improve", "self"),
                ]) { startComputing() }
            case .computing:
                computingPage
            case .score:
                scorePage
            case .promise:
                promisePage
            case .signup:
                SignupPage(
                    onSkip: {
                        container.sync.track("signup_skipped")
                        step = .missingOut
                    },
                    onDone: onDone
                )
                .onAppear { container.sync.track("signup_viewed") }
            case .missingOut:
                missingOutPage
            }
        }
        .preferredColorScheme(.light)   // Cal AI tone: quiz is always light
        .onAppear {
            // UI tests: always run the full quiz, even on a reused simulator.
            if ProcessInfo.processInfo.arguments.contains("--reset-welcome") {
                quizDone = false
            }
            if quizDone { step = .signup }
        }
    }

    private func advance(to next: Step) {
        container.sync.track("quiz_step", ["step": String(describing: step)])
        withAnimation { step = next }
    }

    // MARK: - Quiz chrome

    private func quiz(
        title: String,
        subtitle: String? = nil,
        selection: Binding<String>,
        options: [(icon: String, label: String, value: String)],
        onContinue: @escaping () -> Void
    ) -> some View {
        QuizScaffold(
            progress: Double(step.rawValue + 1) / 9.0,
            title: title,
            subtitle: subtitle,
            canContinue: !selection.wrappedValue.isEmpty,
            onBack: step == .goal ? nil : {
                container.sync.track("quiz_back", ["from": String(describing: step)])
                withAnimation { step = Step(rawValue: step.rawValue - 1) ?? .goal }
            },
            onContinue: onContinue
        ) {
            VStack(spacing: 12) {
                ForEach(options, id: \.value) { option in
                    OptionCard(
                        icon: option.icon,
                        label: option.label,
                        selected: selection.wrappedValue == option.value
                    ) {
                        selection.wrappedValue = option.value
                    }
                }
            }
        }
    }

    private var ratingPage: some View {
        QuizScaffold(
            progress: Double(step.rawValue + 1) / 9.0,
            title: "Do you know your rating?",
            subtitle: "From chess.com, lichess, or a club.",
            canContinue: true,
            onBack: {
                container.sync.track("quiz_back", ["from": "rating"])
                withAnimation { step = .experience }
            },
            onContinue: { advance(to: .opening) }
        ) {
            VStack(spacing: 20) {
                OptionCard(icon: "questionmark.circle", label: "I've never had a rating",
                           selected: notSure) { notSure = true }
                OptionCard(icon: "number.circle.fill", label: "Yes — let me set it",
                           selected: !notSure) { notSure = false }
                if !notSure {
                    Text("\(Int(sliderRating))")
                        .font(.system(size: 44, weight: .bold, design: .rounded))
                        .monospacedDigit()
                    Slider(value: $sliderRating, in: 400...2200, step: 50)
                        .tint(.primary)
                }
            }
        }
    }

    // MARK: - Computing + score reveal

    private func startComputing() {
        container.sync.track("quiz_step", ["step": "rivals"])
        computedScore = Self.estimateScore(
            notSure: notSure, slider: Int(sliderRating), experience: experience,
            opening: opening, midgame: midgame, endgame: endgame
        )
        selfRating = computedScore
        withAnimation { step = .computing }
        container.sync.track("quiz_completed", [
            "goal": goal, "experience": experience,
            "rating_known": notSure ? "no" : "yes",
            "self_rating": notSure ? "" : String(Int(sliderRating)),
            "opening": opening, "midgame": midgame, "endgame": endgame,
            "hero": hero, "rivals": rivals,
            "computed_score": String(computedScore),
        ])
        Task { @MainActor in
            for tick in 1...20 {
                try? await Task.sleep(for: .milliseconds(110))
                withAnimation { computeProgress = Double(tick) / 20 }
            }
            try? await Task.sleep(for: .milliseconds(250))
            withAnimation { step = .score }
        }
    }

    /// Rating estimate from the quiz signals (the slider wins when given).
    static func estimateScore(
        notSure: Bool, slider: Int, experience: String,
        opening: String, midgame: String, endgame: String
    ) -> Int {
        guard notSure else { return slider }
        var score = switch experience {
        case "some": 750
        case "years": 1000
        default: 450
        }
        let openingBonus = switch opening {
        case "e4", "d4": 80
        case "hypermodern": 140
        default: -60
        }
        let midgameBonus = switch midgame {
        case "trades": 40
        case "plans": 140
        default: -80
        }
        let endgameBonus = switch endgame {
        case "basic": 40
        case "converts": 140
        default: -80
        }
        score += openingBonus + midgameBonus + endgameBonus
        return min(2200, max(400, score))
    }

    private var computingPage: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("\(Int(computeProgress * 100))%")
                .font(.system(size: 64, weight: .bold, design: .rounded))
                .monospacedDigit()
            Text("Calculating your chess score")
                .font(.title2.bold())
                .padding(.top, 4)
            ProgressView(value: computeProgress)
                .tint(.primary)
                .padding(.horizontal, 40)
                .padding(.top, 22)
            VStack(alignment: .leading, spacing: 14) {
                computeRow("Openings", done: computeProgress > 0.25)
                computeRow("Middlegame", done: computeProgress > 0.5)
                computeRow("Endgame", done: computeProgress > 0.75)
                computeRow("Playing style", done: computeProgress >= 1)
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
            .padding(.horizontal, 28)
            .padding(.top, 30)
            Spacer()
            Spacer()
        }
    }

    private func computeRow(_ label: String, done: Bool) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
            Spacer()
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? .primary : .tertiary)
        }
    }

    private var scorePage: some View {
        VStack(spacing: 0) {
            Spacer()
            Text("Your chess score")
                .font(.title2.bold())
            Text("\(computedScore)")
                .font(.system(size: 84, weight: .bold, design: .rounded))
                .monospacedDigit()
                .padding(.top, 4)
            Text("We'll start Stockfish at your level — hard enough to learn from, easy enough to beat. It adapts every game.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 36)
                .padding(.top, 14)
            Spacer()
            CapsuleCTA(label: "Continue", enabled: true) {
                withAnimation { step = .promise }
            }
        }
    }

    private var promisePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Our promise")
                .font(.system(size: 34, weight: .bold))
                .padding(.horizontal, 28)
                .padding(.top, 60)
            Text("Play 10 games with your AI coach and your score will be measurably higher.")
                .font(.title3)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 28)
                .padding(.top, 10)
            VStack(alignment: .leading, spacing: 16) {
                promiseRow("magnifyingglass", "Every mistake found and shown on the board")
                promiseRow("arrow.counterclockwise", "Replay your mistakes until you get them right")
                promiseRow("chart.line.uptrend.xyaxis", "Your score tracks every game")
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
            .padding(.horizontal, 28)
            .padding(.top, 28)
            Spacer()
            CapsuleCTA(label: "Let's go", enabled: true) {
                quizDone = true
                withAnimation { step = .signup }
            }
        }
    }

    private func promiseRow(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .frame(width: 26)
            Text(text)
                .font(.subheadline)
        }
    }

    // MARK: - Missing out

    private var missingOutPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Playing without an account")
                .font(.system(size: 34, weight: .bold))
                .padding(.horizontal, 28)
                .padding(.top, 60)
            VStack(alignment: .leading, spacing: 16) {
                missRow("iphone.slash", "Your games and score are gone if you lose or change your phone")
                missRow("brain.head.profile", "Coaching reports and training progress aren't kept together")
                missRow("chart.line.downtrend.xyaxis", "No progress tracking across devices")
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
            .padding(.horizontal, 28)
            .padding(.top, 28)
            Spacer()
            VStack(spacing: 10) {
                CapsuleCTA(label: "Create a free account", enabled: true) {
                    container.sync.track("missing_out_create_account")
                    withAnimation { step = .signup }
                }
                Button("Play anyway") {
                    container.sync.track("missing_out_play_anyway")
                    onDone()
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 24)
            }
        }
        .onAppear { container.sync.track("missing_out_viewed") }
    }

    private func missRow(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .frame(width: 26)
                .foregroundStyle(.red)
            Text(text)
                .font(.subheadline)
        }
    }
}

// MARK: - Cal AI-style building blocks

/// Back arrow + progress bar, left headline, content, pinned capsule CTA.
private struct QuizScaffold<Content: View>: View {
    let progress: Double
    let title: String
    var subtitle: String?
    let canContinue: Bool
    var onBack: (() -> Void)?
    let onContinue: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                if let onBack {
                    Button(action: onBack) {
                        Image(systemName: "arrow.left")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.primary)
                            .frame(width: 44, height: 44)
                            .background(Color(.secondarySystemBackground), in: Circle())
                    }
                } else {
                    Color.clear.frame(width: 44, height: 44)
                }
                ProgressView(value: progress)
                    .tint(.primary)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)

            Text(title)
                .font(.system(size: 34, weight: .bold))
                .padding(.horizontal, 28)
                .padding(.top, 24)
            if let subtitle {
                Text(subtitle)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 28)
                    .padding(.top, 6)
            }

            Spacer(minLength: 20)

            ScrollView {
                content
                    .padding(.horizontal, 24)
            }
            .scrollBounceBehavior(.basedOnSize)

            CapsuleCTA(label: "Continue", enabled: canContinue, action: onContinue)
        }
    }
}

/// White option card: icon in a soft circle, label, trailing radio;
/// selected = bold border + filled radio.
private struct OptionCard: View {
    let icon: String
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
                    .background(Color(.secondarySystemBackground), in: Circle())
                Text(label)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                Spacer()
                Image(systemName: selected ? "smallcircle.filled.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? .primary : .tertiary)
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color(.systemBackground))
                    .stroke(selected ? Color.primary : Color(.systemGray4),
                            lineWidth: selected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// The pinned Cal AI CTA: full-width black capsule.
struct CapsuleCTA: View {
    let label: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.headline)
                .foregroundStyle(Color(.systemBackground))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 17)
                .background(enabled ? Color.primary : Color(.systemGray3), in: Capsule())
        }
        .disabled(!enabled)
        .buttonStyle(.plain)
        .padding(.horizontal, 24)
        .padding(.bottom, 24)
    }
}

/// Account creation, Cal AI-style: left headline, black Apple pill,
/// outlined Google/email pills.
private struct SignupPage: View {
    @Environment(DependencyContainer.self) private var container
    let onSkip: () -> Void
    let onDone: () -> Void

    @State private var showEmailSheet = false
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Save your progress")
                .font(.system(size: 34, weight: .bold))
                .padding(.horizontal, 28)
                .padding(.top, 70)
            Text("Every game, your score and coaching reports — synced automatically.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 28)
                .padding(.top, 8)

            Spacer()

            VStack(spacing: 12) {
                AppleSignInButton(sync: container.sync) { onDone() }
                    .frame(height: 54)
                    .clipShape(Capsule())

                signupPill(label: "Sign in with Google", icon: "globe") {
                    container.sync.track("signup_method_tapped", ["method": "google"])
                    busy = true
                    Task { @MainActor in
                        await GoogleSignInHelper.signIn(sync: container.sync)
                        busy = false
                        if container.sync.isSignedIn { onDone() }
                    }
                }

                signupPill(label: "Continue with email", icon: "envelope") {
                    container.sync.track("signup_method_tapped", ["method": "email"])
                    showEmailSheet = true
                }

                Button("Skip for now") { onSkip() }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 34)
            .disabled(busy)
            .overlay {
                if busy { ProgressView() }
            }
        }
        .sheet(isPresented: $showEmailSheet) {
            AccountSheet(sync: container.sync)
                .onDisappear {
                    if container.sync.isSignedIn { onDone() }
                }
        }
    }

    private func signupPill(label: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                Text(label)
            }
            .font(.headline)
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                Capsule().stroke(Color(.systemGray3), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
