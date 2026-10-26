package cc.hyoban.moments.footprints

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

internal fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
internal fun Context.bluetoothAllowed() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
internal fun Context.wifiAllowed(background: Boolean) = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
    getSystemService(LocationManager::class.java).isLocationEnabled &&
    (!background || Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
internal fun Context.connectionAutomationAllowed(rules: ConnectionRules) = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
    wifiAllowed(background = true) && // Automatic starts need background location, including Bluetooth-only rules.
    (rules.bluetooth.isEmpty() || bluetoothAllowed())

@SuppressLint("InlinedApi") // The public constant is inlined; older releases return the same sentinel.
private fun ssid(info: WifiInfo?): String? = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }
    ?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
internal fun Context.connectedSsid(capabilities: NetworkCapabilities? = null): String? =
    if (!wifiAllowed(background = false)) null else {
        val transport = if (Build.VERSION.SDK_INT >= 29) capabilities?.transportInfo as? WifiInfo else null
        ssid(transport ?: applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo)
    }

/** System-cached scan results plus the current network; never an active background scan. */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
internal fun Context.wifiChoices(): List<String> {
    if (!wifiAllowed(background = false)) return emptyList()
    val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
    return (wifi.scanResults.map { it.SSID }.filter { it.isNotBlank() } + listOfNotNull(connectedSsid())).distinct().sorted()
}

@SuppressLint("MissingPermission")
internal fun Context.bluetoothChoices(): Map<String, String> {
    if (!bluetoothAllowed()) return emptyMap()
    return getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .associate { it.address.uppercase(Locale.ROOT) to (it.name ?: it.address) }
}
