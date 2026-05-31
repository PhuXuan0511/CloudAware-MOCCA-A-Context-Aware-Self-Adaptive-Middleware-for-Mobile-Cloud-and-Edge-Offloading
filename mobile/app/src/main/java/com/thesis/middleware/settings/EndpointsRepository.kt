package com.thesis.middleware.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the edge/cloud base URLs across app launches so the user can
 * configure them once per Wi-Fi network without rebuilding the APK.
 *
 * Default points to the Android emulator alias (`10.0.2.2`) — the right
 * value when the emulator runs on the same host as docker-compose. For a
 * real phone on shared Wi-Fi with a laptop running the backend, the user
 * must enter the laptop's LAN IP via [com.thesis.middleware.settings.SettingsActivity].
 */
class EndpointsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var edgeUrl: String
        get() = prefs.getString(KEY_EDGE, DEFAULT_EDGE)!!
        set(value) { prefs.edit().putString(KEY_EDGE, value.trim().trimEnd('/')).apply() }

    var cloudUrl: String
        get() = prefs.getString(KEY_CLOUD, DEFAULT_CLOUD)!!
        set(value) { prefs.edit().putString(KEY_CLOUD, value.trim().trimEnd('/')).apply() }

    companion object {
        const val DEFAULT_EDGE = "http://10.0.2.2:8001"
        const val DEFAULT_CLOUD = "http://10.0.2.2:8002"

        private const val PREFS_NAME = "mocca.endpoints"
        private const val KEY_EDGE = "edge_url"
        private const val KEY_CLOUD = "cloud_url"
    }
}
