package cc.hyoban.moments.footprints

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How footprints are recorded, apart from the recording itself, which stays on the map's sheet:
 * starting and pausing by connection, motion detection, what the status bar shows, running in
 * the background and the diagnostics log, each a segmented group as the settings page has them.
 */
@Composable
fun FootprintSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    SubPage(stringResource(R.string.footprint_settings_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl)
                    .semantics { testTagsAsResourceId = true },
            ) {
                ConnectionAutomationSettings(container)
                ActivityRecognitionSettings()
                StatusBarSettings()
                BackgroundSettings()
                DiagnosticsSettings(container)
            }
        }
    }
}

/** The live update the recording notification may become, the notification's own settings, and what its icons mean. */
@Composable
private fun StatusBarSettings() {
    val context = LocalContext.current
    val status by RecordingService.status.collectAsStateWithLifecycle()
    val promotion = rememberNotificationPromotion(status)
    var explaining by rememberSaveable { mutableStateOf(false) }
    SegmentedGroup(
        stringResource(R.string.footprints_status_icons),
        null,
        listOfNotNull(
            if (Build.VERSION.SDK_INT >= 36) { shapes ->
                ActionRow(Icons.Rounded.Stream, stringResource(R.string.footprints_live_status_settings), shapes,
                    supporting = promotion?.let { stringResource(it) }, tag = "footprints_live_status_settings", external = true) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                }
            } else null,
            { shapes ->
                ActionRow(Icons.Rounded.Notifications, stringResource(R.string.footprints_notification_settings), shapes,
                    supporting = if (Build.VERSION.SDK_INT < 36) promotion?.let { stringResource(it) } else null,
                    tag = "footprints_notification_settings", external = true) {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, RecordingNotifications.CHANNEL))
                }
            },
            { shapes ->
                ActionRow(Icons.Rounded.Info, stringResource(R.string.footprints_status_icons_help), shapes, tag = "footprints_status_icons") {
                    explaining = true
                }
            },
        ),
    )
    if (explaining) InfoDialog(
        stringResource(R.string.footprints_status_icons_help),
        listOfNotNull(
            stringResource(R.string.footprints_status_icons_note),
            if (Build.VERSION.SDK_INT >= 36) stringResource(R.string.footprints_live_status_note) else null,
        ),
    ) { explaining = false }
}

/** What keeps recording going in the background: the app's system settings, and on Android 10 coming back after a restart. */
@Composable
private fun BackgroundSettings() {
    val context = LocalContext.current
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    SegmentedGroup(
        stringResource(R.string.footprints_background_options),
        stringResource(R.string.footprints_background_note),
        listOfNotNull(
            { shapes ->
                ActionRow(Icons.Rounded.BatteryChargingFull, stringResource(R.string.footprints_settings), shapes, external = true) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }
            },
            if (Build.VERSION.SDK_INT == 29) { shapes ->
                ActionRow(Icons.Rounded.RestartAlt, stringResource(R.string.footprints_background_permission), shapes) {
                    background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            } else null,
        ),
    )
}

/** The diagnostics log, saved as a file wherever the user picks. */
@Composable
private fun DiagnosticsSettings(container: AppContainer) {
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
    SegmentedGroup(
        stringResource(R.string.footprints_diagnostics_section),
        stringResource(if (failed) R.string.footprints_error else R.string.footprints_diagnostics_note),
        listOf { shapes ->
            ActionRow(Icons.Rounded.Download, stringResource(R.string.footprints_export_diagnostics), shapes,
                tag = "footprints_export_diagnostics", enabled = !busy) {
                export.launch("footprint-diagnostics-${LocalDate.now()}.jsonl")
            }
        },
    )
}

/** A row that does something on a tap, with what it is set to under its name; [external] ones open the system's settings. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActionRow(
    icon: ImageVector,
    title: String,
    shapes: ListItemShapes,
    supporting: String? = null,
    tag: String? = null,
    enabled: Boolean = true,
    external: Boolean = false,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        colors = segmentColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = if (external) {
            { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) }
        } else {
            null
        },
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) { Text(title) }
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
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = if (index > 0) Modifier.padding(top = Space.m) else Modifier)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) } },
    )
}
