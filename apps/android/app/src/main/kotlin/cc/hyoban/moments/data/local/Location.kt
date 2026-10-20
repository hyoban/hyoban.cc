package cc.hyoban.moments.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

// Called only once the app has the location permission.
/**
 * Where the phone is: a fix from the last few minutes if any provider has one, else a
 * fresh one from the best provider there is, or null if none comes within the timeout.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = manager.getProviders(true)
    val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < RECENT_FIX_MS }
        .minByOrNull { it.accuracy }
    if (recent != null) return recent

    val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
        ?: return null
    return withTimeoutOrNull(LOCATE_TIMEOUT_MS.toLong()) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }
}

/** How old a fix may be and still count as where the phone is now. */
private const val RECENT_FIX_MS = 2 * 60 * 1000L

/**
 * What the place at [fix] is called, a town or a part of one, from the system's geocoder;
 * null where the phone has none, is offline, or it finds nothing.
 */
suspend fun placeName(context: Context, fix: Fix, locale: Locale = Locale.getDefault()): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, locale)
    val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(fix.latitude, fix.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
    } ?: return null
    return listOf(address.subLocality, address.locality, address.subAdminArea, address.adminArea).firstOrNull { !it.isNullOrBlank() }
}

/** How long to wait for a first fix before giving up. */
private const val LOCATE_TIMEOUT_MS = 10_000

/** How long to wait for a place's name before leaving it to be typed. */
private const val GEOCODE_TIMEOUT_MS = 5_000L
