// SupabaseSync — Chessmaster
// GPL-3.0-or-later
//
// The small slice of supabase-swift this app uses, as plain OkHttp calls to the same project:
// Auth (GoTrue, /auth/v1), PostgREST (/rest/v1, RPC /rest/v1/rpc/<fn>) and Edge Functions
// (/functions/v1/<name>). Request shapes follow supabase-swift so the backend sees the same
// calls from both apps. The session lives in EncryptedSharedPreferences and is refreshed
// before it expires and whenever the server answers 401.
package com.hensu.chessmaster

import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** One HTTP client for Supabase and the GA4 Measurement Protocol. */
internal val supabaseHTTP: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Coaching reports are generated inside the request; edge functions may run for minutes.
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** A non-2xx answer from Supabase. `body` is the raw response (PostgREST / GoTrue error JSON). */
class SupabaseHttpException(val status: Int, val body: String) : Exception(describe(status, body)) {
    /** Includes the raw body, so callers can look for backend error codes ("not_found", …). */
    override fun toString(): String = "SupabaseHttpException($status): $body"

    private companion object {
        fun describe(status: Int, body: String): String {
            val obj = runCatching { AppJson.parseToJsonElement(body).jsonObject }.getOrNull()
            for (key in listOf("msg", "error_description", "message", "error")) {
                val text = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrBlank()) return text
            }
            return "The server returned an error ($status)."
        }
    }
}

/** No stored session (signed out), or the stored one could not be refreshed and was dropped. */
class SupabaseSessionMissingException : Exception("Not signed in.")

/** The stored auth session: tokens plus the parts of the user the app reads. */
@Serializable
data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds at which the access token expires. */
    val expiresAt: Long,
    val userID: String,
    val email: String? = null,
    val userMetadata: JsonObject = JsonObject(emptyMap()),
)

class SupabaseClient(supabaseURL: String, private val supabaseKey: String) {
    private val base = supabaseURL.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Mutex()

    // MARK: - Session storage

    /** Null when the keystore is unusable; the session then lives in memory for this launch only. */
    private val prefs: SharedPreferences? by lazy { openPrefs() }
    @Volatile private var loaded = false

    /** The stored session as last read or written; null until `load()` has run or when signed out. */
    @Volatile var currentSession: SupabaseSession? = null
        private set

