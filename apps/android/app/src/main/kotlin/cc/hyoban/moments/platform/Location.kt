package cc.hyoban.moments.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One fix of where the phone is, from the platform's location manager; no Play services
 * needed. Tries the fused provider, then network, then GPS, and settles for the freshest
 * last known location when none answers in time.
 */
class CurrentLocation(private val context: Context) {
    fun hasPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun get(timeoutMillis: Long = 8_000): Location? {
        if (!hasPermission()) {
            Log.i(TAG, "No location permission")
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        Log.i(TAG, "Enabled providers: $providers (all: ${manager.allProviders})")

        for (provider in providers) {
            val fix = withTimeoutOrNull(timeoutMillis) { request(manager, provider) }
            Log.i(TAG, "$provider -> ${fix?.let { "${it.latitude},${it.longitude} ±${it.accuracy}m" } ?: "nothing"}")
            if (fix != null) return fix
        }

        val lastKnown = manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        Log.i(TAG, "Last known -> ${lastKnown?.let { "${it.provider} ${it.latitude},${it.longitude}" } ?: "nothing"}")
        return lastKnown
    }

    private suspend fun request(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } else {
                    val listener = LocationListener { location -> if (continuation.isActive) continuation.resume(location) }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, context.mainLooper)
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            } catch (error: Exception) {
                Log.w(TAG, "$provider failed", error)
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private companion object {
        const val TAG = "MomentsLocation"
    }
}
