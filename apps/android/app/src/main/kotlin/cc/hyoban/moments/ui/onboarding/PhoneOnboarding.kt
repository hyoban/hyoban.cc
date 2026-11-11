package cc.hyoban.moments.ui.onboarding

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.settings.importSources
import cc.hyoban.moments.data.spec.Module
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.footprints.BackgroundAllowances
import cc.hyoban.moments.Setting
import cc.hyoban.moments.footprints.RecordingService
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.hasPermission
import cc.hyoban.moments.ui.components.openAppSettings
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.settings.ImportScreen
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 初次打开's footprints step on the phone, and the background step it may lead to. */
@Composable
fun PhoneOnboardingFootprints(container: PhoneContainer, onBack: () -> Unit, onDone: () -> Unit) {
    var background by rememberSaveable { mutableStateOf(false) }
    if (background) {
        BackHandler { background = false }
        Background({ background = false }, onDone)
    } else {
        Footprints(container, onBack, onBackground = { background = true }, onDone = onDone)
    }
}

@Composable
internal fun Footprints(container: PhoneContainer, onBack: () -> Unit, onBackground: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!RecordingService.hasPreciseLocation(context)) return@rememberLauncherForActivityResult onDone()
        container.scope.launch {
            try { RecordingService.control(context, "daily_on") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("Footprints", "Could not start recording", e) }
        }
        if (context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) onDone() else onBackground()
    }
    StepPage(stringResource(Res.string.onboarding_footprints), onBack, subtitle = stringResource(Res.string.onboarding_footprints_line), actions = {
        Button(
            onClick = {
                val asked = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) asked += Manifest.permission.POST_NOTIFICATIONS
                permissions.launch(asked.toTypedArray())
            },
            modifier = Modifier.fillMaxWidth().testTag("onboarding_footprints_on"),
        ) { Text(stringResource(Res.string.onboarding_fp_on)) }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("onboarding_later")) { Text(stringResource(Res.string.onboarding_later)) }
    }) {
        Fact(AppIcons.Shield, stringResource(Res.string.onboarding_fp_local))
        Fact(AppIcons.Eye, stringResource(Res.string.onboarding_fp_public))
        Fact(AppIcons.Bell, stringResource(Res.string.onboarding_fp_notification))
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.gutter, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.tx2)
        Text(text, style = AppTheme.typography.body)
    }
}

/** Footprints given location only while the app is used: what that leaves, and the way to the system's settings. */
@Composable
internal fun Background(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var background by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    var notifying by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var live by remember { mutableStateOf(BackgroundAllowances.liveUpdates(context)) }
    var battery by remember { mutableStateOf(BackgroundAllowances.unrestrictedBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        background = context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        notifying = NotificationManagerCompat.from(context).areNotificationsEnabled()
        live = BackgroundAllowances.liveUpdates(context)
        battery = BackgroundAllowances.unrestrictedBattery(context)
    }
    StepPage(stringResource(Res.string.onboarding_footprints), onBack, actions = {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("onboarding_done")) { Text(stringResource(Res.string.onboarding_done)) }
    }) {
        val rows = buildList<Segment> {
            add { shapes ->
                SegmentedListItem(
                    onClick = { context.openAppSettings() },
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = {
                        Icon(if (background) AppIcons.Check else AppIcons.Alert, contentDescription = null, tint = if (background) tokens.accent else tokens.danger)
                    },
                    supportingContent = { Text(stringResource(if (background) Res.string.onboarding_allowed else Res.string.onboarding_background_note)) },
                    trailingContent = { Icon(AppIcons.External, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.testTag("onboarding_background"),
                ) { Text(stringResource(if (background) Res.string.footprints_needs_location else Res.string.onboarding_background)) }
            }
            add { shapes ->
                SegmentedListItem(
                    onClick = {
                        if (!notifying) context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = { Icon(AppIcons.Bell, contentDescription = null) },
                    supportingContent = { Text(stringResource(if (notifying) Res.string.onboarding_allowed else Res.string.onboarding_notifications_off)) },
                    trailingContent = {
                        if (notifying) Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent)
                        else Icon(AppIcons.External, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                ) { Text(stringResource(Res.string.onboarding_notifications)) }
            }
            if (Build.VERSION.SDK_INT >= 36 && notifying) add { shapes ->
                AllowanceRow(AppIcons.Stream, Res.string.footprints_needs_live, Res.string.footprints_needs_live_note, live, shapes, "onboarding_live") {
                    BackgroundAllowances.openLiveUpdates(context)
                }
            }
            add { shapes ->
                AllowanceRow(AppIcons.BatteryCharging, Res.string.footprints_needs_battery, Res.string.footprints_needs_battery_note, battery, shapes, "onboarding_battery") {
                    BackgroundAllowances.requestUnrestrictedBattery(context)
                }
            }
            if (BackgroundAllowances.autoStartSettings) add { shapes ->
                SegmentedListItem(
                    onClick = { BackgroundAllowances.openAutoStart(context) },
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
                    supportingContent = { Text(stringResource(Res.string.footprints_auto_start_path)) },
                    trailingContent = { Icon(AppIcons.External, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.testTag("onboarding_auto_start"),
                ) { Text(stringResource(Res.string.footprints_auto_start)) }
            }
        }
        SegmentedGroup(null, null, rows)
    }
}

/** A system allowance on the footprints' last step: allowed, it is checked and does nothing; otherwise it opens the system's settings. */
@Composable
private fun AllowanceRow(icon: ImageVector, title: StringResource, off: StringResource, allowed: Boolean, shapes: cc.hyoban.moments.ui.design.ListItemShapes, tag: String, onOpen: () -> Unit) {
    SegmentedListItem(
        onClick = { if (!allowed) onOpen() },
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(stringResource(if (allowed) Res.string.onboarding_allowed else off)) },
        trailingContent = {
            if (allowed) Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent)
            else Icon(AppIcons.External, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        modifier = Modifier.testTag(tag),
    ) { Text(stringResource(title)) }
}
