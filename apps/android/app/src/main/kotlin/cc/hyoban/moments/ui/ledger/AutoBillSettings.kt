package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
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
    // Turned on without the accessibility service, the sheet says what it reads first (35).
    var explaining by remember { mutableStateOf(false) }
    val service = androidx.compose.ui.res.stringResource(R.string.share_assist_service_label)
    if (explaining) cc.hyoban.moments.ui.design.PermissionSheet(
        icon = AppIcons.Accessibility,
        title = stringResource(Res.string.permission_auto_bill_title),
        body = stringResource(Res.string.permission_auto_bill_body, service),
        facts = listOf(
            cc.hyoban.moments.ui.design.PermissionFact(AppIcons.EyeOff, stringResource(Res.string.permission_auto_bill_page)),
            cc.hyoban.moments.ui.design.PermissionFact(AppIcons.Phone, stringResource(Res.string.permission_auto_bill_local)),
            cc.hyoban.moments.ui.design.PermissionFact(AppIcons.Bell, stringResource(Res.string.permission_auto_bill_notify)),
        ),
        path = stringResource(Res.string.permission_auto_bill_path, service),
        primary = stringResource(Res.string.permission_open_settings),
        external = true,
        secondary = stringResource(Res.string.permission_not_now),
        onPrimary = { explaining = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        onDismiss = { explaining = false },
        tag = "permission_auto_bill",
    )
    val rows = buildList<Segment> {
        add { shapes ->
            SwitchRow(on, AppIcons.Receipt, stringResource(Res.string.auto_bill_label), "auto_bill", shapes, stringResource(Res.string.auto_bill_note)) {
                AutoBill.setEnabled(context, !on)
                if (!on && !connected) explaining = true
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
                leadingContent = { Icon(AppIcons.Accessibility, contentDescription = null) },
                supportingContent = { Text(stringResource(Res.string.auto_bill_service_note)) },
                modifier = Modifier.testTag("auto_bill_service"),
            ) { Text(stringResource(Res.string.share_assist_enable_service)) }
        }
        if (on && !notifying) add { shapes ->
            SegmentedListItem(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                },
                shapes = shapes,
                colors = segmentColors(),
                leadingContent = { Icon(AppIcons.BellOff, contentDescription = null) },
                supportingContent = { Text(stringResource(Res.string.auto_bill_notifications_note)) },
                modifier = Modifier.testTag("auto_bill_notifications"),
            ) { Text(stringResource(Res.string.auto_bill_notifications)) }
        }
    }
    SegmentedGroup(stringResource(Res.string.auto_bill_title_group), null, rows)
}
