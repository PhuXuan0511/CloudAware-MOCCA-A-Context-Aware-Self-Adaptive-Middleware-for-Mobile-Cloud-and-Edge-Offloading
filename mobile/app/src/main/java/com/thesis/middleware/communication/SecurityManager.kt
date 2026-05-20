package com.thesis.middleware.communication

/**
 * Handles JWT token acquisition and injection into outgoing requests.
 * TODO: Implement OAuth2 client-credentials flow against the auth server.
 */
class SecurityManager {

    private var cachedToken: String? = null

    fun getAuthHeader(): String {
        val token = cachedToken ?: refreshToken()
        return "Bearer $token"
    }

    private fun refreshToken(): String {
        // TODO: POST to auth server with client credentials, cache the JWT
        cachedToken = "stub-token"
        return cachedToken!!
    }

    fun invalidateToken() {
        cachedToken = null
    }
}
