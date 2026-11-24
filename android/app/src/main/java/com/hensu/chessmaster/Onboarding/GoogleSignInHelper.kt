// Chessmaster — GPL-3.0-or-later
package com.hensu.chessmaster

/** Google sign-in. iOS shows Google's native sheet and falls back to the web flow; Android
 *  always uses the web flow (Supabase OAuth in a Custom Tab — no Google Play services
 *  sign-in SDK), which becomes a Supabase session when the browser returns. */
object GoogleSignInHelper {
    suspend fun signIn(sync: SyncService) {
        sync.signInWithGoogle()
    }
}
