package cc.hyoban.moments.footprints

import cc.hyoban.moments.plugins.footprints
import cc.hyoban.moments.ui.settings.ActionRow

import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module

import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.TextButton
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
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Services
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.components.testTagsAsResources
import kotlinx.coroutines.flow.MutableStateFlow
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.*
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.recordTime
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.entryUid
import cc.hyoban.moments.ui.compose.KeepPlace
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    val deletable: Boolean = false,
)

/** Controls and selection live with the map, even while the bottom panel is closed. */
data class FootprintsMapState(
    val drawing: FootprintDrawing,
    /** The part chosen in the panel to frame, or empty for all of it. */
    val frame: List<TrackPoint>,
    val frameRequested: Boolean,
    val onFramed: () -> Unit,
    val open: () -> Unit,
    /** Whether the device is recording now, which the map's footprints button shows. */
    val recording: Boolean = false,
    /** A stay on the map tapped, by its start. */
    val openStay: (Long) -> Unit = {},
    /** The span of the part chosen in the panel, which stands out of the rest of its day. */
    val focus: LongRange? = null,
    /** The footprints are being looked at, from their panel: the rest of the map steps back. */
    val looking: Boolean = false,
    /** Done looking at them, the map's own things tapped again. */
    val stopLooking: () -> Unit = {},
    /** The days with footprints, and the trips recorded by session, said as when each began, for the 足迹 layer's time. */
    val days: Set<LocalDate> = emptySet(),
    val trips: List<Pair<String, String>> = emptyList(),
)

/**
 * The footprints over the map and their panel: the chosen time's track, told as stays and moves.
 * Where the device records them ([FootprintRecording], the phone's), the panel also turns the
 * recording and exports or deletes what it keeps; elsewhere (the Mac, from the phone's backup) it
 * only shows them.
 */
