package cc.hyoban.moments.footprints

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R

/** Read actual system state on demand; a promotion request is not proof of a visible chip. */
internal fun notificationPromotionStatus(context: Context): Int {
    if (Build.VERSION.SDK_INT < 36) return R.string.footprints_promotion_unsupported
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!manager.areNotificationsEnabled()) return R.string.footprints_promotion_notifications_off
    val notification = manager.activeNotifications.firstOrNull { it.id == RecordingNotifications.ID }?.notification
        ?: return R.string.footprints_promotion_inactive
    if (notification.channelId == RecordingNotifications.REST_CHANNEL) return R.string.footprints_promotion_resting
    if (!manager.canPostPromotedNotifications()) return R.string.footprints_promotion_disabled
    if (notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return R.string.footprints_promotion_active
    return R.string.footprints_promotion_not_promoted
}

@Composable
internal fun RecordingNotificationStatus(recordingStatus: Int?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var message by remember { mutableStateOf<Int?>(null) }
    fun refresh() {
        message = try { notificationPromotionStatus(context) } catch (_: Exception) { R.string.footprints_promotion_unknown }
        (context.applicationContext as MomentsApplication).container.footprintDiagnostics.record("notification_promotion",
            mapOf("state" to resources.getResourceEntryName(message!!)))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(recordingStatus) { refresh() }
    message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = ::refresh, modifier = Modifier.testTag("footprints_refresh_promotion")) {
        Text(stringResource(R.string.footprints_promotion_refresh))
    }
}
