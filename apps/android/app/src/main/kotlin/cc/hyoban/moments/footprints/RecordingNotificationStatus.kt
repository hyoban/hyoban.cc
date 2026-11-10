package cc.hyoban.moments.footprints

import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.res.*
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.MomentsApplication

/** Read actual system state on demand; a promotion request is not proof of a visible chip. */
internal fun notificationPromotionStatus(context: Context): StringResource {
    if (Build.VERSION.SDK_INT < 36) return Res.string.footprints_promotion_unsupported
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return Res.string.footprints_promotion_notifications_off
    val notification = manager.activeNotifications.firstOrNull { it.id == RecordingNotifications.ID }?.notification
        ?: return Res.string.footprints_promotion_inactive
    if (notification.channelId == RecordingNotifications.REST_CHANNEL) return Res.string.footprints_promotion_resting
    if (!manager.canPostPromotedNotifications()) return Res.string.footprints_promotion_disabled
    if (notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return Res.string.footprints_promotion_active
    return Res.string.footprints_promotion_not_promoted
}

/** What the system shows of the recording notification now, read again on every return to the page. */
@Composable
internal fun rememberNotificationPromotion(recordingStatus: StringResource?): StringResource? {
    val context = LocalContext.current
    val resources = LocalResources.current
    var message by remember { mutableStateOf<StringResource?>(null) }
    fun refresh() {
        message = try { notificationPromotionStatus(context) } catch (_: Exception) { Res.string.footprints_promotion_unknown }
        (context.applicationContext as MomentsApplication).container.footprintDiagnostics.record("notification_promotion",
            mapOf("state" to message!!.key))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(recordingStatus) { refresh() }
    return message
}
