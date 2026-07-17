// Chessmaster — GPL-3.0-or-later
import GoogleSignIn
import SupabaseSync
import UIKit

/// Native Google Sign-In: Google's own sheet (no browser, no domain
/// dialog), then the ID token becomes a Supabase session. Falls back to
/// the web flow when the iOS OAuth client isn't configured in the build.
enum GoogleSignInHelper {
    private static var clientID: String? {
        let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String
        return (id?.isEmpty == false) ? id : nil
    }

    @MainActor
    static func signIn(sync: SyncService) async {
        guard let clientID, let presenter = topViewController() else {
            await sync.signInWithGoogle()   // web-flow fallback
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString else { return }
            await sync.signInWithGoogleIDToken(
                idToken: idToken,
                accessToken: result.user.accessToken.tokenString
            )
        } catch {
            // User closed the sheet; not an error state.
        }
    }

    /// The visible view controller (sign-in can start from a sheet or cover).
    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
