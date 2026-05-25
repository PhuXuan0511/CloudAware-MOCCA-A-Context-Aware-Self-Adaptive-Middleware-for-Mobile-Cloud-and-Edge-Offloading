package com.thesis.middleware.communication

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Acquires and caches OAuth2 access tokens via the client-credentials flow,
 * then exposes them as a ready-to-use `Authorization` header.
 *
 *  - Tokens are cached until [refreshSkewMs] before their `expires_in`
 *    deadline, then transparently refreshed on the next call.
 *  - [getAuthHeader] is intentionally blocking — it's invoked from an OkHttp
 *    interceptor that already runs on a worker thread, so wrapping in a
 *    coroutine would buy nothing.
 *  - The refresh path uses double-checked locking so a burst of concurrent
 *    requests coalesces into a single token call.
 */
class SecurityManager(
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val scope: String? = null,
    private val httpClient: OkHttpClient = defaultClient(),
    private val refreshSkewMs: Long = DEFAULT_REFRESH_SKEW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()
    private val lock = Any()

    @Volatile private var cached: CachedToken? = null

    fun getAuthHeader(): String = "Bearer ${currentToken()}"

    fun invalidateToken() {
        synchronized(lock) { cached = null }
    }

    private fun currentToken(): String {
        cached?.takeIf { it.isFresh() }?.let { return it.token }
        return synchronized(lock) {
            cached?.takeIf { it.isFresh() }?.let { return@synchronized it.token }
            val fresh = fetchToken()
            cached = fresh
            fresh.token
        }
    }

    private fun CachedToken.isFresh(): Boolean = clock() < expiresAtMs - refreshSkewMs

    private fun fetchToken(): CachedToken {
        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .apply { if (!scope.isNullOrBlank()) add("scope", scope) }
            .build()
        val request = Request.Builder().url(tokenUrl).post(form).build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("token endpoint $tokenUrl returned ${resp.code}: $body")
            }
            val parsed = gson.fromJson(body, TokenResponse::class.java)
                ?: throw IOException("token endpoint $tokenUrl returned empty body")
            val ttlMs = parsed.expiresIn.coerceAtLeast(1L) * 1_000L
            return CachedToken(parsed.accessToken, expiresAtMs = clock() + ttlMs)
        }
    }

    private data class CachedToken(val token: String, val expiresAtMs: Long)

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("token_type") val tokenType: String = "Bearer",
        @SerializedName("expires_in") val expiresIn: Long = 3600
    )

    companion object {
        private const val DEFAULT_REFRESH_SKEW_MS = 30_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