@Composable
fun FootprintsOnMap(
    container: AppContainer,
    onSettings: () -> Unit,
    onWriteAtStay: (cc.hyoban.moments.ui.StayAt) -> Unit,
    onCheckin: (String) -> Unit,
    content: @Composable (FootprintsMapState) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The sheet is a window over every page, so it closes for the settings page and opens again on the way back.
    var reopen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (reopen) { reopen = false; expanded = true } }
    // Whether the map draws them: the layers menu's 足迹, which looking at a day or a part turns on.
    val settings = LocalSettings.current
    val showTracks = settings.mapFootprints
    var frameRequested by rememberSaveable { mutableStateOf(false) }
    var frame by remember { mutableStateOf(emptyList<TrackPoint>()) }
    // The part chosen, by its start and end; and whether the map is still on the footprints after
    // the panel framed them, until the map's own places or trips are tapped.
    var focus by remember { mutableStateOf<LongRange?>(null) }
    var framed by rememberSaveable { mutableStateOf(false) }
    val device = LocalDevice.current
    val scope = rememberCoroutineScope()
    val store = container.footprints
    val recording = Plugins.registry.service(Services.footprintRecording, container, settings::isOn)
    val revision by store.revision.collectAsStateWithLifecycle()
    val status by (recording?.status ?: NoStatus).collectAsStateWithLifecycle()
    // What they show is the 足迹 layer's time, chosen in the map's layers menu with every other layer's.
    val times = remember(settings) { cc.hyoban.moments.ui.map.MapTimes(settings) }
    val time = times.of(cc.hyoban.moments.ui.map.TimedLayer.TRACK)
    val selection = remember(time, LocalDate.now()) { cc.hyoban.moments.ui.map.footprintSelection(time) }
    val day = selection.day
    val session = selection.session
    LaunchedEffect(selection) { focus = null }
    var view by remember { mutableStateOf(TrackView()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var deleting by remember { mutableStateOf<TrackSelection?>(null) }

    fun work(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                error = Res.string.footprints_error
            } finally { busy = false }
        }
    }
    fun control(action: String) = work {
        val recording = recording ?: return@work
        recording.control(action)
        if (action == "start_trip") withContext(Dispatchers.IO) { store.state().session }?.let { times.setOwn(cc.hyoban.moments.ui.map.TimedLayer.TRACK, cc.hyoban.moments.ui.map.MapTime.Trip(it)) }
    }

    LaunchedEffect(revision, selection) {
        try {
            view = withContext(Dispatchers.IO) {
                val state = store.state()
                val (read, unconfirmed) = store.pointsWithTail(selection)
                // A long time is drawn from every so many points, as the map can draw only so many.
                val points = if (read.size <= MAX_DRAWN) read else (read.indices step (read.size / MAX_DRAWN + 1)).map { read[it] } + read.last()
                // Only what is being recorded now has an unconfirmed end: today's, the running trip's, or a time running to now.
                val now = System.currentTimeMillis()
                val live = state.recording && when {
                    session != null -> session == state.session
                    day != null -> day == LocalDate.now().toString()
                    else -> (selection.to ?: Long.MAX_VALUE) >= now && (selection.from ?: 0L) <= now
                }
                val tail = unconfirmed.takeIf { live && it.isNotEmpty() && System.currentTimeMillis() - it.last().time <= TRACK_GAP_MS }.orEmpty()
                val parts = trackParts(points, if (points.isEmpty()) emptyList() else store.activities(points.first().time - ACTIVITY_LOOKBACK_MS, points.last().time))
                TrackView(state, store.history(), points, trackStats(points), parts, footprintDrawing(points, parts, tail), recording?.deletable(selection) == true)
            }
            loaded = true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            error = Res.string.footprints_error
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
    // The check-in over each stay, by its start, kept from it or Swarm's: that stay is kept already.
    val checkinsOn = LocalSettings.current.isOn(Module.CHECKIN)
    val checkins by container.repository.checkins.collectAsStateWithLifecycle()
    val keptStays = remember(view.parts, checkins, checkinsOn) {
        if (!checkinsOn) emptyMap()
        else view.parts.filterIsInstance<TrackStay>().mapNotNull { stay -> checkins.overlapping(stay.start, stay.end).firstOrNull()?.let { stay.start to it } }.toMap()
    }
    // A stay kept as a place or a check-in there: the picker opens at once and names it after
    // what is around it, so a slow or stalled lookup never leaves the tap without an answer.
    var keeping by remember { mutableStateOf<TrackStay?>(null) }
    keeping?.let { stay ->
        val keepCheckin: suspend (String, String) -> Unit = { place, venue ->
            val entry = stayCheckin(stay, place, venue)
            // A check-in already there over the stay, Swarm's or one kept before, is that visit,
            // whichever day it started on.
            val other = container.repository.checkins.value.overlapping(stay.start, stay.end).any { it.uid != entry.entryUid }
            if (other) device.toast(textOf(Res.string.message_checkin_known))
            else {
                container.repository.saveEntry(entry)
                // The bills paid while there, with no place, may take this one.
                container.offerPlaceToBills(place, stay.start, stay.end)
            }
        }
        // Kept as a check-in only while check-ins are on (记录项与公开).
        KeepPlace(container, Fix(stay.latitude, stay.longitude), "", onDismiss = { keeping = null }, alternative = keepCheckin.takeIf { LocalSettings.current.isOn(Module.CHECKIN) }?.let { org.jetbrains.compose.resources.stringResource(Res.string.action_save_checkin) to it })
    }
    // Recording asks first for what it needs; exporting saves to a file the owner names. Both are the recording device's.
    val enable = recording?.rememberEnable(
        onGranted = ::control,
        onDeclined = {},
        onDenied = { error = Res.string.footprints_permission_needed },
    ) ?: {}
    val export = recording?.rememberExport(::work) ?: { _, _, _ -> }
    val state = view.state
    content(FootprintsMapState(
        drawing = if (showTracks) view.drawing else FootprintDrawing(),
        frame = frame,
        frameRequested = frameRequested,
        onFramed = { frameRequested = false },
        open = { expanded = true },
        recording = state.recording,
        focus = focus.takeIf { showTracks },
        looking = showTracks && view.points.isNotEmpty() && (expanded || framed),
        days = remember(view.history.days) { view.history.days.mapNotNullTo(HashSet()) { runCatching { LocalDate.parse(it) }.getOrNull() } },
        trips = view.history.sessions.map { it.id to stamp(it.started) },
        stopLooking = { framed = false; focus = null },
        // A stay tapped is written at, a Moment or its check-in; one kept already opens that check-in.
        openStay = { start ->
            val stay = view.parts.firstOrNull { it is TrackStay && it.start == start } as? TrackStay
            val kept = keptStays[start]
            when {
                kept != null -> onCheckin(kept.uid)
                stay != null -> onWriteAtStay(cc.hyoban.moments.ui.StayAt(stay.start, stay.end, stay.latitude, stay.longitude))
            }
        },
    ))
    // Paused, never started or stopped by an error, the row offers to resume; otherwise to pause.
    val resumable = state.paused || status == null || status == Res.string.footprints_permission_needed || status == Res.string.footprints_error
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        containerColor = AppColors.sheet,
        modifier = Modifier.testTagsAsResources().testTag("footprints_panel"),
    ) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = Space.xl)) {
            // Its name, and the way to how it records, as the place sheet has its name and the way there.
            item {
                Row(Modifier.fillMaxWidth().padding(start = Space.gutter + Space.l, end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.footprints_title), Modifier.weight(1f).semantics { heading() }, style = AppTheme.typography.title)
                    // How it records is the recording device's to set.
                    if (recording != null) IconButton(onClick = { expanded = false; reopen = true; onSettings() }, modifier = Modifier.testTag("footprint_settings")) {
                        Icon(AppIcons.Settings, contentDescription = stringResource(Res.string.footprint_settings_title))
                    }
                }
            }
            if (recording != null) item {
                cc.hyoban.moments.ui.settings.SettingsGroupView(cc.hyoban.moments.ui.settings.SettingsGroupSpec(
                    stringResource(Res.string.footprints_section_recording),
                    error?.let { stringResource(it) } ?: stringResource(Res.string.footprints_local_note),
                    listOfNotNull(
                        cc.hyoban.moments.ui.settings.SettingsRow.Switch(AppIcons.Refresh, stringResource(Res.string.footprints_daily), "footprints_daily", state.daily,
                            supporting = stringResource(Res.string.footprints_daily_note), enabled = loaded && !busy) {
                            if (!state.daily) enable("daily_on") else control("daily_off")
                        },
                        cc.hyoban.moments.ui.settings.SettingsRow.Switch(AppIcons.Flag, stringResource(Res.string.footprints_trip), "footprints_trip", state.session != null,
                            supporting = stringResource(if (state.session != null) Res.string.footprints_trip_note else Res.string.footprints_trip_off_note),
                            enabled = loaded && !busy) {
                            if (state.session == null) enable("start_trip") else control("end_trip")
                        },
                        if (state.requested) cc.hyoban.moments.ui.settings.SettingsRow.Action(
                            if (resumable) AppIcons.Play else AppIcons.Pause,
                            stringResource(if (resumable) Res.string.footprints_resume else Res.string.footprints_pause),
                            "footprints_pause",
                            supporting = stringResource(if (state.paused) Res.string.footprints_paused else status ?: Res.string.footprints_interrupted),
                            enabled = !busy,
                        ) { if (resumable) enable("resume") else control("pause") } else null,
                    ),
                ))
            }
            item {
                val selectedTrip = view.history.sessions.firstOrNull { it.id == session }
                SegmentedGroup(
                    stringResource(Res.string.footprints_section_tracks),
                    stringResource(Res.string.footprints_quality_note),
                    listOf<cc.hyoban.moments.ui.settings.Segment>(
                        { shapes ->
                            // What is chosen, and a tap shows the whole of it on the map.
                            SegmentedListItem(
                                onClick = { settings.changeMapFootprints(true); frame = emptyList(); focus = null; framed = true; frameRequested = true; expanded = false },
                                shapes = shapes,
                                enabled = view.points.isNotEmpty(),
                                colors = segmentColors(),
                                leadingContent = { Icon(if (selectedTrip == null) AppIcons.Calendar else AppIcons.Flag, contentDescription = null) },
                                supportingContent = {
                                    Text(if (view.points.isEmpty()) stringResource(Res.string.footprints_empty) else listOfNotNull(
                                        stringResource(Res.string.footprints_stats, decimals(view.stats.metres / 1000, 2), pluralStringResource(Res.plurals.footprints_points, view.points.size, view.points.size), view.stats.recordedMillis / 60_000),
                                        view.points.lastOrNull()?.let { stringResource(Res.string.footprints_last_fix, stamp(it.time)) },
                                    ).joinToString("\n"))
                                },
                                trailingContent = if (view.points.isNotEmpty()) {
                                    { Icon(AppIcons.Fit, contentDescription = stringResource(Res.string.footprints_frame)) }
                                } else null,
                                modifier = Modifier.testTag("footprints_frame"),
                            ) {
                                Text(if (selectedTrip == null) cc.hyoban.moments.ui.map.timeWords(time) else stamp(selectedTrip.started) + " – " + (selectedTrip.ended?.let(::stamp) ?: stringResource(Res.string.footprints_active_trip)))
                            }
                        },
                    ) + if (recording == null) emptyList() else listOf<cc.hyoban.moments.ui.settings.Segment>(
                        { shapes ->
                            ActionRow(AppIcons.Download, stringResource(Res.string.footprints_export), shapes,
                                enabled = loaded && !busy && view.points.isNotEmpty()) {
                                export(TrackSelection(selection.day, selection.session), false, "footprints-${session ?: day ?: time.encode().replace(':', '-')}.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Download, stringResource(Res.string.footprints_export_all), shapes,
                                enabled = loaded && !busy && view.history.days.isNotEmpty()) {
                                export(TrackSelection(), false, "footprints-all.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Download, stringResource(Res.string.footprints_export_raw), shapes,
                                supporting = stringResource(Res.string.footprints_export_raw_note),
                                enabled = loaded && !busy && (selection.day in view.history.days || session != null)) {
                                export(TrackSelection(selection.day, selection.session), true, "footprints-raw-${session ?: day ?: time.encode().replace(':', '-')}.gpx")
                            }
                        },
                        { shapes ->
                            // Every sample in one file: what 导入's GPX row needs to bring all of it back.
                            ActionRow(AppIcons.Download, stringResource(Res.string.footprints_export_all_raw), shapes,
                                supporting = stringResource(Res.string.footprints_export_all_raw_note),
                                tag = "footprints_export_all_raw", enabled = loaded && !busy && view.history.days.isNotEmpty()) {
                                export(TrackSelection(), true, "footprints-raw-all.gpx")
                            }
                        },
                        { shapes ->
                            ActionRow(AppIcons.Trash, stringResource(Res.string.footprints_delete), shapes,
                                supporting = stringResource(Res.string.footprints_delete_note),
                                enabled = loaded && !busy && view.deletable && (selection.day in view.history.days || session != null)) {
                                deleting = selection
                            }
                        },
                    ),
                )
            }
            if (view.parts.isNotEmpty()) item {
                // The day or trip told as moves and stays; a tap frames that part on the map.
                val days = view.points.map { day(it.time) }.distinct().size > 1
                SegmentedGroup(
                    stringResource(Res.string.footprints_section_parts),
                    null,
                    view.parts.map<TrackPart, cc.hyoban.moments.ui.settings.Segment> { part ->
                        { shapes ->
                            val span = clock(part.start, days) + "–" + clock(part.end, days)
                            val (icon, title, supporting) = when (part) {
                                is TrackStay -> Triple(AppIcons.Pin,
                                    stringResource(Res.string.footprints_stay) + " " + duration(part.end - part.start),
                                    listOfNotNull(span, keptStays[part.start]?.venue ?: stayNames[part.start]).joinToString(" "))
                                is TrackMove -> Triple(
                                    when (part.mode) { TravelMode.WALK -> AppIcons.Walk; TravelMode.CYCLE -> AppIcons.Bike; TravelMode.DRIVE -> AppIcons.Car },
                                    stringResource(when (part.mode) {
                                        TravelMode.WALK -> Res.string.footprints_mode_walk
                                        TravelMode.CYCLE -> Res.string.footprints_mode_cycle
                                        TravelMode.DRIVE -> Res.string.footprints_mode_drive
                                    }) + " " + stringResource(Res.string.footprints_part_km, decimals(part.metres / 1000, 1)),
                                    listOfNotNull(span, part.averageSpeed?.let { average ->
                                        val top = part.topSpeed
                                        if (top != null) stringResource(Res.string.footprints_speeds, kmh(average), kmh(top))
                                        else stringResource(Res.string.footprints_speed_average, kmh(average))
                                    }).joinToString("\n"))
                            }
                            SegmentedListItem(
                                onClick = { settings.changeMapFootprints(true); frame = part.points; focus = part.start..part.end; framed = true; frameRequested = true; expanded = false },
                                shapes = shapes,
                                colors = segmentColors(),
                                leadingContent = { Icon(icon, contentDescription = null) },
                                supportingContent = { Text(supporting) },
                                trailingContent = {
                                    if (part is TrackStay && part.start in keptStays) {
                                        Icon(AppIcons.Flag, contentDescription = stringResource(Res.string.footprints_stay_kept), tint = tokens.tx3,
                                            modifier = Modifier.minimumInteractiveComponentSize().testTag("footprints_stay_kept_${part.start}"))
                                    } else if (part is TrackStay) {
                                        IconButton(onClick = { keeping = part }, modifier = Modifier.testTag("footprints_save_place_${part.start}")) {
                                            Icon(AppIcons.Plus, contentDescription = stringResource(Res.string.share_save_place))
                                        }
                                    } else {
                                        Icon(AppIcons.Fit, contentDescription = stringResource(Res.string.footprints_frame_part))
                                    }
                                },
                                modifier = Modifier.testTag("footprints_part_${part.start}"),
                            ) { Text(title) }
                        }
                    },
                )
            }
        }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.footprints_delete)) },
            text = { Text(stringResource(Res.string.footprints_delete_confirm)) },
            confirmButton = { TextButton(onClick = {
                deleting = null
                work {
                    withContext(Dispatchers.IO) { recording?.delete(target) }
                    if (target.session != null) times.reset(cc.hyoban.moments.ui.map.TimedLayer.TRACK)
                }
            }) { Text(stringResource(Res.string.footprints_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.footprints_cancel)) } },
        )
    }
}

