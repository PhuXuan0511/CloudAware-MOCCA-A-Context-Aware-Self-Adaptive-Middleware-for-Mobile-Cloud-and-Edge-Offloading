package com.thesis.middleware.context.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.thesis.middleware.context.NetworkContext
import com.thesis.middleware.context.NetworkType

/**
 * Collects network context: type, bandwidth, and signal strength.
 * RTT is intentionally left at 0 because a real measurement requires an
 * async probe; ContextManager.collect() is synchronous.
 */
class NetworkCollector(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    fun collect(): NetworkContext {
        val active = connectivityManager.activeNetwork
        val caps = active?.let(connectivityManager::getNetworkCapabilities)
            ?: return NetworkContext(NetworkType.NONE, 0f, 0f, 0)

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularType()
            else -> NetworkType.NONE
        }

        return NetworkContext(
            type = type,
            rttMs = 0f,
            bandwidthMbps = caps.linkDownstreamBandwidthKbps / 1000f,
            signalStrength = signalLevel()
        )
    }

    private fun cellularType(): NetworkType {
        if (!hasPhonePermission()) return NetworkType.LTE
        return when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> NetworkType.FIVE_G
            else -> NetworkType.LTE
        }
    }

    private fun signalLevel(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0
        if (!hasPhonePermission()) return 0
        return telephonyManager.signalStrength?.level ?: 0
    }

    private fun hasPhonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}