    private fun openPrefs(): SharedPreferences? {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(App.context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                App.context, PREFS_FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return runCatching { create() }.recoverCatching {
            // A restored backup or a reset keystore leaves an undecryptable file: start over.
            App.context.deleteSharedPreferences(PREFS_FILE)
            create()
        }.getOrNull()
    }

    /** Reads the stored session from disk (once). */
    suspend fun load() = withContext(Dispatchers.IO) { loadBlocking() }

    @Synchronized
    private fun loadBlocking() {
        if (loaded) return
        currentSession = runCatching {
            prefs?.getString(KEY_SESSION, null)?.let { AppJson.decodeFromString(SupabaseSession.serializer(), it) }
        }.getOrNull()
        loaded = true
    }

    @Synchronized
    private fun store(session: SupabaseSession?) {
        loaded = true
        currentSession = session
        runCatching {
            prefs?.edit()?.apply {
                if (session == null) remove(KEY_SESSION)
                else putString(KEY_SESSION, AppJson.encodeToString(SupabaseSession.serializer(), session))
            }?.apply()
        }
    }

    // MARK: - HTTP

    private fun url(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val builder = (base + path).toHttpUrl().newBuilder()
        for ((name, value) in query) builder.addQueryParameter(name, value)
        return builder.build()
    }

    /** Blocking; call on Dispatchers.IO. Returns status + body text. */
    private fun execute(
        method: String, url: HttpUrl, body: String?, bearer: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val request = Request.Builder().url(url)
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer $bearer")
        for ((name, value) in headers) request.header(name, value)
        val payload = body?.toRequestBody(json)
        when (method) {
            "GET" -> request.get()
            "DELETE" -> if (payload == null) request.delete() else request.delete(payload)
            else -> request.method(method, payload ?: "".toRequestBody(json))
        }
        supabaseHTTP.newCall(request.build()).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun check(result: Pair<Int, String>): String {
        if (result.first !in 200..299) throw SupabaseHttpException(result.first, result.second)
        return result.second
    }

    /** An auth call made with the public key (sign-in, sign-up, OTP…). */
    private fun authCall(path: String, query: List<Pair<String, String>>, body: JsonObject): String =
        check(execute("POST", url("/auth/v1$path", query), body.toString(), supabaseKey))

    /**
     * A data call as the signed-in user (or with the public key when signed out, like
     * supabase-swift). Refreshes the token first when it is about to expire, and once more on 401.
     */
    private suspend fun authorized(
        method: String, path: String, query: List<Pair<String, String>> = emptyList(),
        body: String? = null, headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        loadBlocking()
        val signedIn = currentSession != null
        val bearer = if (signedIn) session().accessToken else supabaseKey
        var result = execute(method, url(path, query), body, bearer, headers)
        if (result.first == 401 && signedIn) {
            val fresh = refresh(staleAccessToken = bearer)
            result = execute(method, url(path, query), body, fresh.accessToken, headers)
        }
        check(result)
    }

    // MARK: - Auth: session

    /** The current session with a valid access token (refreshed when it expires within a minute). */
    suspend fun session(): SupabaseSession = withContext(Dispatchers.IO) {
        loadBlocking()
        val current = currentSession ?: throw SupabaseSessionMissingException()
        if (current.expiresAt - System.currentTimeMillis() / 1000 > EXPIRY_MARGIN_SECONDS) current
        else refresh(staleAccessToken = current.accessToken)
    }

    /**
     * `POST /auth/v1/token?grant_type=refresh_token`. One refresh at a time; a caller that waited
     * behind another one gets the already-refreshed session. A refresh token the server rejects
     * removes the stored session; a network failure keeps it (a later online refresh heals).
     */
    private suspend fun refresh(staleAccessToken: String): SupabaseSession = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val current = currentSession ?: throw SupabaseSessionMissingException()
            if (current.accessToken != staleAccessToken) return@withContext current
            val result = execute(
                "POST", url("/auth/v1/token", listOf("grant_type" to "refresh_token")),
                buildJsonObject { put("refresh_token", current.refreshToken) }.toString(), supabaseKey)
            if (result.first in 200..299) {
                parseSession(result.second)?.also { store(it) } ?: throw SupabaseHttpException(result.first, result.second)
            } else {
                if (result.first in listOf(400, 401, 403)) store(null)
                throw SupabaseHttpException(result.first, result.second)
            }
        }
    }

