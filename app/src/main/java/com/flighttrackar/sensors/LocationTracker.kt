package com.flighttrackar.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.flighttrackar.model.ObserverLocation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks observer GPS position and computes local magnetic declination via [GeomagneticField].
 * Uses Google Play Services [FusedLocationProviderClient] with automatic fallback to
 * platform [LocationManager].
 */
class LocationTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<ObserverLocation?>(null)
    val location: StateFlow<ObserverLocation?> = _location.asStateFlow()

    private var started = false

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { publishLocation(it) }
        }
    }

    private val platformListener = LocationListener { loc ->
        publishLocation(loc)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true

        // Grab last known location immediately for instant startup
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _location.value == null) {
                    publishLocation(loc)
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(1500L)
                .setMinUpdateDistanceMeters(3f)
                .build()
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        } catch (_: Exception) {
            startPlatformFallback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformFallback() {
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.getLastKnownLocation(p)?.let { publishLocation(it) }
                    locationManager.requestLocationUpdates(p, 3000L, 3f, platformListener, Looper.getMainLooper())
                }
            }
        } catch (_: Exception) {
            // Permission not granted or no location provider enabled
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            fusedClient.removeLocationUpdates(fusedCallback)
        } catch (_: Exception) {
        }
        try {
            locationManager.removeUpdates(platformListener)
        } catch (_: Exception) {
        }
    }

    private fun publishLocation(loc: Location) {
        val altM = if (loc.hasAltitude()) loc.altitude else 0.0
        val field = GeomagneticField(
            loc.latitude.toFloat(),
            loc.longitude.toFloat(),
            altM.toFloat(),
            System.currentTimeMillis()
        )
        _location.value = ObserverLocation(
            latitudeDeg = loc.latitude,
            longitudeDeg = loc.longitude,
            altitudeMeters = altM,
            accuracyMeters = loc.accuracy,
            magneticDeclinationDeg = field.declination,
            timestampMs = loc.time
        )
    }
}
