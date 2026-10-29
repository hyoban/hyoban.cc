package cc.hyoban.moments.ui.ledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.ledger.AutoBill
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.settings.segmentColors

/**
 * 自动记账's switch, and what it still needs when on: the accessibility service turned on in
 * the system's settings, and notifications, which it offers each payment in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AutoBillGroup() {
    val context = LocalContext.current
    val enabled by AutoBill.enabledFlow(context).collectAsStateWithLifecycle()
    val connected by ShareAccessibilityService.connected.collectAsStateWithLifecycle()
    var notifying by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        notifying = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifying = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val on = enabled == true
    val rows = buildList<Segment> {
        add { shapes ->
            SwitchRow(on, Icons.AutoMirrored.Rounded.ReceiptLong, stringResource(R.string.auto_bill_label), "auto_bill", shapes, stringResource(R.string.auto_bill_note)) {
                AutoBill.setEnabled(context, !on)
                if (!on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (on && !connected) add { shapes ->
            SegmentedListItem(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                shapes = shapes,
                colors = segmentColors(),
                leadingContent = { Icon(Icons.Rounded.AccessibilityNew, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.auto_bill_service_note)) },
                modifier = Modifier.testTag("auto_bill_service"),
            ) { Text(stringResource(R.string.share_assist_enable_service)) }
        }
        if (on && !notifying) add { shapes ->
            SegmentedListItem(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                },
                shapes = shapes,
                colors = segmentColors(),
                leadingContent = { Icon(Icons.Rounded.NotificationsOff, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.auto_bill_notifications_note)) },
                modifier = Modifier.testTag("auto_bill_notifications"),
            ) { Text(stringResource(R.string.auto_bill_notifications)) }
        }
    }
    SegmentedGroup(stringResource(R.string.auto_bill_title_group), null, rows)
}
