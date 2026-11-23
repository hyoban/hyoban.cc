package cc.hyoban.moments.ui.ledger

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.plugins.AutoBillNeeds
import cc.hyoban.moments.plugins.AutoBillPlugin
import cc.hyoban.moments.plugins.PhonePlugins
import cc.hyoban.moments.ui.components.hasPermission
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.ledger.AutoBill
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsGroupView
import cc.hyoban.moments.ui.settings.SettingsRow

/**
 * 自动记账's switch, and what it still needs when on: the accessibility service turned on in
 * the system's settings, and notifications, which it offers each payment in.
 */
@Composable
internal fun autoBillGroup(): SettingsGroupSpec {
    val context = LocalContext.current
    val enabled by AutoBill.enabledFlow(context).collectAsStateWithLifecycle()
    val connected by ShareAccessibilityService.connected.collectAsStateWithLifecycle()
    var notifying by remember { mutableStateOf(AutoBillNeeds.notifications.given(context)) }
    LifecycleResumeEffect(Unit) {
        notifying = AutoBillNeeds.notifications.given(context)
        onPauseOrDispose {}
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifying = AutoBillNeeds.notifications.given(context)
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
        onPrimary = { explaining = false; AutoBillNeeds.service.open(context) },
        onDismiss = { explaining = false },
        tag = "permission_auto_bill",
    )
    val rows = buildList<SettingsRow> {
        add(
            SettingsRow.Switch(AppIcons.Receipt, stringResource(Res.string.auto_bill_label), "auto_bill", on, stringResource(Res.string.auto_bill_note)) {
                AutoBill.setEnabled(context, !on)
                if (!on && !connected) explaining = true
                if (!on) AutoBillNeeds.notifications.permissions.firstOrNull { !context.hasPermission(it) }?.let(permission::launch)
            },
        )
        // What it still needs, as its plugin declares them, in their order.
        if (on) PhonePlugins.needsOf(AutoBillPlugin).forEach { need ->
            val given = when (need) {
                AutoBillNeeds.service -> connected
                AutoBillNeeds.notifications -> notifying
                else -> need.given(context)
            }
            if (!given) add(
                SettingsRow.Action(need.icon, stringResource(need.title), need.key, supporting = need.why?.let { stringResource(it) }) {
                    need.open(context)
                },
            )
        }
    }
    return SettingsGroupSpec(stringResource(Res.string.auto_bill_title_group), null, rows)
}
