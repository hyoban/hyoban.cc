package cc.hyoban.moments.footprints

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.R

/** Notification channels and ColorOS live-update presentation, independent of recording policy. */
internal object RecordingNotifications {
    const val ID = 2201
    const val CHANNEL = "footprint-recording"
    const val REST_CHANNEL = "footprint-recording-rest"
    const val PAUSE = "cc.hyoban.moments.footprints.PAUSE"

    fun createChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            // Normal importance keeps status icons visible when silent notifications are hidden.
            // No sound/vibration is needed for a recorder. Android preserves existing user/channel
            // preferences; upgraded installs can adjust visibility through the sheet's settings link.
            NotificationChannel(CHANNEL, context.getString(R.string.footprints_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        // A separate quiet channel keeps rest in the drawer without changing the user's
        // active-recording channel settings. Keep the foreground service and motion sensor alive.
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(REST_CHANNEL, context.getString(R.string.footprints_rest_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun build(context: Context, message: Int): Notification {
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData("moments://map".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(context, 1, Intent(context, RecordingService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // State follows actual fixes/GPS-free rest, not merely the user's recording toggle.
        // Some OEMs replace the small icon; the live-update chip also carries explicit text.
        val icon = when (message) {
            R.string.footprints_recording -> R.drawable.ic_notification_footprints
            R.string.footprints_resting -> R.drawable.ic_notification_recording_resting
            R.string.footprints_waiting -> R.drawable.ic_notification_recording_waiting
            else -> R.drawable.ic_notification_recording_blocked
        }
        val builder = NotificationCompat.Builder(context,
            if (message == R.string.footprints_resting) REST_CHANNEL else CHANNEL)
            .setSmallIcon(icon)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentTitle(context.getString(R.string.footprints_title))
            .setContentText(context.getString(message)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.footprints_pause), pause)
        if (Build.VERSION.SDK_INT >= 36 && message != R.string.footprints_resting) {
            // ColorOS exposes this as “流体云显示实时活动” in per-app notification settings.
            // Rest stays in the notification drawer; the chip returns when GPS resumes.
            // Update only on state changes; no timers or synthetic progress for the chip.
            val label = when (message) {
                R.string.footprints_recording -> R.string.footprints_chip_recording
                R.string.footprints_waiting -> R.string.footprints_chip_waiting
                else -> R.string.footprints_chip_blocked
            }
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(context.getString(label))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(message)))
        }
        return builder.build()
    }
}
