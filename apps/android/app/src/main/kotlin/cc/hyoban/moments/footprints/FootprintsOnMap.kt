package cc.hyoban.moments.footprints

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FootprintsOnMap(container: AppContainer, onSettings: () -> Unit, content: @Composable (FootprintsMapState) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The sheet is a window over every page, so it closes for the settings page and opens again on the way back.
    var reopen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (reopen) { reopen = false; expanded = true } }
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
    var deleting by remember { mutableStateOf<TrackSelection?>(null) }
    var exportDay by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSession by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRaw by rememberSaveable { mutableStateOf(false) }
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
    // ColorOS's file manager names the file from the type: application/gpx+xml became ".gpx+xml"
    // and octet-stream ".bin". Only a wildcard keeps the ".gpx" name as given.
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) work {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                output.bufferedWriter(Charsets.UTF_8).use { store.export(TrackSelection(exportDay, exportSession), it, raw = exportRaw) }
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
    // Paused, never started or stopped by an error, the row offers to resume; otherwise to pause.
    val resumable = state.paused || status == null || status == R.string.footprints_permission_needed || status == R.string.footprints_error
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        containerColor = AppColors.sheet,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("footprints_panel"),
    ) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = Space.xl)) {
            // Its name, and the way to how it records, as the place sheet has its name and the way there.
            item {
                Row(Modifier.fillMaxWidth().padding(start = Space.gutter + Space.l, end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.footprints_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { expanded = false; reopen = true; onSettings() }, modifier = Modifier.testTag("footprint_settings")) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.footprint_settings_title))
                    }
                }
            }
            item {
                SegmentedGroup(
                    stringResource(R.string.footprints_section_recording),
                    error?.let { stringResource(it) } ?: stringResource(R.string.footprints_local_note),
                    listOfNotNull(
                        { shapes ->
                            SwitchRow(state.daily, Icons.Rounded.Autorenew, stringResource(R.string.footprints_daily), "footprints_daily", shapes,
                                supporting = stringResource(R.string.footprints_daily_note), enabled = loaded && !busy) {
                                if (!state.daily) enable("daily_on") else control("daily_off")
                            }
                        },
                        { shapes ->
                            SwitchRow(state.session != null, Icons.Rounded.Flag, stringResource(R.string.footprints_trip), "footprints_trip", shapes,
                                supporting = stringResource(if (state.session != null) R.string.footprints_trip_note else R.string.footprints_trip_off_note),
                                enabled = loaded && !busy) {
                                if (state.session == null) enable("start_trip") else control("end_trip")
                            }
                        },
                        if (state.requested) { shapes ->
                            ActionRow(
                                if (resumable) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                stringResource(if (resumable) R.string.footprints_resume else R.string.footprints_pause),
                                shapes,
                                supporting = stringResource(if (state.paused) R.string.footprints_paused else status ?: R.string.footprints_interrupted),
                                tag = "footprints_pause",
                                enabled = !busy,
                            ) { if (resumable) enable("resume") else control("pause") }
                        } else null,
                        { shapes ->
                            SwitchRow(showTracks, Icons.Rounded.Layers, stringResource(R.string.footprints_show), "footprints_layer", shapes) {
                                showTracks = !showTracks
                            }
                        },
                    ),
                )
            }
            item {
                val selectedTrip = view.history.sessions.firstOrNull { it.id == session }
                SegmentedGroup(
                    stringResource(R.string.footprints_section_tracks),
                    stringResource(R.string.footprints_quality_note),
                    listOf(
                        { shapes ->
                            // What is chosen, and a tap shows the whole of it on the map.
                            SegmentedListItem(
                                onClick = { showTracks = true; frameRequested = true; expanded = false },
                                shapes = shapes,
                                enabled = view.points.isNotEmpty(),
                                colors = segmentColors(),
                                leadingContent = { Icon(if (selectedTrip == null) Icons.Rounded.CalendarToday else Icons.Rounded.Flag, contentDescription = null) },
                                supportingContent = {
                                    Text(if (view.points.isEmpty()) stringResource(R.string.footprints_empty) else listOfNotNull(
                                        stringResource(R.string.footprints_stats, view.stats.metres / 1000, pluralStringResource(R.plurals.footprints_points, view.points.size, view.points.size), view.stats.recordedMillis / 60_000),
                                        view.points.lastOrNull()?.let { stringResource(R.string.footprints_last_fix, stamp(it.time)) },
                                    ).joinToString("\n"))
                                },
                                trailingContent = if (view.points.isNotEmpty()) {
                                    { Icon(Icons.Rounded.ZoomOutMap, contentDescription = stringResource(R.string.footprints_frame)) }
                                } else null,
                                modifier = Modifier.testTag("footprints_frame"),
                            ) {
                                Text(if (selectedTrip == null) day else stamp(selectedTrip.started) + " – " + (selectedTrip.ended?.let(::stamp) ?: stringResource(R.string.footprints_active_trip)))
                            }
                        },
                        { shapes ->
                            ActionRow(Icons.Rounded.FileDownload, stringResource(R.string.footprints_export), shapes,
                                enabled = loaded && !busy && view.points.isNotEmpty()) {
                                exportDay = selection.day
                                exportSession = selection.session
                                exportRaw = false
                                export.launch("footprints-${session ?: day}.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(Icons.Rounded.SaveAlt, stringResource(R.string.footprints_export_all), shapes,
                                enabled = loaded && !busy && view.history.days.isNotEmpty()) {
                                exportDay = null
                                exportSession = null
                                exportRaw = false
                                export.launch("footprints-all.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(Icons.Rounded.FileDownload, stringResource(R.string.footprints_export_raw), shapes,
                                supporting = stringResource(R.string.footprints_export_raw_note),
                                enabled = loaded && !busy && (selection.day in view.history.days || session != null)) {
                                exportDay = selection.day
                                exportSession = selection.session
                                exportRaw = true
                                export.launch("footprints-raw-${session ?: day}.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(Icons.Rounded.Delete, stringResource(R.string.footprints_delete), shapes,
                                supporting = stringResource(R.string.footprints_delete_note),
                                enabled = loaded && !busy && !state.requested && (selection.day in view.history.days || session != null)) {
                                deleting = selection
                            }
                        },
                    ),
                    header = {
                        // Days, then trips, as filter chips: one of them chosen at a time.
                        Column(Modifier.padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                items((listOf(LocalDate.now().toString(), day) + view.history.days).distinct().sortedDescending()) { date ->
                                    FilterChip(selected = session == null && day == date, onClick = { day = date; session = null }, label = { Text(date) },
                                        leadingIcon = if (session == null && day == date) {
                                            { Icon(Icons.Rounded.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                                        } else null)
                                }
                            }
                            if (view.history.sessions.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                items(view.history.sessions, key = { it.id }) { trip ->
                                    FilterChip(selected = session == trip.id, onClick = { session = trip.id },
                                        leadingIcon = { Icon(if (session == trip.id) Icons.Rounded.Check else Icons.Rounded.Flag, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
                                        label = { Text(stamp(trip.started) + if (trip.ended == null) "\u2003" + stringResource(R.string.footprints_active_trip) else "") })
                                }
                            }
                        }
                    },
                )
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
