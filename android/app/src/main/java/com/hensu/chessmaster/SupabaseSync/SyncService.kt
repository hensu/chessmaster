// SupabaseSync — Chessmaster
// GPL-3.0-or-later
package com.hensu.chessmaster

import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class SupabaseConfig(val url: String, val anonKey: String) {
    companion object {
        /** Reads SUPABASE_URL / SUPABASE_ANON_KEY from the build config.
         *  Returns null when the backend hasn't been configured —
         *  the app then runs fully offline. */
        fun fromMainBundle(): SupabaseConfig? {
            val urlString = BuildConfig.SUPABASE_URL
            val key = BuildConfig.SUPABASE_ANON_KEY
            if (urlString.isEmpty() || key.isEmpty()) return null
            if (Uri.parse(urlString).scheme.isNullOrEmpty()) return null
            return SupabaseConfig(urlString, key)
        }
    }
}

/** Auth + outbox sync. Play never depends on this: games queue locally
 *  (syncState = "local") and push whenever a session exists. */
class SyncService(
    config: SupabaseConfig?,
    private val games: GameRepository,
    private val ratingHistory: RatingHistoryRepository,
) {
    sealed class SyncState {
        data object NotConfigured : SyncState()
        data object SignedOut : SyncState()
        /** signed in, nothing to do */
        data object Idle : SyncState()
        data object Syncing : SyncState()
        data class Error(val message: String) : SyncState()
    }

    internal val client: SupabaseClient? = config?.let { SupabaseClient(it.url, it.anonKey) }

    var state: SyncState by mutableStateOf(if (config != null) SyncState.SignedOut else SyncState.NotConfigured)
        private set
    var userID: UUID? by mutableStateOf(null)
        private set
    /** False until `restoreSession()` has run at least once. Sign-in prompts
     *  must wait for this — before it's true, `isSignedIn` is simply unknown,
     *  not "signed out", so prompting would be wrong. */
    var sessionRestored by mutableStateOf(false)
        private set

    /** Bumped whenever the stored auth user changes, so `displayName` readers recompose. */
    private var sessionTick by mutableIntStateOf(0)

    val isConfigured: Boolean get() = client != null
    val isSignedIn: Boolean get() = userID != null

    // MARK: - Feature flags / A/B experiments

    /** Resolved feature flags. Seeded from the last fetch's cache so gating
     *  is stable offline; `refreshFlags()` updates from the backend. */
    var flags: Map<String, Boolean> by mutableStateOf(
        Defaults.standard.json("flags.cache", MapSerializer(String.serializer(), Boolean.serializer())) ?: emptyMap())
        private set

    /** Gate a feature: `if (sync.flag("new_home")) { ... }`. Unknown flags
     *  fall back to the given default, so code can ship before the
     *  app_config row exists. */
    fun flag(key: String, default: Boolean = false): Boolean = flags[key] ?: default

    /** The `value` of each flag that resolved ON for this install (an
     *  experiment's payload, e.g. the product suffix of a price test). */
    var flagValues: Map<String, String> by mutableStateOf(
        Defaults.standard.json("flags.values", MapSerializer(String.serializer(), String.serializer())) ?: emptyMap())
        private set

    /** A flag's payload, or null when the flag is off for this install (the
     *  control half of an A/B split) or carries no value. */
    fun flagValue(key: String): String? = flagValues[key]

    @Serializable
    private data class AppConfigRow(
        val key: String, val enabled: Boolean, val ab_split: Boolean, val value: String? = null)

    /** Fetches app_config and resolves each flag: plain rows apply to
     *  everyone (remote toggle); ab_split rows put this install in a stable
     *  50/50 variant. Resolved flags ride on every GA hit as user
     *  properties (flag_<key>), so cohorts are comparable in reports. */
    suspend fun refreshFlags() {
        val client = client ?: return
        val rows = try {
            AppJson.decodeFromString(
                ListSerializer(AppConfigRow.serializer()), client.select("app_config", "key, enabled, ab_split, value"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val resolved = mutableMapOf<String, Boolean>()
        val values = mutableMapOf<String, String>()
        for (row in rows) {
            val on = if (row.ab_split) abVariant(row.key) else row.enabled
            resolved[row.key] = on
            if (on && !row.value.isNullOrEmpty()) values[row.key] = row.value
        }
        flags = resolved
        flagValues = values
        Defaults.standard.setJson("flags.values", values.toMap(), MapSerializer(String.serializer(), String.serializer()))
        Defaults.standard.setJson("flags.cache", resolved.toMap(), MapSerializer(String.serializer(), Boolean.serializer()))
    }

    /** True once this device's push token is stored server-side, so the
     *  backend push service can reach this user — the client then leaves
     *  re-engagement to the server instead of scheduling local notifications. */
    var hasRemoteToken by mutableStateOf(false)
        private set
    private var deviceTokenHex: String? = null

    /** Upsert this device's push token so the re-engagement push job can reach
     *  the signed-in user. No-op when signed out (push targets accounts). */
    suspend fun registerDeviceToken(hex: String, environment: String, tz: String, locale: String) {
        val client = client ?: return
        val userID = userID ?: return
        deviceTokenHex = hex
        val row = buildJsonObject {
            put("user_id", userID.syncUUIDString)
            put("token", hex)
            put("platform", "android")
            put("environment", environment)
            put("tz", tz)
            put("locale", locale)
            put("updated_at", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())
        }
        hasRemoteToken = try {
            client.upsert("device_tokens", row, onConflict = "token")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Restores an existing session (call at launch). Sets `sessionRestored`
     *  when done so prompts know the auth state is now settled. */
    suspend fun restoreSession() {
        try {
            val client = client ?: return
            client.load()
            val session = try {
                client.session()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The refresh failed (e.g. launched offline) but a session is
                // still stored locally — keep the user signed in rather than
                // falsely prompting them to sign in. A later online refresh heals.
                client.currentSession
            }
            val id = session?.let { runCatching { UUID.fromString(it.userID) }.getOrNull() }
            if (id != null) {
                userID = id
                sessionTick++
                state = SyncState.Idle
            }
        } finally {
            sessionRestored = true
        }
    }

    /** Anonymous account: full sync without any credentials; can be linked
     *  to a provider later. */
    suspend fun signInAnonymously() {
        val client = client ?: return
        signIn("anonymous") { client.signInAnonymously() }
    }

    /** Runs one sign-in call; a failure becomes the error state, like every Swift sign-in. */
    private suspend fun signIn(method: String, call: suspend () -> SupabaseSession) {
        try {
            val session = call()
            completeSignIn(UUID.fromString(session.userID), method)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SyncState.Error(e.localizedMessage ?: e.toString())
        }
    }

    /** Sign in with Apple from an identity token + nonce (the iOS native flow;
     *  Android uses the web flow, `signInWithApple()`). */
    suspend fun signInWithApple(idToken: String, nonce: String) {
        val client = client ?: return
        signIn("apple") { client.signInWithIdToken("apple", idToken, nonce = nonce) }
    }

    /** Google sign-in via a Custom Tab (Supabase web OAuth, PKCE flow). Returns once the
     *  browser round-trip has finished (signed in, failed, or the tab was closed). */
    suspend fun signInWithGoogle() = signInWithOAuth("google", "google_web")

    /** Sign in with Apple via a Custom Tab (Supabase web OAuth, PKCE flow); Android has no
     *  native Apple sheet. Returns once the browser round-trip has finished. */
    suspend fun signInWithApple() = signInWithOAuth("apple", "apple_web")

    /** The web flow in flight: completed with the callback URL, or null when the tab was closed. */
    private var oauthPending: CompletableDeferred<String?>? = null

    private suspend fun signInWithOAuth(provider: String, method: String) {
        val client = client ?: return
        val activity = MainActivity.current ?: return
        val pending = CompletableDeferred<String?>()
        oauthPending?.complete(null)
        oauthPending = pending
        Defaults.standard.set(oauthMethodKey, method)
        // No callback shortly after the app is back in front = the user closed the tab.
        val scope = MainScope()
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) left = true
            if (event == Lifecycle.Event.ON_RESUME && left) {
                scope.launch {
                    delay(1500)
                    pending.complete(null)
                }
            }
        }
        try {
            val url = client.oauthURL(provider, oauthRedirectURL)
            activity.lifecycle.addObserver(observer)
            CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
            val callback = pending.await()
                ?: return   // User closed the tab; not an error state.
            finishOAuth(callback, method)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SyncState.Error(e.localizedMessage ?: e.toString())
        } finally {
            activity.lifecycle.removeObserver(observer)
            scope.cancel()
            if (oauthPending === pending) oauthPending = null
        }
    }

    /** Exchanges the callback's `code` for a session (or records the provider's error). */
    private suspend fun finishOAuth(callback: String, method: String) {
        val client = client ?: return
        val uri = Uri.parse(callback)
        val code = uri.getQueryParameter("code")
        if (code == null) {
            val problem = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
            if (problem != null) state = SyncState.Error(problem)
            return
        }
        Defaults.standard.remove(oauthMethodKey)
        signIn(method) { client.exchangeCodeForSession(code) }
    }

    /** The app's entry point hands every incoming URL here. Returns true when it was the
     *  OAuth callback (`chessmaster://auth-callback?code=…`), which then finishes the
     *  sign-in started by `signInWithGoogle()` / `signInWithApple()`. */
    fun handleOAuthCallback(url: String): Boolean {
        if (!url.startsWith(oauthRedirectURL)) return false
        val pending = oauthPending
        if (pending != null && pending.isActive) {
            pending.complete(url)
        } else {
            // The process was restarted while the browser was open: finish from here.
            val method = Defaults.standard.string(oauthMethodKey) ?: "google_web"
            MainScope().launch { finishOAuth(url, method) }
        }
        return true
    }

    /** Google Sign-In with an ID token: exchange it for a session. */
    suspend fun signInWithGoogleIDToken(idToken: String, accessToken: String?) {
        val client = client ?: return
        signIn("google_native") { client.signInWithIdToken("google", idToken, accessToken = accessToken) }
    }

    /** Password sign-in — only used by the UI-test fixture account. */
    suspend fun signInWithPassword(email: String, password: String) {
        val client = client ?: return
        signIn("password") { client.signIn(email, password) }
    }

    /** Email sign-in, step 1: send a 6-digit code. */
    suspend fun sendEmailCode(to: String) {
        val client = client ?: throw SyncError.NotConfigured
        client.signInWithOTP(to, shouldCreateUser = true)
    }

    /** Email sign-in, step 2: verify the code. */
    suspend fun verifyEmailCode(email: String, code: String): Boolean {
        val client = client ?: return false
        return try {
            val session = client.verifyOTP(email, code, "email")
            if (session == null) {
                state = SyncState.Error("Verification did not return a session.")
                return false
            }
            completeSignIn(UUID.fromString(session.userID), "email")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SyncState.Error("That code didn't work — check it and try again.")
            false
        }
    }

    /** The signed-in account's email, if any (shown in the account UI). */
    suspend fun accountEmail(): String? {
        val client = client ?: return null
        return try {
            client.session().email
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun completeSignIn(userID: UUID, method: String) {
        this.userID = userID
        sessionTick++
        track("signed_in", mapOf("method" to method))
        state = SyncState.Idle
        pullRemote()
        pushPending()
    }

    // MARK: - Analytics

    /** User-scoped GA properties attached to every hit ("premium" etc.).
     *  The app layer keeps these current; signed_in is derived here. */
    var userProperties: MutableMap<String, String> = mutableStateMapOf()

    /** Fire-and-forget product event, sent to Google Analytics only —
     *  nothing is stored in the backend. Never blocks or surfaces errors. */
    fun track(name: String, props: Map<String, String> = emptyMap()) {
        if (App.arguments.contains("--uitest")) return
        sendToGoogleAnalytics(name, props)
    }

    /** GA4 Measurement Protocol — plain HTTPS, no SDK (the Firebase
     *  Analytics binary is closed-source and GPL-incompatible with the
     *  Stockfish-derived client). Session params make events count toward
     *  GA4 realtime/engagement reports. */
    private var gaSessionID = Instant.now().epochSecond.toString()
    /** Start of the foreground time not yet reported to GA; null while
     *  backgrounded. Each hit carries the time since the previous one, so
     *  GA's engagement time reflects real foreground use. */
    private var engagementStart: Instant? = Instant.now()
    private var backgroundedAt: Instant? = null

    /** App came to the foreground. A return after GA's 30-minute session
     *  timeout starts a new session, matching the Firebase SDK. */
    fun appBecameActive() {
        val backgroundedAt = backgroundedAt
        if (backgroundedAt != null && java.time.Duration.between(backgroundedAt, Instant.now()).seconds > 30 * 60) {
            gaSessionID = Instant.now().epochSecond.toString()
        }
        this.backgroundedAt = null
        if (engagementStart == null) engagementStart = Instant.now()
    }

    /** App left the foreground: flush the unreported foreground time. */
    fun appResignedActive() {
        if (engagementStart == null) return
        track("user_engagement")
        engagementStart = null
        backgroundedAt = Instant.now()
    }

    /** GA "Pages and screens" view. The property is a web stream fed by the
     *  Measurement Protocol, so screens are sent as page_view. */
    fun trackScreen(name: String) {
        track("page_view", mapOf(
            "page_title" to name,
            "page_location" to "app://chessai/${name.lowercase().replace(" ", "-")}",
        ))
    }

    /** Milliseconds of foreground time since the last hit (min 1 — GA only
     *  counts a user as active when engagement_time_msec > 0). */
    private fun takeEngagementMsec(): Int {
        val start = engagementStart ?: return 1
        val now = Instant.now()
        engagementStart = now
        return maxOf(1, java.time.Duration.between(start, now).toMillis().toInt())
    }

    private fun sendToGoogleAnalytics(name: String, props: Map<String, String>) {
        val measurementID = BuildConfig.GA_MEASUREMENT_ID
        val apiSecret = BuildConfig.GA_API_SECRET
        if (measurementID.isEmpty() || apiSecret.isEmpty()) return
        val url = "https://www.google-analytics.com/mp/collect".toHttpUrl().newBuilder()
            .addQueryParameter("measurement_id", measurementID)
            .addQueryParameter("api_secret", apiSecret)
            .build()

        val body = buildJsonObject {
            put("client_id", analyticsDeviceID.syncUUIDString)
            put("events", buildJsonArray {
                add(buildJsonObject {
                    put("name", name)
                    putJsonObject("params") {
                        put("session_id", gaSessionID)
                        put("engagement_time_msec", takeEngagementMsec().toString())
                        for ((key, value) in props) put(key, value.take(100))
                    }
                })
            })
            userID?.let { put("user_id", it.syncUUIDString) }
            // MP hits get no IP geolocation or device detection — without these
            // GA shows Country/Device/OS as "(not set)".
            val country = Locale.getDefault().country
            if (country.isNotEmpty()) putJsonObject("user_location") { put("country_id", country) }
            putJsonObject("device") { for ((key, value) in gaDevice) put(key, value) }
            // GA caps user-property values at 36 chars and names at 24 —
            // keep flag keys to 19 chars or shorter.
            putJsonObject("user_properties") {
                putJsonObject("signed_in") { put("value", if (isSignedIn) "true" else "false") }
                for ((key, value) in flags) {
                    putJsonObject("flag_${key.take(19)}") { put("value", if (value) "true" else "false") }
                }
                for ((key, value) in userProperties) {
                    putJsonObject(key) { put("value", value.take(36)) }
                }
            }
        }

        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        supabaseHTTP.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    /** Fetches an existing, completed coaching report for a game — read-only
     *  (RLS: own reports), never triggers generation. Returns the report
     *  JSON, or null when no report exists yet. */
    suspend fun fetchCoachingReport(gameID: String): String? {
        val client = client
        if (client == null || !isSignedIn) return null
        return try {
            val text = client.select("coaching_reports", "report", listOf(
                "game_id" to "eq.${gameID.lowercase()}",
                "status" to "eq.complete",
                "limit" to "1",
            ))
            val rows = AppJson.parseToJsonElement(text) as? JsonArray
            val report = (rows?.firstOrNull() as? JsonObject)?.get("report")
            if (report == null || report is JsonNull) null else report.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Whether this user has ever received a completed coaching report (RLS:
     *  own rows). null when unknown (signed out / offline) — callers must
     *  treat that as "don't act". */
    suspend fun hasCompletedCoachingReport(): Boolean? {
        val client = client
        if (client == null || !isSignedIn) return null
        return try {
            val text = client.select("coaching_reports", "id", listOf("status" to "eq.complete", "limit" to "1"))
            val rows = AppJson.parseToJsonElement(text) as? JsonArray
            rows != null && rows.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Registers a purchased subscription server-side (best-effort; the
     *  store's server notifications are the durable path, this is the instant unlock). */
    suspend fun verifySubscription(productID: String, purchaseToken: String): VerifiedSubscription? {
        if (!isSignedIn) signInAnonymously()
        if (!isSignedIn) return null
        return try {
            val text = invokeFunctionJSON("verify-subscription", buildJsonObject {
                put("platform", "android")
                put("product_id", productID)
                put("purchase_token", purchaseToken)
            }.toString())
            val body = AppJson.parseToJsonElement(text) as? JsonObject
            val entitlement = body?.get("entitlement") as? JsonObject
            val isTrial = (entitlement?.get("is_trial") as? JsonPrimitive)?.content == "true"
            val expires = (entitlement?.get("expires_at") as? JsonPrimitive)?.content
            VerifiedSubscription(
                offerCode = (body?.get("offer_code") as? JsonPrimitive)?.content == "true",
                trialEndsAt = if (isTrial) expires?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() } else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Test purchases may not verify; real purchases retry on next launch.
            null
        }
    }

    /** What Google Play told the backend about a purchase that the billing library
     *  does not tell the app: whether it came from a promo code, and its trial end. */
    data class VerifiedSubscription(val offerCode: Boolean, val trialEndsAt: Instant?)

    /** The player's name as friends see it (auth `full_name`/`name` metadata;
     *  Google fills it, Apple only on first authorization, email never). */
    val displayName: String?
        get() {
            sessionTick
            val meta = client?.currentSession?.userMetadata ?: return null
            val name = ((meta["full_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (meta["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)?.trim()
            return if (name.isNullOrEmpty()) null else name
        }

    /** Saves the name to auth metadata (what friend games and pushes show). */
    suspend fun setDisplayName(name: String): Boolean {
        val trimmed = name.trim()
        val client = client
        if (client == null || trimmed.isEmpty()) return false
        return try {
            client.updateUserMetadata(buildJsonObject { put("full_name", trimmed) })
            sessionTick++
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    enum class SignUpResult { signedIn, confirmEmail, failed }

    /** Creates an email+password account. When the project requires email
     *  confirmation, the session arrives only after the user confirms. */
    suspend fun signUpWithPassword(email: String, password: String): SignUpResult {
        val client = client ?: return SignUpResult.failed
        return try {
            val session = client.signUp(email, password)
            if (session != null) {
                completeSignIn(UUID.fromString(session.userID), "password_signup")
                SignUpResult.signedIn
            } else {
                SignUpResult.confirmEmail
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignUpResult.failed
        }
    }

    suspend fun sendPasswordReset(to: String): Boolean {
        val client = client ?: return false
        return try {
            client.resetPasswordForEmail(to)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun signOut() {
        val client = client ?: return
        track("signed_out")   // before userID clears, so the hit carries it
        // Stop push to this device for the account being left (leave other
        // devices' tokens alone by matching only this one).
        deviceTokenHex?.let { hex ->
            try {
                client.delete("device_tokens", listOf("token" to "eq.$hex"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        hasRemoteToken = false
        deviceTokenHex = null
        try {
            client.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        userID = null
        sessionTick++
        state = SyncState.SignedOut
    }

    /** Permanently deletes the account server-side —
     *  the backend removes the auth user and every owned row cascades — then
     *  clears the local session. Returns false if the server call failed. */
    suspend fun deleteAccount(): Boolean {
        val client = client ?: return false
        try {
            val response = AppJson.parseToJsonElement(invokeFunctionJSON("delete-account", "{}")).jsonObject
            (response["deleted"] as? JsonPrimitive)?.booleanOrNull ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        track("account_deleted")
        hasRemoteToken = false
        deviceTokenHex = null
        // The server session is already revoked with the user; only local
        // sign-out can succeed.
        try {
            client.signOut(localOnly = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        userID = null
        sessionTick++
        state = SyncState.SignedOut
        return true
    }

    // MARK: - Edge Functions

    /** Invokes a backend Edge Function with the caller's session JWT and
     *  decodes the JSON response. Throws `SupabaseHttpException` on a non-2xx answer. */
    suspend inline fun <reified Body, reified Result> invokeFunction(name: String, body: Body): Result {
        val text = invokeFunctionJSON(name, AppJson.encodeToString(serializer<Body>(), body))
        return AppJson.decodeFromString(serializer<Result>(), text)
    }

    /** `invokeFunction` with the body and the response as raw JSON text. */
    suspend fun invokeFunctionJSON(name: String, bodyJSON: String): String {
        val client = client ?: throw SyncError.NotConfigured
        return client.invoke(name, bodyJSON)
    }

    sealed class SyncError : Exception() {
        data object NotConfigured : SyncError()
    }

    // MARK: - Push

    /** Removes a game server-side (best-effort; RLS restricts to own rows). */
    suspend fun deleteRemoteGame(id: String) {
        val client = client
        if (client == null || !isSignedIn) return
        try {
            client.delete("games", listOf("id" to "eq.${id.lowercase()}"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Pushes locally finished games and rating rows (append-only upserts).
     *  One bad row must not strand the rest of the queue. */
    suspend fun pushPending() {
        val client = client ?: return
        val userID = userID ?: return
        state = SyncState.Syncing
        var rowFailures = 0
        try {
            val pendingGames = games.pendingSyncGames()
            for (record in pendingGames) {
                val remote = RemoteGame.of(record, userID)
                if (remote == null) {
                    games.markGameSynced(record.id)   // never retryable
                    continue
                }
                try {
                    client.upsert("games", AppJson.encodeToJsonElement(RemoteGame.serializer(), remote), onConflict = "id")
                    games.markGameSynced(record.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    rowFailures += 1   // stays pending; retried next push
                }
            }

            val syncedGameIDs = games.recentGames()
                .filter { it.syncState == "synced" }
                .map { it.id.lowercase() }
                .toSet()
            for (record in ratingHistory.pendingSync()) {
                val remote = RemoteRatingHistory(record, userID)
                // Drop dangling FKs (game failed to sync or was local-only).
                val gameId = remote.gameId
                if (gameId != null && !syncedGameIDs.contains(gameId.toString().lowercase())) {
                    remote.gameId = null
                }
                try {
                    client.insert("rating_history", AppJson.encodeToJsonElement(RemoteRatingHistory.serializer(), remote))
                    record.id?.let { ratingHistory.markSynced(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    rowFailures += 1
                }
            }
            state = if (rowFailures == 0) SyncState.Idle else SyncState.Error("$rowFailures items didn't sync; will retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SyncState.Error(e.localizedMessage ?: e.toString())
        }
    }

    // MARK: - Pull

    @Serializable
    private data class PulledGame(
        @Serializable(with = SyncUUIDSerializer::class)
        var id: UUID,
        @SerialName("opponent_type") var opponentType: String,
        @SerialName("engine_level") var engineLevel: Int? = null,
        @SerialName("user_color") var userColor: String,
        @SerialName("time_control") var timeControl: String? = null,
        @SerialName("time_class") var timeClass: String,
        var result: String,
        var termination: String? = null,
        var pgn: String,
        var evals: String? = null,
        @SerialName("rating_before") var ratingBefore: Double? = null,
        @SerialName("rating_after") var ratingAfter: Double? = null,
        @SerialName("played_at") @Serializable(with = SyncInstantSerializer::class)
        var playedAt: Instant,
    )

    /** Restores games/rating history from the backend (sign-in on a fresh
     *  install). Local rows win on ID collision. */
    suspend fun pullRemote() {
        val client = client ?: return
        if (userID == null) return
        state = SyncState.Syncing
        try {
            val pulled = AppJson.decodeFromString(
                ListSerializer(PulledGame.serializer()),
                client.select("games", "*", listOf("order" to "played_at.desc", "limit" to "500")))

            val localIDs = games.recentGames(10_000).map { it.id.lowercase() }.toSet()
            val missing = pulled.filter { !localIDs.contains(it.id.toString().lowercase()) }
            // Replaying each PGN for its final position is the slow part: keep it off the main thread.
            val records = withContext(Dispatchers.Default) {
                missing.map { remote ->
                    var tcInitial: Int? = null
                    var tcIncrement: Int? = null
                    remote.timeControl?.let { tc ->
                        val parts = tc.split("+").mapNotNull { it.toIntOrNull() }
                        if (parts.size == 2) { tcInitial = parts[0]; tcIncrement = parts[1] }
                    }
                    GameRecord(
                        id = remote.id.syncUUIDString,
                        startedAt = remote.playedAt,
                        endedAt = remote.playedAt,
                        opponentType = if (remote.opponentType == "engine") "engine" else "humanLocal",
                        engineLevel = remote.engineLevel,
                        playerColor = remote.userColor,
                        tcInitialSeconds = tcInitial,
                        tcIncrementSeconds = tcIncrement,
                        result = when (remote.result) {
                            "white_win" -> "whiteWin"
                            "black_win" -> "blackWin"
                            "draw" -> "draw"
                            else -> "aborted"
                        },
                        termination = remote.termination,
                        pgn = remote.pgn,
                        finalFEN = GameReplay.of(remote.pgn)?.plies?.lastOrNull()?.fenAfter ?: "",
                        ratingCategory = if (remote.ratingBefore != null) remote.timeClass else null,
                        ratingBefore = remote.ratingBefore,
                        ratingAfter = remote.ratingAfter,
                        syncState = "synced",
                        analysisJSON = remote.evals,
                    )
                }
            }
            for (record in records) games.save(record)
            state = SyncState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SyncState.Error(e.localizedMessage ?: e.toString())
        }
    }

    companion object {
        /** The OAuth callback scheme; must be registered in the app's
         *  manifest and in the Supabase dashboard's redirect URLs. */
        const val oauthRedirectURL = "chessmaster://auth-callback"

        /** Which web sign-in is in flight, kept across a process restart while the browser is open. */
        private const val oauthMethodKey = "oauth.pendingMethod"

        /** Stable anonymous install identifier — ties pre-signup funnel events
         *  to the account the player eventually creates. */
        val analyticsDeviceID: UUID by lazy {
            val key = "analytics.deviceID"
            val stored = Defaults.standard.string(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            stored ?: UUID.randomUUID().also { Defaults.standard.set(key, it.syncUUIDString) }
        }

        /** Deterministic 50/50 assignment, stable per install and per flag
         *  (FNV-1a — a seeded hash can't be used). */
        private fun abVariant(key: String): Boolean {
            var hash = 0xcbf29ce484222325uL
            for (byte in "${analyticsDeviceID.syncUUIDString}:$key".toByteArray(Charsets.UTF_8)) {
                hash = hash xor byte.toUByte().toULong()
                hash *= 0x100000001b3uL
            }
            return hash % 2uL == 0uL
        }

        /** GA4 MP `device` block, computed once per launch. */
        private val gaDevice: Map<String, String> by lazy {
            mapOf(
                "category" to "mobile",
                "operating_system" to "Android",
                "operating_system_version" to (Build.VERSION.RELEASE ?: ""),
                "model" to (Build.MODEL ?: ""),
                "brand" to (Build.MANUFACTURER ?: ""),
                "language" to Locale.getDefault().toLanguageTag(),
            )
        }
    }
}