/** No recording here to say anything of itself. */
private val NoStatus = MutableStateFlow<StringResource?>(null)

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
    return if (minutes < 60) stringResource(Res.string.footprints_minutes, minutes)
    else stringResource(Res.string.footprints_hours_minutes, minutes / 60, minutes % 60)
}

/** At most how many points of a time the map draws. */
private const val MAX_DRAWN = 20_000

private fun stamp(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** When the footprints say, as the records write it: to the second, in the phone's offset then. */
private fun recordStamp(time: Long): String = cc.hyoban.moments.data.local.stamp(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toOffsetDateTime())

/**
 * A stay kept as a check-in at [place], called [venue], public until its sheet says otherwise:
 * from its start to its end, at its middle as the footprints have it. Its uid is made from its start, so keeping it again
 * changes that one rather than adding another.
 */
private fun stayCheckin(stay: TrackStay, place: String, venue: String): JsonObject {
    val at = recordStamp(stay.start)
    val now = recordTime()
    return buildJsonObject {
        put("uid", Entries.stableUlid(at, "stay:${stay.start}"))
        put("at", at)
        put("end", recordStamp(stay.end))
        put("point", buildJsonObject {
            put("lat", stay.latitude)
            put("lng", stay.longitude)
            put("from", "track")
        })
        put("place", place)
        put("parts", buildJsonObject {
            put("checkin", buildJsonObject {
                put("v", 1)
                put("venue", venue)
                put("source", buildJsonObject { put("app", "phone") })
            })
        })
        put("createdAt", now)
        put("updatedAt", now)
    }
}
