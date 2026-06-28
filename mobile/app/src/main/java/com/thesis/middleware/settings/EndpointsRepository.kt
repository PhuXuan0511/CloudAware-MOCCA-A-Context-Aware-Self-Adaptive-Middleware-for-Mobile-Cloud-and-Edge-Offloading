package com.thesis.middleware.settings

import android.content.Context
import android.content.SharedPreferences
import com.thesis.middleware.adaptation.ExecutionMode

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

    /**
     * Runtime execution mode. Read on every task submission so changes from
     * the Settings screen take effect immediately for the next tap.
     */
    var executionMode: ExecutionMode
        get() = runCatching {
            ExecutionMode.valueOf(prefs.getString(KEY_MODE, ExecutionMode.ADAPTIVE.name)!!)
        }.getOrDefault(ExecutionMode.ADAPTIVE)
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    /**
     * Debug-only: when non-null, ContextManager replaces the computed network
     * score with this value before the policy sees it. Set to null to restore
     * real sensor readings. -1f is the sentinel stored in SharedPreferences
     * meaning "no override".
     */
    var debugNetworkScore: Float?
        get() {
            val stored = prefs.getFloat(KEY_DEBUG_NETWORK, SENTINEL)
            return if (stored < 0f) null else stored
        }
        set(value) {
            prefs.edit().putFloat(KEY_DEBUG_NETWORK, value ?: SENTINEL).apply()
        }

    /**
     * Debug-only: when non-null, MapeLoop replaces the computed speedup with
     * this value so the audience can see any rule fire regardless of real
     * network conditions. Set to null to restore real estimates. -1f = no override.
     */
    var debugSpeedup: Float?
        get() {
            val stored = prefs.getFloat(KEY_DEBUG_SPEEDUP, SENTINEL)
            return if (stored < 0f) null else stored
        }
        set(value) {
            prefs.edit().putFloat(KEY_DEBUG_SPEEDUP, value ?: SENTINEL).apply()
        }

    /**
     * Debug-only: when non-null, MapeLoop replaces the estimated remote energy
     * so BALANCED_COST can be shown choosing LOCAL. -1f = no override.
     */
    var debugRemoteEnergyMj: Float?
        get() {
            val stored = prefs.getFloat(KEY_DEBUG_REMOTE_ENERGY, SENTINEL)
            return if (stored < 0f) null else stored
        }
        set(value) {
            prefs.edit().putFloat(KEY_DEBUG_REMOTE_ENERGY, value ?: SENTINEL).apply()
        }

    companion object {
        const val DEFAULT_EDGE = "http://10.0.2.2:8001"
        const val DEFAULT_CLOUD = "http://10.0.2.2:8002"

        private const val PREFS_NAME = "mocca.endpoints"
        private const val KEY_EDGE = "edge_url"
        private const val KEY_CLOUD = "cloud_url"
        private const val KEY_MODE = "execution_mode"
        private const val KEY_DEBUG_NETWORK = "debug_network_score"
        private const val KEY_DEBUG_SPEEDUP = "debug_speedup"
        private const val KEY_DEBUG_REMOTE_ENERGY = "debug_remote_energy"
        private const val SENTINEL = -1f
    }
}
