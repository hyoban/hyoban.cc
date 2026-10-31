package cc.hyoban.moments.footprints

import androidx.compose.runtime.produceState
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.TextButton
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import cc.hyoban.moments.ui.design.*
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
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
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.SAME_PLACE_METRES
import cc.hyoban.moments.data.local.nearestPlaces
import java.time.Instant
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class TrackView(
    val state: RecordingState = RecordingState(),
    val history: FootprintHistory = FootprintHistory(emptyList(), emptyList()),
    val points: List<TrackPoint> = emptyList(),
    val stats: TrackStats = TrackStats(0.0, 0),
    val parts: List<TrackPart> = emptyList(),
    val drawing: FootprintDrawing = FootprintDrawing(),
)

/** Controls and selection live with the map, even while the bottom panel is closed. */
data class FootprintsMapState(
    val drawing: FootprintDrawing,
    /** The part chosen in the panel to frame, or empty for all of it. */
    val frame: List<TrackPoint>,
    val frameRequested: Boolean,
    val onFramed: () -> Unit,
    val open: () -> Unit,
)

@Composable
fun FootprintsOnMap(container: AppContainer, onSettings: () -> Unit, content: @Composable (FootprintsMapState) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The sheet is a window over every page, so it closes for the settings page and opens again on the way back.
    var reopen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (reopen) { reopen = false; expanded = true } }
    var showTracks by rememberSaveable { mutableStateOf(true) }
    var frameRequested by rememberSaveable { mutableStateOf(false) }
    var frame by remember { mutableStateOf(emptyList<TrackPoint>()) }
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
    var imported by remember { mutableStateOf<TrackImportResult?>(null) }
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
                val parts = trackParts(points, if (points.isEmpty()) emptyList() else store.activities(points.first().time - ACTIVITY_LOOKBACK_MS, points.last().time))
                TrackView(store.state(), store.history(), points, trackStats(points), parts, footprintDrawing(points, parts))
            }
            loaded = true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            error = R.string.footprints_error
        }
    }
    // A stay's name, by its start, from the phone's own places nearby; no request leaves for it.
    val places by container.repository.places.collectAsStateWithLifecycle()
    val stayNames = remember(view.parts, places) {
        view.parts.filterIsInstance<TrackStay>().mapNotNull { stay ->
            nearestPlaces(places, Fix(stay.latitude, stay.longitude), limit = 1).firstOrNull()
                ?.takeIf { (_, metres) -> metres <= SAME_PLACE_METRES }?.let { (id, _) -> stay.start to places.getValue(id).name }
        }.toMap()
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
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            imported = null
            val result = withContext(Dispatchers.IO) {
                val points = context.contentResolver.openInputStream(uri)?.use {
                    // Not a GPX at all reads as one with nothing in it.
                    try { readGpx(it) } catch (_: org.xml.sax.SAXException) { emptyList() }
                } ?: error("Cannot open document")
                store.import(points)
            }
            imported = result
            // Shows the first day it added.
            result.firstDay?.let { day = it; session = null }
        }
    }
    val state = view.state
    content(FootprintsMapState(
        drawing = if (showTracks) view.drawing else FootprintDrawing(),
        frame = frame,
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
                    Text(stringResource(R.string.footprints_title), Modifier.weight(1f).semantics { heading() }, style = AppTheme.typography.title)
                    IconButton(onClick = { expanded = false; reopen = true; onSettings() }, modifier = Modifier.testTag("footprint_settings")) {
                        Icon(AppIcons.Settings, contentDescription = stringResource(R.string.footprint_settings_title))
                    }
                }
            }
            item {
                SegmentedGroup(
                    stringResource(R.string.footprints_section_recording),
                    error?.let { stringResource(it) } ?: stringResource(R.string.footprints_local_note),
                    listOfNotNull(
                        { shapes ->
                            SwitchRow(state.daily, AppIcons.Refresh, stringResource(R.string.footprints_daily), "footprints_daily", shapes,
                                supporting = stringResource(R.string.footprints_daily_note), enabled = loaded && !busy) {
                                if (!state.daily) enable("daily_on") else control("daily_off")
                            }
                        },
                        { shapes ->
                            SwitchRow(state.session != null, AppIcons.Flag, stringResource(R.string.footprints_trip), "footprints_trip", shapes,
                                supporting = stringResource(if (state.session != null) R.string.footprints_trip_note else R.string.footprints_trip_off_note),
                                enabled = loaded && !busy) {
                                if (state.session == null) enable("start_trip") else control("end_trip")
                            }
                        },
                        if (state.requested) { shapes ->
                            ActionRow(
                                if (resumable) AppIcons.Play else AppIcons.Pause,
                                stringResource(if (resumable) R.string.footprints_resume else R.string.footprints_pause),
                                shapes,
                                supporting = stringResource(if (state.paused) R.string.footprints_paused else status ?: R.string.footprints_interrupted),
                                tag = "footprints_pause",
                                enabled = !busy,
                            ) { if (resumable) enable("resume") else control("pause") }
                        } else null,
                        { shapes ->
                            SwitchRow(showTracks, AppIcons.Layers, stringResource(R.string.footprints_show), "footprints_layer", shapes) {
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
                    imported?.let {
                        if (it.added + it.overlapping + it.invalid == 0) stringResource(R.string.footprints_import_empty)
                        else stringResource(R.string.footprints_import_done, it.added, it.overlapping, it.invalid)
                    } ?: stringResource(R.string.footprints_quality_note),
                    listOf(
                        { shapes ->
                            // What is chosen, and a tap shows the whole of it on the map.
                            SegmentedListItem(
                                onClick = { showTracks = true; frame = emptyList(); frameRequested = true; expanded = false },
                                shapes = shapes,
                                enabled = view.points.isNotEmpty(),
                                colors = segmentColors(),
                                leadingContent = { Icon(if (selectedTrip == null) AppIcons.Calendar else AppIcons.Flag, contentDescription = null) },
                                supportingContent = {
                                    Text(if (view.points.isEmpty()) stringResource(R.string.footprints_empty) else listOfNotNull(
                                        stringResource(R.string.footprints_stats, view.stats.metres / 1000, pluralStringResource(R.plurals.footprints_points, view.points.size, view.points.size), view.stats.recordedMillis / 60_000),
                                        view.points.lastOrNull()?.let { stringResource(R.string.footprints_last_fix, stamp(it.time)) },
                                    ).joinToString("\n"))
                                },
                                trailingContent = if (view.points.isNotEmpty()) {
                                    { Icon(AppIcons.Fit, contentDescription = stringResource(R.string.footprints_frame)) }
                                } else null,
                                modifier = Modifier.testTag("footprints_frame"),
                            ) {
                                Text(if (selectedTrip == null) day else stamp(selectedTrip.started) + " – " + (selectedTrip.ended?.let(::stamp) ?: stringResource(R.string.footprints_active_trip)))
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Download, stringResource(R.string.footprints_export), shapes,
                                enabled = loaded && !busy && view.points.isNotEmpty()) {
                                exportDay = selection.day
                                exportSession = selection.session
                                exportRaw = false
                                export.launch("footprints-${session ?: day}.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Download, stringResource(R.string.footprints_export_all), shapes,
                                enabled = loaded && !busy && view.history.days.isNotEmpty()) {
                                exportDay = null
                                exportSession = null
                                exportRaw = false
                                export.launch("footprints-all.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.FileOpen, stringResource(R.string.footprints_import), shapes,
                                supporting = stringResource(R.string.footprints_import_note),
                                tag = "footprints_import", enabled = loaded && !busy) {
                                // Files apps call a .gpx by several types, or none.
                                import.launch(arrayOf("*/*"))
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Download, stringResource(R.string.footprints_export_raw), shapes,
                                supporting = stringResource(R.string.footprints_export_raw_note),
                                enabled = loaded && !busy && (selection.day in view.history.days || session != null)) {
                                exportDay = selection.day
                                exportSession = selection.session
                                exportRaw = true
                                export.launch("footprints-raw-${session ?: day}.gpx")
                            }
                        },
                        { shapes ->
                            // Every sample in one file: what 导入 GPX 轨迹 needs to bring all of it back.
                            ActionRow(AppIcons.Download, stringResource(R.string.footprints_export_all_raw), shapes,
                                supporting = stringResource(R.string.footprints_export_all_raw_note),
                                tag = "footprints_export_all_raw", enabled = loaded && !busy && view.history.days.isNotEmpty()) {
                                exportDay = null
                                exportSession = null
                                exportRaw = true
                                export.launch("footprints-raw-all.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Trash, stringResource(R.string.footprints_delete), shapes,
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
                                            { Icon(AppIcons.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                                        } else null)
                                }
                            }
                            if (view.history.sessions.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                items(view.history.sessions, key = { it.id }) { trip ->
                                    FilterChip(selected = session == trip.id, onClick = { session = trip.id },
                                        leadingIcon = { Icon(if (session == trip.id) AppIcons.Check else AppIcons.Flag, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
                                        label = { Text(stamp(trip.started) + if (trip.ended == null) "\u2003" + stringResource(R.string.footprints_active_trip) else "") })
                                }
                            }
                        }
                    },
                )
            }
            if (view.parts.isNotEmpty()) item {
                // The day or trip told as moves and stays; a tap frames that part on the map.
                val days = view.points.map { day(it.time) }.distinct().size > 1
                SegmentedGroup(
                    stringResource(R.string.footprints_section_parts),
                    stringResource(R.string.footprints_parts_note),
                    view.parts.map<TrackPart, cc.hyoban.moments.ui.settings.Segment> { part ->
                        { shapes ->
                            val span = clock(part.start, days) + "–" + clock(part.end, days)
                            val (icon, title, supporting) = when (part) {
                                is TrackStay -> Triple(AppIcons.Pin,
                                    stringResource(R.string.footprints_stay) + "\u2003" + duration(part.end - part.start),
                                    listOfNotNull(span, stayNames[part.start]).joinToString("\u2003"))
                                is TrackMove -> Triple(
                                    when (part.mode) { TravelMode.WALK -> AppIcons.Walk; TravelMode.CYCLE -> AppIcons.Bike; TravelMode.DRIVE -> AppIcons.Car },
                                    stringResource(when (part.mode) {
                                        TravelMode.WALK -> R.string.footprints_mode_walk
                                        TravelMode.CYCLE -> R.string.footprints_mode_cycle
                                        TravelMode.DRIVE -> R.string.footprints_mode_drive
                                    }) + "\u2003" + stringResource(R.string.footprints_part_km, part.metres / 1000),
                                    listOfNotNull(span, part.averageSpeed?.let { average ->
                                        val top = part.topSpeed
                                        if (top != null) stringResource(R.string.footprints_speeds, kmh(average), kmh(top))
                                        else stringResource(R.string.footprints_speed_average, kmh(average))
                                    }).joinToString("\n"))
                            }
                            SegmentedListItem(
                                onClick = { showTracks = true; frame = part.points; frameRequested = true; expanded = false },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(icon, contentDescription = null) },
                                supportingContent = { Text(supporting) },
                                trailingContent = { Icon(AppIcons.Fit, contentDescription = stringResource(R.string.footprints_frame_part)) },
                                modifier = Modifier.testTag("footprints_part_${part.start}"),
                            ) { Text(title) }
                        }
                    },
                )
            }
            item {
                // The spans made public: private by default, each taken back by its switch; the
                // day or trip chosen above can be made one.
                val revision by store.revision.collectAsStateWithLifecycle()
                val shares by produceState(emptyList<TrackShare>(), revision) {
                    value = withContext(Dispatchers.IO) { store.shares().filter { !it.removed } }
                }
                val selectedTrip = view.history.sessions.firstOrNull { it.id == session }
                val zone = ZoneId.systemDefault()
                val span = when {
                    selectedTrip != null -> selectedTrip.started to (selectedTrip.ended ?: System.currentTimeMillis())
                    else -> LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli() to
                        LocalDate.parse(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                }
                SegmentedGroup(
                    stringResource(R.string.footprints_public_spans),
                    stringResource(R.string.items_tracks_note),
                    shares.map<TrackShare, cc.hyoban.moments.ui.settings.Segment> { share ->
                        { shapes ->
                            SwitchRow(true, AppIcons.Eye, stamp(share.start) + " – " + stamp(share.end), "share_${share.uid}", shapes,
                                supporting = stringResource(if (share.collection != null) R.string.footprints_span_trip else R.string.footprints_span_manual)) {
                                work { withContext(Dispatchers.IO) { store.removeShare(share.uid) } }
                            }
                        }
                    } + listOf<cc.hyoban.moments.ui.settings.Segment> { shapes ->
                        ActionRow(AppIcons.Plus, stringResource(R.string.footprints_make_public), shapes,
                            supporting = stamp(span.first) + " – " + stamp(span.second),
                            tag = "footprints_make_public",
                            enabled = loaded && !busy && view.points.isNotEmpty() && shares.none { it.start == span.first && it.end == span.second }) {
                            work {
                                withContext(Dispatchers.IO) {
                                    store.saveShare(TrackShare(cc.hyoban.moments.data.local.Ulid.next(), span.first, span.second))
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

/** An activity entered before the first point may still say how it went. */
private const val ACTIVITY_LOOKBACK_MS = 30 * 60_000L

private fun day(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()

/** A part's time of day, with its date where the selection spans more than one day. */
private fun clock(time: Long, withDate: Boolean): String =
    Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (withDate) "MM-dd HH:mm" else "HH:mm"))

private fun kmh(metresPerSecond: Double) = (metresPerSecond * 3.6).roundToInt()

@Composable
private fun duration(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    return if (minutes < 60) stringResource(R.string.footprints_minutes, minutes)
    else stringResource(R.string.footprints_hours_minutes, minutes / 60, minutes % 60)
}

private fun stamp(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
