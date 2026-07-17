// Chessmaster — GPL-3.0-or-later
import AuthenticationServices
import CryptoKit
import SwiftUI
import SupabaseSync

/// Native Sign in with Apple. Generates a nonce, hashes it into the Apple
/// request, and exchanges the identity token for a Supabase session.
struct AppleSignInButton: View {
    let sync: SyncService
    let onSignedIn: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var nonce: String?

    var body: some View {
        SignInWithAppleButton(.continue) { request in
            sync.track("signup_method_tapped", ["method": "apple"])
            let raw = Self.randomNonce()
            nonce = raw
            request.requestedScopes = [.email]
            request.nonce = Self.sha256(raw)
        } onCompletion: { result in
            guard case .success(let auth) = result,
                  let credential = auth.credential as? ASAuthorizationAppleIDCredential,
                  let tokenData = credential.identityToken,
                  let idToken = String(data: tokenData, encoding: .utf8),
                  let nonce
            else { return }  // cancelled or malformed; not an error state
            Task { @MainActor in
                await sync.signInWithApple(idToken: idToken, nonce: nonce)
                if sync.isSignedIn { onSignedIn() }
            }
        }
        .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
        // Shape (height + corner clip) is owned by each call site.
    }

    private static func randomNonce(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var bytes = [UInt8](repeating: 0, count: length)
        _ = SecRandomCopyBytes(kSecRandomDefault, length, &bytes)
        return String(bytes.map { charset[Int($0) % charset.count] })
    }

    private static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
