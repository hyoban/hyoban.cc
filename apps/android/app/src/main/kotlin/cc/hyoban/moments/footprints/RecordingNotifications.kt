package cc.hyoban.moments.footprints

import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.res.*
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.R
import cc.hyoban.moments.plugins.FootprintChannels

/** Notification channels and ColorOS live-update presentation, independent of recording policy. */
internal object RecordingNotifications {
    const val ID = 2201
    val CHANNEL = FootprintChannels.recording.id
    val REST_CHANNEL = FootprintChannels.rest.id
    const val PAUSE = "cc.hyoban.moments.footprints.PAUSE"

    fun createChannels(context: Context) {
        FootprintChannels.recording.create(context)
        FootprintChannels.rest.create(context)
    }

    fun build(context: Context, message: StringResource): Notification {
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData("moments://map".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(context, 1, Intent(context, RecordingService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // State follows actual fixes/GPS-free rest, not merely the user's recording toggle.
        // Some OEMs replace the small icon; the live-update chip also carries explicit text.
        val icon = when (message) {
            Res.string.footprints_recording -> R.drawable.ic_notification_footprints
            Res.string.footprints_resting -> R.drawable.ic_notification_recording_resting
            Res.string.footprints_waiting -> R.drawable.ic_notification_recording_waiting
            else -> R.drawable.ic_notification_recording_blocked
        }
        val builder = NotificationCompat.Builder(context,
            if (message == Res.string.footprints_resting) REST_CHANNEL else CHANNEL)
            .setSmallIcon(icon)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentTitle(textOf(Res.string.footprints_title))
            .setContentText(textOf(message)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, textOf(Res.string.footprints_pause), pause)
        if (Build.VERSION.SDK_INT >= 36 && message != Res.string.footprints_resting) {
            // ColorOS exposes this as “流体云显示实时活动” in per-app notification settings.
            // Rest stays in the notification drawer; the chip returns when GPS resumes.
            // Update only on state changes; no timers or synthetic progress for the chip.
            val label = when (message) {
                Res.string.footprints_recording -> Res.string.footprints_chip_recording
                Res.string.footprints_waiting -> Res.string.footprints_chip_waiting
                else -> Res.string.footprints_chip_blocked
            }
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(textOf(label))
                .setStyle(NotificationCompat.BigTextStyle().bigText(textOf(message)))
        }
        return builder.build()
    }
}
