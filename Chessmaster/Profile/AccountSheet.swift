// Chessmaster — GPL-3.0-or-later
import SwiftUI
import SupabaseSync

/// Account sign-in: email + password (with sign-up and reset), Google, or Apple.
/// Signed-in players get every finished game saved to their account.
struct AccountSheet: View {
    let sync: SyncService
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var password = ""
    @State private var isSignUp = false
    @State private var busy = false
    @State private var errorMessage: String?
    @State private var infoMessage: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Brand header
                Image("LaunchLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 56, height: 56)
                    .clipShape(RoundedRectangle(cornerRadius: 13))
                    .padding(.top, 28)
                Text("Chess AI")
                    .font(.system(.title2, design: .serif).bold())
                    .padding(.top, 10)

                Text(isSignUp ? "Create account" : "Sign in")
                    .font(.title3.weight(.semibold))
                    .padding(.top, 26)
                Rectangle()
                    .fill(Color.green.opacity(0.8))
                    .frame(width: 64, height: 3)
                    .clipShape(Capsule())
                    .padding(.top, 8)

                // Email + password / code
                VStack(spacing: 12) {
                    field {
                        TextField("Email", text: $email)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }

                    field {
                        SecureField("Password", text: $password)
                            .textContentType(isSignUp ? .newPassword : .password)
                    }
                    primaryButton(isSignUp ? "Sign Up with Email" : "Sign In with Email",
                                  enabled: email.contains("@") && password.count >= 6) {
                        run {
                            if isSignUp {
                                switch await sync.signUpWithPassword(email: email, password: password) {
                                case .signedIn:
                                    break
                                case .confirmEmail:
                                    infoMessage = "Almost there — confirm the email we just sent, then sign in."
                                    isSignUp = false
                                case .failed:
                                    errorMessage = "Couldn't create the account — that email may already be registered."
                                }
                            } else {
                                await sync.signInWithPassword(email: email, password: password)
                                if !sync.isSignedIn {
                                    errorMessage = "That email and password didn't match."
                                }
                            }
                        }
                    }
                }
                .padding(.top, 24)

                if let errorMessage {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .padding(.top, 12)
                }
                if let infoMessage {
                    Text(infoMessage)
                        .font(.footnote)
                        .foregroundStyle(.green)
                        .padding(.top, 12)
                }

                // Providers
                VStack(spacing: 12) {
                    Button {
                        run { await GoogleSignInHelper.signIn(sync: sync) }
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "globe")
                                .fontWeight(.semibold)
                            Text("Sign In with Google")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .overlay(Capsule().stroke(Color.secondary.opacity(0.45), lineWidth: 1.2))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)

                    AppleSignInButton(sync: sync) { dismiss() }
                        .frame(height: 52)
                        .clipShape(Capsule())
                }
                .padding(.top, 22)

                if !isSignUp {
                    Button("Forgot your password?") {
                        run {
                            guard email.contains("@") else {
                                errorMessage = "Enter your email above first."
                                return
                            }
                            if await sync.sendPasswordReset(to: email) {
                                infoMessage = "Password reset email sent — check your inbox."
                            } else {
                                errorMessage = "Couldn't send the reset email."
                            }
                        }
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 22)
                }

                HStack(spacing: 5) {
                    Text(isSignUp ? "Already have an account?" : "Don't have an account?")
                        .foregroundStyle(.secondary)
                    Button(isSignUp ? "Sign In" : "Sign Up Here") {
                        isSignUp.toggle()
                        errorMessage = nil
                        infoMessage = nil
                    }
                    .fontWeight(.semibold)
                    .tint(.green)
                }
                .font(.footnote)
                .padding(.top, 12)
                .padding(.bottom, 30)
            }
            .padding(.horizontal, 24)
        }
        .overlay {
            if busy {
                ZStack {
                    Color.black.opacity(0.25).ignoresSafeArea()
                    ProgressView()
                        .padding(18)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                }
            }
        }
        .overlay(alignment: .topTrailing) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .background(Color.secondary.opacity(0.15), in: Circle())
            }
            .padding(14)
            .accessibilityLabel("Cancel")
        }
        .presentationDragIndicator(.visible)
    }

    // MARK: - Pieces

    private func field<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .padding(.horizontal, 16)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(Color.secondary.opacity(0.08))
                    .stroke(Color.secondary.opacity(0.35), lineWidth: 1.2)
            )
    }

    private func primaryButton(_ title: String, enabled: Bool,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(enabled ? Color.green.opacity(0.85) : Color.secondary.opacity(0.3),
                            in: Capsule())
        }
        .disabled(!enabled || busy)
        .buttonStyle(.plain)
    }

    /// Runs an auth action; dismisses on success (signed-in state).
    private func run(_ action: @escaping () async -> Void) {
        busy = true
        errorMessage = nil
        Task { @MainActor in
            await action()
            busy = false
            if sync.isSignedIn { dismiss() }
        }
    }
}
