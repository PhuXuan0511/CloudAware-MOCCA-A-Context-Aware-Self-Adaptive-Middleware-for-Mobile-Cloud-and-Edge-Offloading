package com.thesis.middleware.context.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.thesis.middleware.context.LocationContext

/**
 * Returns the freshest last-known location across GPS, network, and passive
 * providers. Uses the platform LocationManager to avoid a Play Services
 * dependency; caller must hold ACCESS_FINE/COARSE_LOCATION at runtime.
 */
class LocationCollector(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun collect(): LocationContext {
        if (!hasLocationPermission()) return EMPTY

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val best = providers
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return EMPTY

        return LocationContext(
            latitude = best.latitude,
            longitude = best.longitude,
            accuracy = best.accuracy
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        val EMPTY = LocationContext(0.0, 0.0, 0f)
    }
}
