package cc.hyoban.moments.footprints

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

/**
 * What the system lets recording keep doing in the background beyond its permissions: the live
 * update (ColorOS's 流体云), running without battery limits, and ColorOS's auto-start, each
 * read from the system where it can be and opened in its own settings.
 */
internal object BackgroundAllowances {
    /** Whether the recording notification may become a live update; before Android 16 there is none to allow. */
    fun liveUpdates(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        return !manager.areNotificationsEnabled() || manager.canPostPromotedNotifications()
    }

    fun notifications(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun unrestrictedBattery(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * ColorOS (OPPO, OnePlus, realme) stops an app that may not start itself; whether it may
     * can't be read, so its row is only shown there.
     */
    val autoStartSettings: Boolean = Build.MANUFACTURER.lowercase() in setOf("oppo", "oneplus", "realme")

    fun openNotifications(context: Context) = context.open(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openLiveUpdates(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return openNotifications(context)
        context.open(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    /** The system's own dialog that lifts the battery limits, or its list where a system has none. */
    @SuppressLint("BatteryLife")
    fun requestUnrestrictedBattery(context: Context) = context.open(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** ColorOS keeps auto-start in the app's own page, under its battery use. */
    fun openAutoStart(context: Context) = context.open()

    /** The first of [intents] the system opens, else the app's own page. */
    private fun Context.open(vararg intents: Intent) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
        for (candidate in intents.toList() + details) {
            try {
                return startActivity(candidate)
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}
