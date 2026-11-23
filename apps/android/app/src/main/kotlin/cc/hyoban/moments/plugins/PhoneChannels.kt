package cc.hyoban.moments.plugins

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource

/**
 * A notification channel a plugin posts in, added to [PhonePoints.channels]; the host's own are
 * [HostChannels]. Each is made by [create] just before its first notification, as it always was, so
 * the system's settings list only the channels of what has posted, each named in the phone's language
 * of the time. Its id, name and importance never change: a channel's importance can't be changed once
 * made, and a new id would leave the owner's settings behind on the old one.
 */
class PhoneChannel(
    val id: String,
    val name: StringResource,
    val importance: Int,
    val description: StringResource? = null,
    /** No sound, no vibration, no badge. */
    val quiet: Boolean = false,
    /** The ids it took over from, deleted as it is made. */
    val replaces: List<String> = emptyList(),
) {
    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        replaces.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(
            NotificationChannel(id, textOf(name), importance).apply {
                this@PhoneChannel.description?.let { setDescription(textOf(it)) }
                if (quiet) {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            },
        )
    }
}

/** The footprints' channels: recording, its quiet rest, and the connections watched. */
object FootprintChannels {
    // Normal importance keeps status icons visible when silent notifications are hidden.
    // No sound/vibration is needed for a recorder. Android preserves existing user/channel
    // preferences; upgraded installs can adjust visibility through the sheet's settings link.
    val recording = PhoneChannel("footprint-recording", Res.string.footprints_title, NotificationManager.IMPORTANCE_DEFAULT, quiet = true)

    // A separate quiet channel keeps rest in the drawer without changing the user's
    // active-recording channel settings. Keep the foreground service and motion sensor alive.
    val rest = PhoneChannel("footprint-recording-rest", Res.string.footprints_rest_channel, NotificationManager.IMPORTANCE_MIN, quiet = true)

    val connectionWatch = PhoneChannel("connection-watch", Res.string.connection_automation_title, NotificationManager.IMPORTANCE_LOW)

    val all = listOf(recording, rest, connectionWatch)
}

/** 相机's: the location being sent. */
object CameraChannels {
    val location = PhoneChannel("camera_location", Res.string.camera_channel, NotificationManager.IMPORTANCE_LOW)
}

/** 自动记账's: each payment offered as a bill. */
object AutoBillChannels {
    /** High importance, so a payment pops up over the payment app; a channel's can't be raised once made. The first, at default importance, only ever on a phone it was tried on. */
    val payments = PhoneChannel(
        "ledger-payments-alert", Res.string.auto_bill_channel, NotificationManager.IMPORTANCE_HIGH,
        replaces = listOf("ledger-payments"),
    )
}

/** Updates': a newer release. */
object UpdateChannels {
    val updates = PhoneChannel("updates", Res.string.update_channel, NotificationManager.IMPORTANCE_DEFAULT)
}

/** The host's own, which no plugin holds: publishing to the site. */
object HostChannels {
    val publishing = PhoneChannel("publishing", Res.string.notification_channel_publishing, NotificationManager.IMPORTANCE_LOW)

    val all = listOf(publishing)
}
