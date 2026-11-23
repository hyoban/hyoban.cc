package cc.hyoban.moments.plugins

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.net.toUri
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.camera.CameraLink
import cc.hyoban.moments.footprints.ActivityTransitions
import cc.hyoban.moments.footprints.BackgroundAllowances
import cc.hyoban.moments.footprints.RecordingService
import cc.hyoban.moments.footprints.granted
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.openAppSettings
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.picker.GalleryAccess
import cc.hyoban.moments.ui.picker.LocalMedia
import org.jetbrains.compose.resources.StringResource
import rikka.shizuku.Shizuku

/**
 * One thing a plugin needs of the phone: Android permissions the app asks for, or a setting only the
 * system's own page gives (an accessibility service, the battery's limits, installing apps, Shizuku).
 * Its plugin adds it to [PhonePoints.needs]; the plugin's pages list what of it is missing and ask
 * for it from here, in the order it was added.
 */
class PhoneNeed(
    /** Its row's test tag, where it has one. */
    val key: String,
    val icon: ImageVector,
    /** The words that name it. */
    val title: StringResource,
    /** Why it is needed, said while it isn't given. */
    val why: StringResource? = null,
    /** What its row says once given, where it stays shown. */
    val allowed: StringResource? = null,
    /** Whether its plugin's list of needs shows it; false for one asked only as its feature starts. */
    val listed: Boolean = true,
    private val asked: () -> List<String> = { emptyList() },
    private val needed: (Context) -> Boolean = { true },
    private val check: (Context) -> Boolean,
    private val opens: (Context) -> Unit,
) {
    /** The Android permissions asked for it in the app, on this phone's Android; none for a system setting. */
    val permissions: Array<String> get() = asked().toTypedArray()

    /** Whether the phone needs it now, as it is set up (the background location only for precise location). */
    fun applies(context: Context): Boolean = needed(context)

    fun given(context: Context): Boolean = check(context)

    /** Needed now and not given. */
    fun missing(context: Context): Boolean = applies(context) && !given(context)

    /** The system's page where it is given. */
    fun open(context: Context) = opens(context)
}

private fun notificationPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

private fun Context.openAppNotifications() =
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

