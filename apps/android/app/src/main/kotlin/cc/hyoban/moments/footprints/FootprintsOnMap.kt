package cc.hyoban.moments.footprints

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class TrackView(
    val state: RecordingState = RecordingState(),
    val history: FootprintHistory = FootprintHistory(emptyList(), emptyList()),
    val points: List<TrackPoint> = emptyList(),
    val stats: TrackStats = TrackStats(0.0, 0),
    val segments: List<List<TrackPoint>> = emptyList(),
)

/** Controls and selection live with the map, even while the bottom panel is closed. */
data class FootprintsMapState(
    val segments: List<List<TrackPoint>>,
    val frameRequested: Boolean,
    val onFramed: () -> Unit,
    val open: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootprintsOnMap(container: AppContainer, content: @Composable (FootprintsMapState) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showTracks by rememberSaveable { mutableStateOf(true) }
    var frameRequested by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = container.footprints
    val revision by store.revision.collectAsStateWithLifecycle()
    val status by RecordingService.status.collectAsStateWithLifecycle()
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var session by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = TrackSelection(day = day.takeIf { session == null }, session = session)
    var view by remember { mutableStateOf(TrackView()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var backgroundOptions by rememberSaveable { mutableStateOf(false) }
    var statusBarOptions by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TrackSelection?>(null) }
    var exportDay by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSession by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }

    fun work(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                error = R.string.footprints_error
            } finally { busy = false }
        }
    }
    fun control(action: String) = work {
        RecordingService.control(context, action)
        if (action == "start_trip") session = withContext(Dispatchers.IO) { store.state().session }
    }

    LaunchedEffect(revision, selection) {
        try {
            view = withContext(Dispatchers.IO) {
                val points = store.points(selection)
                TrackView(store.state(), store.history(), points, trackStats(points), trackSegments(points))
            }
            loaded = true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            error = R.string.footprints_error
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingAction
        pendingAction = null
        if (RecordingService.hasPreciseLocation(context) && action != null) control(action)
        else error = R.string.footprints_permission_needed
    }
    fun enable(action: String) {
        pendingAction = action
        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) work {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                output.bufferedWriter(Charsets.UTF_8).use { store.export(TrackSelection(exportDay, exportSession), it) }
            }
        }
    }
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) work {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                output.bufferedWriter(Charsets.UTF_8).use { container.footprintDiagnostics.export(it) }
            }
        }
    }
    val state = view.state
    content(FootprintsMapState(
        segments = if (showTracks) view.segments else emptyList(),
        frameRequested = frameRequested,
        onFramed = { frameRequested = false },
        open = { expanded = true },
    ))
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("footprints_panel"),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.footprints_title), style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.footprints_show), Modifier.weight(1f))
                    Switch(showTracks, onCheckedChange = { showTracks = it }, modifier = Modifier.testTag("footprints_layer"))
                }
                Text(stringResource(R.string.footprints_local_note), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.footprints_daily), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.footprints_daily_note), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(state.daily, onCheckedChange = { if (it) enable("daily_on") else control("daily_off") },
                                enabled = loaded && !busy, modifier = Modifier.testTag("footprints_daily"))
                        }
                        Button(
                            onClick = { if (state.session == null) enable("start_trip") else control("end_trip") },
                            enabled = loaded && !busy, modifier = Modifier.fillMaxWidth().testTag("footprints_trip"),
                        ) { Text(stringResource(if (state.session == null) R.string.footprints_start_trip else R.string.footprints_end_trip)) }
                        if (state.session != null) {
                            Text(stringResource(R.string.footprints_trip_note), style = MaterialTheme.typography.bodySmall)
                        }
                        val message = when {
                            !loaded -> R.string.footprints_loading
                            state.paused -> R.string.footprints_paused
                            !state.requested -> R.string.footprints_off
                            else -> status ?: R.string.footprints_interrupted
                        }
                        Text(stringResource(message), modifier = Modifier.testTag("footprints_status"))
                        if (state.requested) {
                            OutlinedButton(
                                onClick = { if (state.paused || status == null || status == R.string.footprints_permission_needed || status == R.string.footprints_error) enable("resume") else control("pause") },
                                enabled = !busy, modifier = Modifier.testTag("footprints_pause"),
                            ) {
                                Text(stringResource(if (state.paused || status == null || status == R.string.footprints_permission_needed || status == R.string.footprints_error) R.string.footprints_resume else R.string.footprints_pause))
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { backgroundOptions = !backgroundOptions }) {
                    Text(stringResource(R.string.footprints_background_options))
                }
                if (backgroundOptions) {
                    Text(stringResource(R.string.footprints_background_note), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }) { Text(stringResource(R.string.footprints_settings)) }
                    if (Build.VERSION.SDK_INT == 29) {
                        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                        TextButton(onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                            Text(stringResource(R.string.footprints_background_permission))
                        }
                    }
                }
            }
            item { ConnectionAutomationSettings(container) }
            item { ActivityRecognitionSettings() }
            item {
                TextButton(onClick = { statusBarOptions = !statusBarOptions }, modifier = Modifier.testTag("footprints_status_icons")) {
                    Text(stringResource(R.string.footprints_status_icons))
                }
                if (statusBarOptions) {
                    RecordingNotificationStatus(status)
                    if (Build.VERSION.SDK_INT >= 36) {
                        Text(stringResource(R.string.footprints_live_status_note), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    "package:${context.packageName}".toUri()))
                            }
                        }, modifier = Modifier.testTag("footprints_live_status_settings")) {
                            Text(stringResource(R.string.footprints_live_status_settings))
                        }
                    }
                    Text(stringResource(R.string.footprints_status_icons_note), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, RecordingNotifications.CHANNEL))
                    }, modifier = Modifier.testTag("footprints_notification_settings")) {
                        Text(stringResource(R.string.footprints_notification_settings))
                    }
                }
            }
            error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) } }
            item { Text(stringResource(R.string.footprints_days), style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((listOf(LocalDate.now().toString(), day) + view.history.days).distinct().sortedDescending()) { date ->
                        FilterChip(selected = session == null && day == date, onClick = { day = date; session = null }, label = { Text(date) })
                    }
                }
            }
            if (view.history.sessions.isNotEmpty()) {
                item { Text(stringResource(R.string.footprints_trips), style = MaterialTheme.typography.titleMedium) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(view.history.sessions, key = { it.id }) { trip ->
                            FilterChip(selected = session == trip.id, onClick = { session = trip.id }, label = {
                                Text(stamp(trip.started) + if (trip.ended == null) " · " + stringResource(R.string.footprints_active_trip) else "")
                            })
                        }
                    }
                }
            }
            item {
                val selectedTrip = view.history.sessions.firstOrNull { it.id == session }
                Text(if (selectedTrip == null) day else stamp(selectedTrip.started) + " — " + (selectedTrip.ended?.let(::stamp) ?: stringResource(R.string.footprints_active_trip)), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.footprints_stats, view.stats.metres / 1000, pluralStringResource(R.plurals.footprints_points, view.points.size, view.points.size), view.stats.recordedMillis / 60_000))
                view.points.lastOrNull()?.let { Text(stringResource(R.string.footprints_last_fix, stamp(it.time)), style = MaterialTheme.typography.bodySmall) }
            }
            item {
                if (view.points.isEmpty()) {
                    Text(stringResource(R.string.footprints_empty), Modifier.padding(vertical = 32.dp))
                } else {
                    FilledTonalButton(
                        onClick = { showTracks = true; frameRequested = true; expanded = false },
                        modifier = Modifier.fillMaxWidth().testTag("footprints_frame"),
                    ) { Text(stringResource(R.string.footprints_frame)) }
                }
            }
            item {
                Column {
                    OutlinedButton(onClick = {
                        exportDay = selection.day
                        exportSession = selection.session
                        export.launch("footprints-${session ?: day}.gpx")
                    }, enabled = loaded && !busy && view.points.isNotEmpty()) { Text(stringResource(R.string.footprints_export)) }
                    TextButton(onClick = {
                        exportDay = null
                        exportSession = null
                        export.launch("footprints-all.gpx")
                    }, enabled = loaded && !busy && view.history.days.isNotEmpty()) { Text(stringResource(R.string.footprints_export_all)) }
                    TextButton(onClick = { exportDiagnostics.launch("footprint-diagnostics-${LocalDate.now()}.jsonl") },
                        enabled = !busy, modifier = Modifier.testTag("footprints_export_diagnostics")) {
                        Text(stringResource(R.string.footprints_export_diagnostics))
                    }
                    Text(stringResource(R.string.footprints_diagnostics_note), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { deleting = selection }, enabled = loaded && !busy && !state.requested && (view.points.isNotEmpty() || session != null)) {
                        Text(stringResource(R.string.footprints_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.footprints_delete_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.footprints_delete)) },
            text = { Text(stringResource(R.string.footprints_delete_confirm)) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                work {
                    withContext(Dispatchers.IO) { store.delete(target) }
                    if (target.session != null) session = null
                }
            }) { Text(stringResource(R.string.footprints_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.footprints_cancel)) } },
        )
    }
}

private fun stamp(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