    /** A GoTrue session response; null when the body carries no session (e.g. email confirmation pending). */
    private fun parseSession(text: String): SupabaseSession? {
        val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val access = (obj["access_token"] as? JsonPrimitive)?.contentOrNull ?: return null
        val refresh = (obj["refresh_token"] as? JsonPrimitive)?.contentOrNull ?: return null
        val user = obj["user"] as? JsonObject ?: return null
        val id = (user["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val now = System.currentTimeMillis() / 1000
        val expiresAt = (obj["expires_at"] as? JsonPrimitive)?.longOrNull
            ?: (now + ((obj["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600))
        return SupabaseSession(
            accessToken = access, refreshToken = refresh, expiresAt = expiresAt, userID = id,
            email = (user["email"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
            userMetadata = user["user_metadata"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun signedIn(text: String): SupabaseSession =
        parseSession(text)?.also { store(it) } ?: throw SupabaseHttpException(200, text)

    // MARK: - Auth: PKCE

    /** A new code verifier (kept for the exchange) and its S256 challenge, like supabase-swift's PKCE flow. */
    private fun prepareForPKCE(): Pair<String, String> {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        val verifier = (1..64).map { charset[random.nextInt(charset.length)] }.joinToString("")
        runCatching { prefs?.edit()?.putString(KEY_VERIFIER, verifier)?.apply() }
        memoryVerifier = verifier
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return challenge to "s256"
    }

    @Volatile private var memoryVerifier: String? = null

    // MARK: - Auth: sign-in

    /** `POST /auth/v1/signup` with no credentials: an anonymous account. */
    suspend fun signInAnonymously(): SupabaseSession = withContext(Dispatchers.IO) {
        signedIn(authCall("/signup", emptyList(), buildJsonObject { }))
    }

    /** `POST /auth/v1/signup`. Returns null when the project wants the email confirmed first. */
    suspend fun signUp(email: String, password: String): SupabaseSession? = withContext(Dispatchers.IO) {
        val (challenge, method) = prepareForPKCE()
        val text = authCall("/signup", emptyList(), buildJsonObject {
            put("email", email)
            put("password", password)
            put("code_challenge", challenge)
            put("code_challenge_method", method)
        })
        parseSession(text)?.also { store(it) }
    }

    /** `POST /auth/v1/token?grant_type=password`. */
    suspend fun signIn(email: String, password: String): SupabaseSession = withContext(Dispatchers.IO) {
        signedIn(authCall("/token", listOf("grant_type" to "password"), buildJsonObject {
            put("email", email)
            put("password", password)
        }))
    }

    /** `POST /auth/v1/otp`: emails a sign-in code. */
    suspend fun signInWithOTP(email: String, shouldCreateUser: Boolean): Unit = withContext(Dispatchers.IO) {
        val (challenge, method) = prepareForPKCE()
        authCall("/otp", emptyList(), buildJsonObject {
            put("email", email)
            put("create_user", shouldCreateUser)
            put("code_challenge", challenge)
            put("code_challenge_method", method)
        })
        Unit
    }

    /** `POST /auth/v1/verify`. `type` is GoTrue's OTP type ("email"). Null when no session came back. */
    suspend fun verifyOTP(email: String, token: String, type: String): SupabaseSession? = withContext(Dispatchers.IO) {
        val text = authCall("/verify", emptyList(), buildJsonObject {
            put("email", email)
            put("token", token)
            put("type", type)
        })
        parseSession(text)?.also { store(it) }
    }

    /** `POST /auth/v1/token?grant_type=id_token`: a provider's ID token becomes a session. */
    suspend fun signInWithIdToken(
        provider: String, idToken: String, accessToken: String? = null, nonce: String? = null,
    ): SupabaseSession = withContext(Dispatchers.IO) {
        signedIn(authCall("/token", listOf("grant_type" to "id_token"), buildJsonObject {
            put("provider", provider)
            put("id_token", idToken)
            if (accessToken != null) put("access_token", accessToken)
            if (nonce != null) put("nonce", nonce)
        }))
    }

    /** The page to open for web OAuth: `GET /auth/v1/authorize?provider=…` with a PKCE challenge. */
    suspend fun oauthURL(provider: String, redirectTo: String): String = withContext(Dispatchers.IO) {
        val (challenge, method) = prepareForPKCE()
        url("/auth/v1/authorize", listOf(
            "provider" to provider,
            "redirect_to" to redirectTo,
            "code_challenge" to challenge,
            "code_challenge_method" to method,
        )).toString()
    }

    /** `POST /auth/v1/token?grant_type=pkce`: the `code` from the OAuth callback becomes a session. */
    suspend fun exchangeCodeForSession(code: String): SupabaseSession = withContext(Dispatchers.IO) {
        val verifier = memoryVerifier ?: runCatching { prefs?.getString(KEY_VERIFIER, null) }.getOrNull()
        val session = signedIn(authCall("/token", listOf("grant_type" to "pkce"), buildJsonObject {
            put("auth_code", code)
            if (verifier != null) put("code_verifier", verifier)
        }))
        memoryVerifier = null
        runCatching { prefs?.edit()?.remove(KEY_VERIFIER)?.apply() }
        session
    }

    /** `POST /auth/v1/recover`: emails a password-reset link. */
    suspend fun resetPasswordForEmail(email: String): Unit = withContext(Dispatchers.IO) {
        val (challenge, method) = prepareForPKCE()
        authCall("/recover", emptyList(), buildJsonObject {
            put("email", email)
            put("code_challenge", challenge)
            put("code_challenge_method", method)
        })
        Unit
    }

    /** `PUT /auth/v1/user` with `{"data": …}`: merges into the user's metadata and stores the result. */
    suspend fun updateUserMetadata(data: JsonObject): Unit = withContext(Dispatchers.IO) {
        if (currentSession == null) { loadBlocking(); if (currentSession == null) throw SupabaseSessionMissingException() }
        val text = authorized("PUT", "/auth/v1/user", body = buildJsonObject { put("data", data) }.toString())
        val user = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
        val current = currentSession
        if (user != null && current != null) {
            store(current.copy(
                email = (user["email"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: current.email,
                userMetadata = user["user_metadata"] as? JsonObject ?: current.userMetadata,
            ))
        }
    }

    /**
     * Removes the local session; unless `localOnly`, first asks the server to revoke it
     * (`POST /auth/v1/logout?scope=global`, failures ignored: the local sign-out always succeeds).
     */
    suspend fun signOut(localOnly: Boolean = false): Unit = withContext(Dispatchers.IO) {
        loadBlocking()
        val token = currentSession?.accessToken
        store(null)
        if (!localOnly && token != null) {
            runCatching { execute("POST", url("/auth/v1/logout", listOf("scope" to "global")), null, token) }
        }
    }

    // MARK: - PostgREST

    /** `GET /rest/v1/<table>?select=…&<filters>`; `query` holds raw PostgREST pairs (`"id" to "eq.x"`, `"limit" to "1"`). */
    suspend fun select(table: String, columns: String = "*", query: List<Pair<String, String>> = emptyList()): String =
        authorized("GET", "/rest/v1/$table", listOf("select" to columns.replace(" ", "")) + query)

    suspend fun insert(table: String, row: JsonElement) {
        authorized("POST", "/rest/v1/$table", body = row.toString(), headers = mapOf("Prefer" to "return=representation"))
    }

    suspend fun upsert(table: String, row: JsonElement, onConflict: String) {
        authorized(
            "POST", "/rest/v1/$table", listOf("on_conflict" to onConflict), row.toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=representation"))
    }

    suspend fun update(table: String, values: JsonElement, filters: List<Pair<String, String>>) {
        authorized("PATCH", "/rest/v1/$table", filters, values.toString(), mapOf("Prefer" to "return=representation"))
    }

    suspend fun delete(table: String, filters: List<Pair<String, String>>) {
        authorized("DELETE", "/rest/v1/$table", filters, headers = mapOf("Prefer" to "return=representation"))
    }

    /** `POST /rest/v1/rpc/<fn>` with the parameters as a JSON object. Returns the JSON result. */
    suspend fun rpc(fn: String, params: JsonObject = JsonObject(emptyMap())): String =
        authorized("POST", "/rest/v1/rpc/$fn", body = params.toString())

    // MARK: - Edge Functions

    /** `POST /functions/v1/<name>` with the caller's session token. Returns the response body. */
    suspend fun invoke(name: String, bodyJSON: String): String =
        authorized("POST", "/functions/v1/$name", body = bodyJSON)

    private companion object {
        const val PREFS_FILE = "supabase.auth"
        const val KEY_SESSION = "session"
        const val KEY_VERIFIER = "code_verifier"
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}