/** What recording footprints needs, in the order its 需要 group lists what is missing. */
object FootprintNeeds {
    /** Precise location, asked with notifications as recording first starts. */
    val location = PhoneNeed(
        "footprints_needs_precise", AppIcons.Locate, Res.string.footprints_needs_location, Res.string.footprints_permission_needed,
        listed = false,
        asked = { listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) },
        check = { RecordingService.hasPreciseLocation(it) },
        opens = { it.openAppSettings() },
    )
    val notifications = PhoneNeed(
        "footprints_needs_notifications", AppIcons.Bell, Res.string.onboarding_notifications, Res.string.footprints_needs_notifications,
        asked = ::notificationPermissions,
        check = { BackgroundAllowances.notifications(it) },
        opens = { BackgroundAllowances.openNotifications(it) },
    )
    val live = PhoneNeed(
        "footprints_needs_live", AppIcons.Stream, Res.string.footprints_needs_live, Res.string.footprints_needs_live_note,
        check = { BackgroundAllowances.liveUpdates(it) },
        opens = { BackgroundAllowances.openLiveUpdates(it) },
    )
    val battery = PhoneNeed(
        "footprints_needs_battery", AppIcons.BatteryCharging, Res.string.footprints_needs_battery, Res.string.footprints_needs_battery_note,
        check = { BackgroundAllowances.unrestrictedBattery(it) },
        opens = { BackgroundAllowances.requestUnrestrictedBattery(it) },
    )
    /** 始终允许, only the system's settings give; needed once precise location is. */
    val background = PhoneNeed(
        "footprints_needs_location", AppIcons.Locate, Res.string.footprints_needs_location, Res.string.footprints_needs_location_note,
        asked = { listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        needed = { RecordingService.hasPreciseLocation(it) },
        check = { it.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        opens = { it.openAppSettings() },
    )
    /** Body activity, for 活动识别增强 while it is on. */
    val activity = PhoneNeed(
        "footprints_needs_activity", AppIcons.Walk, Res.string.footprints_needs_activity, Res.string.footprints_needs_activity_note,
        asked = { listOf(Manifest.permission.ACTIVITY_RECOGNITION) },
        needed = { ActivityTransitions.enabled(it) },
        check = { ActivityTransitions.permitted(it) },
        opens = { it.openAppSettings() },
    )

    val all = listOf(location, notifications, live, battery, background, activity)
}

/** What the paired camera needs: reaching it to pair and take photos, and sending it the location. */
object CameraNeeds {
    val bluetooth = PhoneNeed(
        "camera_need_bluetooth", AppIcons.Bluetooth, Res.string.camera_need_bluetooth, Res.string.camera_need_bluetooth_off, Res.string.camera_allowed,
        asked = { listOf(Manifest.permission.BLUETOOTH_CONNECT) },
        check = { CameraLink.bluetoothAllowed(it) },
        opens = { it.openAppSettings() },
    )
    /** Joining the camera's Wi‑Fi and, from Android 17, reaching it on that network. */
    val wifi = PhoneNeed(
        "camera_need_wifi", AppIcons.Wifi, Res.string.permission_camera_wifi,
        listed = false,
        asked = { CameraLink.wifiPermissions.toList() },
        check = { CameraLink.wifiAllowed(it) },
        opens = { it.openAppSettings() },
    )
    /** Location, asked as it is paired. */
    val location = PhoneNeed(
        "camera_need_precise", AppIcons.Pin, Res.string.permission_camera_location,
        listed = false,
        asked = { listOf(Manifest.permission.ACCESS_FINE_LOCATION) },
        check = { CameraLink.locationAllowed(it) },
        opens = { it.openAppSettings() },
    )
    val backgroundLocation = PhoneNeed(
        "camera_need_location", AppIcons.Pin, Res.string.camera_need_location, Res.string.camera_need_location_off, Res.string.camera_allowed,
        asked = { listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        check = { CameraLink.backgroundLocationAllowed(it) },
        opens = { it.openAppSettings() },
    )
    val battery = PhoneNeed(
        "camera_need_battery", AppIcons.Refresh, Res.string.camera_need_battery, Res.string.camera_need_battery_off, Res.string.camera_allowed,
        check = { BackgroundAllowances.unrestrictedBattery(it) },
        opens = { BackgroundAllowances.requestUnrestrictedBattery(it) },
    )

    val all = listOf(bluetooth, wifi, location, backgroundLocation, battery)
}

/** What 自动记账 needs while it is on: its accessibility service, and notifications to offer each payment in. */
object AutoBillNeeds {
    val service = PhoneNeed(
        "auto_bill_service", AppIcons.Accessibility, Res.string.share_assist_enable_service, Res.string.auto_bill_service_note,
        check = { ShareAccessibilityService.connected.value },
        opens = { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )
    val notifications = PhoneNeed(
        "auto_bill_notifications", AppIcons.BellOff, Res.string.auto_bill_notifications, Res.string.auto_bill_notifications_note,
        asked = ::notificationPermissions,
        check = { BackgroundAllowances.notifications(it) },
        opens = { it.openAppNotifications() },
    )

    val all = listOf(service, notifications)
}

/** What 侧键 needs: its accessibility service and Shizuku, with its permission. */
object SideKeyNeeds {
    val accessibility = PhoneNeed(
        "sidekey_accessibility", AppIcons.Accessibility, Res.string.side_key_service, Res.string.side_key_service_off, Res.string.side_key_service_on,
        check = { ShareAccessibilityService.connected.value },
        opens = { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )
    val shizuku = PhoneNeed(
        "sidekey_shizuku", AppIcons.Key, Res.string.side_key_shizuku,
        check = {
            runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        },
        opens = { context ->
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) context.startActivity(intent)
            else Toast.makeText(context, textOf(Res.string.side_key_shizuku_missing), Toast.LENGTH_LONG).show()
        },
    )

    val all = listOf(accessibility, shizuku)
}

/** The gallery's photos and videos, with where they were taken. */
object GalleryNeeds {
    val photos = PhoneNeed(
        "gallery_photos", AppIcons.Photos, Res.string.permission_gallery_title, Res.string.permission_gallery_body,
        asked = { (LocalMedia.permissions + Manifest.permission.ACCESS_MEDIA_LOCATION).toList() },
        check = { LocalMedia.access(it) != GalleryAccess.NONE },
        opens = { it.openAppSettings() },
    )

    val all = listOf(photos)
}

/** Installing the app's updates, which only the system's settings allow. */
object UpdateNeeds {
    val install = PhoneNeed(
        "update_install", AppIcons.Download, Res.string.permission_install_title, Res.string.permission_install_body,
        check = { it.packageManager.canRequestPackageInstalls() },
        opens = { it.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${it.packageName}".toUri())) },
    )

    val all = listOf(install)
}
