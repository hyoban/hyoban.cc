package cc.hyoban.moments.footprints

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsGroupView
import cc.hyoban.moments.ui.settings.SettingsRow
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 足迹记录, a page under 我 and from the map's footprint sheet: how footprints are recorded,
 * apart from the recording itself, which stays on that sheet. What the status bar shows, running
 * in the background, starting and pausing by connection, motion detection and the diagnostics
 * log, each a segmented group; what goes deeper opens as a dialog over the page.
 */
@Composable
fun FootprintSettingsScreen(container: PhoneContainer, onBack: () -> Unit) {
    SubPage(stringResource(Res.string.footprint_settings_title), onBack, Modifier.background(AppColors.page), subtitle = stringResource(Res.string.footprint_settings_subtitle)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl)
                    .semantics { testTagsAsResourceId = true },
            ) {
                NeedsSettings()
                StatusBarSettings()
                BackgroundSettings()
                ConnectionAutomationSettings(container)
                ActivityRecognitionSettings()
                DiagnosticsSettings(container)
            }
        }
    }
}

/**
 * What recording is missing, one row a permission, the way to the system's settings at its end;
 * the group isn't there when nothing is (35).
 */
@Composable
private fun NeedsSettings() {
    val context = LocalContext.current
    fun read() = listOf(
        BackgroundAllowances.notifications(context),
        !RecordingService.hasPreciseLocation(context) || context.granted(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        !ActivityTransitions.enabled(context) || ActivityTransitions.permitted(context),
        BackgroundAllowances.liveUpdates(context),
        BackgroundAllowances.unrestrictedBattery(context),
    )
    var state by androidx.compose.runtime.remember { mutableStateOf(read()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { state = read() }
    val (notifying, background, activity, live, battery) = state
    val rows = buildList<SettingsRow> {
        if (!notifying) add(
            SettingsRow.External(AppIcons.Bell, stringResource(Res.string.onboarding_notifications), "footprints_needs_notifications",
                supporting = stringResource(Res.string.footprints_needs_notifications)) {
                BackgroundAllowances.openNotifications(context)
            },
        )
        if (!live) add(
            SettingsRow.External(AppIcons.Stream, stringResource(Res.string.footprints_needs_live), "footprints_needs_live",
                supporting = stringResource(Res.string.footprints_needs_live_note)) {
                BackgroundAllowances.openLiveUpdates(context)
            },
        )
        if (!battery) add(
            SettingsRow.External(AppIcons.BatteryCharging, stringResource(Res.string.footprints_needs_battery), "footprints_needs_battery",
                supporting = stringResource(Res.string.footprints_needs_battery_note)) {
                BackgroundAllowances.requestUnrestrictedBattery(context)
            },
        )
        if (!background) add(
            SettingsRow.External(AppIcons.Locate, stringResource(Res.string.footprints_needs_location), "footprints_needs_location",
                supporting = stringResource(Res.string.footprints_needs_location_note)) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            },
        )
        if (!activity) add(
            SettingsRow.External(AppIcons.Walk, stringResource(Res.string.footprints_needs_activity), "footprints_needs_activity",
                supporting = stringResource(Res.string.footprints_needs_activity_note)) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            },
        )
    }
    SettingsGroupView(SettingsGroupSpec(stringResource(Res.string.footprints_needs), null, rows))
}

/** The live update the recording notification may become, the notification's own settings, and what its icons mean. */
@Composable
private fun StatusBarSettings() {
    val context = LocalContext.current
    val status by RecordingService.status.collectAsStateWithLifecycle()
    val promotion = rememberNotificationPromotion(status)
    var explaining by rememberSaveable { mutableStateOf(false) }
    SettingsGroupView(
        SettingsGroupSpec(
            stringResource(Res.string.footprints_status_icons),
            null,
            listOfNotNull(
                if (Build.VERSION.SDK_INT >= 36) {
                    SettingsRow.External(AppIcons.Stream, stringResource(Res.string.footprints_live_status_settings), "footprints_live_status_settings",
                        supporting = promotion?.let { stringResource(it) }) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                        }
                    }
                } else null,
                SettingsRow.External(AppIcons.Bell, stringResource(Res.string.footprints_notification_settings), "footprints_notification_settings",
                    supporting = if (Build.VERSION.SDK_INT < 36) promotion?.let { stringResource(it) } else null) {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, RecordingNotifications.CHANNEL))
                },
                SettingsRow.Action(AppIcons.Info, stringResource(Res.string.footprints_status_icons_help), "footprints_status_icons") {
                    explaining = true
                },
            ),
        ),
    )
    if (explaining) InfoDialog(
        stringResource(Res.string.footprints_status_icons_help),
        listOfNotNull(
            stringResource(Res.string.footprints_status_icons_note),
            if (Build.VERSION.SDK_INT >= 36) stringResource(Res.string.footprints_live_status_note) else null,
        ),
    ) { explaining = false }
}

/** What keeps recording going in the background: ColorOS's auto-start, or the app's system settings elsewhere. */
@Composable
private fun BackgroundSettings() {
    val context = LocalContext.current
    SettingsGroupView(
        SettingsGroupSpec(
            stringResource(Res.string.footprints_background_options),
            stringResource(Res.string.footprints_background_note),
            listOf(
                if (BackgroundAllowances.autoStartSettings) {
                    SettingsRow.External(AppIcons.Refresh, stringResource(Res.string.footprints_auto_start), "footprints_auto_start",
                        supporting = stringResource(Res.string.footprints_auto_start_path)) {
                        BackgroundAllowances.openAutoStart(context)
                    }
                } else {
                    SettingsRow.External(AppIcons.BatteryCharging, stringResource(Res.string.footprints_settings), null) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                },
            ),
        ),
    )
}

/** The diagnostics log, saved as a file wherever the user picks. */
@Composable
private fun DiagnosticsSettings(container: PhoneContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            failed = false
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                    output.bufferedWriter(Charsets.UTF_8).use {
                        container.footprintDiagnostics.export(it)
                        container.footprints.exportReplayDiagnostics(it)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true } finally { busy = false }
        }
    }
    SettingsGroupView(
        SettingsGroupSpec(
            stringResource(Res.string.footprints_diagnostics_section),
            stringResource(if (failed) Res.string.footprints_error else Res.string.footprints_diagnostics_note),
            listOf(
                SettingsRow.Action(AppIcons.Download, stringResource(Res.string.footprints_export_diagnostics), "footprints_export_diagnostics",
                    enabled = !busy) {
                    export.launch("footprint-diagnostics-${LocalDate.now()}.jsonl")
                },
            ),
        ),
    )
}

/** The longer explanation behind a row, kept out of the page until asked for. */
@Composable
internal fun InfoDialog(title: String, paragraphs: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                paragraphs.forEachIndexed { index, text ->
                    Text(text, style = AppTheme.typography.body, modifier = if (index > 0) Modifier.padding(top = Space.m) else Modifier)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_confirm)) } },
    )
}
