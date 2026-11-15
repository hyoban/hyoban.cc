package cc.hyoban.moments.ui.trips

import cc.hyoban.moments.ui.components.BackHandler
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.design.groupedRow
import cc.hyoban.moments.ui.compose.icon
import cc.hyoban.moments.res.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.components.railPadding
import cc.hyoban.moments.ui.components.isPhoneLandscape
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DateTimeDialog
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.pluralStringResource
import java.time.temporal.ChronoUnit
import cc.hyoban.moments.ui.theme.Space
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 编辑旅程: its name, the days it spans (its Moments are those days', worked out, not listed),
 * and whether it is public. Kept on the phone like any change until 全部发布到网站; the page's
 * one ink button saves it. With no [id] it makes a trip, named by hand and from today to today
 * until changed, or from [startDay] to [endDay] and named [name] when it comes from a 可能的旅程, and
 * [onMade] opens it; one not yet published can be deleted from here, [onDeleted] then leaving its
 * page too.
 */
@Composable
fun TripEditScreen(
    container: AppContainer,
    id: String?,
    onBack: () -> Unit,
    onMade: (String) -> Unit = {},
    onDeleted: () -> Unit = onBack,
    startDay: String? = null,
    endDay: String? = null,
    name: String? = null,
    /** The records a new trip from a 可能的旅程 runs between, leaving and coming back. */
    fromRecord: String? = null,
    toRecord: String? = null,
) {
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { id?.let { trips.firstOrNull { it.id == id } } }
    if (id != null && trip == null) return
    val today = remember { LocalDate.now().toString() }
    var title by rememberSaveable { mutableStateOf(trip?.title ?: name.orEmpty()) }
    // Each end a day, or a time when known to the minute.
    var start by rememberSaveable { mutableStateOf(trip?.startAt ?: trip?.start ?: startDay ?: today) }
    var end by rememberSaveable { mutableStateOf(trip?.endAt ?: trip?.end ?: endDay ?: today) }
    var private by rememberSaveable { mutableStateOf(id != null && container.repository.isTripPrivate(id)) }
    // Whether its footprints stay off the site, as 私有 says of the trip; set on saving, like the rest.
    val connected = LocalSettings.current.connected
    val footprintsOn = LocalSettings.current.isOn(cc.hyoban.moments.data.spec.Module.FOOTPRINTS)
    val trackShared = trip?.let { rememberTrackShared(container, it) }
    var trackPrivate by rememberSaveable { mutableStateOf<Boolean?>(null) }
    if (trackPrivate == null && trackShared != null) trackPrivate = !trackShared
    val showTrack = trip != null && connected && footprintsOn && !private && trackPrivate != null
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val unpublished = id != null && container.repository.isTripNew(id)
    val collection = remember(id) { id?.let { container.repository.tripCollection(it) } }
    // The record each end follows, if any: a check-in, a Moment, whatever was there.
    val bounds = remember(collection) { collection?.let(container.repository::boundsOf) }
    var from by rememberSaveable { mutableStateOf(bounds?.first ?: fromRecord) }
    var to by rememberSaveable { mutableStateOf(bounds?.second ?: toRecord) }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    val firstDay = start.take(10)
    val lastDay = end.take(10)
    // In its span: on its days, and not before it began nor after it ended where those are times.
    val inSpan = { at: String ->
        cc.hyoban.moments.data.trips.isInTrip(
            cc.hyoban.moments.data.spec.TripRecord("", "", firstDay, lastDay, startAt = start.takeIf { it.length > 10 }, endAt = end.takeIf { it.length > 10 }, createdAt = "", updatedAt = ""),
            at,
        )
    }
    var include by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.strings(it, "include") }.orEmpty()) }
    var exclude by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.strings(it, "exclude") }.orEmpty()) }
    var covers by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.coversOf(it) }.orEmpty().map { "${it.first}/${it.second}" }) }
    val all by container.shownMoments.collectAsStateWithLifecycle()
    // The Moments of its days and of two days either side, which can be taken in from outside.
    val nearby = remember(all, firstDay, lastDay) {
        val before = LocalDate.parse(firstDay).minusDays(2).toString()
        val after = LocalDate.parse(lastDay).plusDays(2).toString()
        all.filter { it.dateKey in before..after }.sortedBy { it.occurredAt }
    }
    val members = nearby.filter { moment ->
        val inside = inSpan(moment.occurredAt)
        (inside && moment.uid !in exclude) || (!inside && moment.uid in include)
    }
    val (inDays, around) = nearby.partition { inSpan(it.occurredAt) }
    // Its days' records, listed on a page of their own from the row that counts them.
    var listing by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = listing) { listing = false }
    // Choosing an end, start or end, on a page of its own: a time by hand, or any record.
    BackHandler(enabled = choosing != null) { choosing = null }
    val scope = rememberCoroutineScope()
    // Ends on its days in order, and, both to the minute on one day, the end after the start.
    val valid = title.isNotBlank() && firstDay <= lastDay &&
        (start.length <= 10 || end.length <= 10 || cc.hyoban.moments.data.trips.epochOf(start) <= cc.hyoban.moments.data.trips.epochOf(end))
    SubPage(
        when {
            choosing == "start" -> stringResource(Res.string.trip_edit_leaving)
            choosing == "end" -> stringResource(Res.string.trip_edit_back)
            listing -> stringResource(Res.string.trip_edit_in_days_title)
            id == null -> stringResource(Res.string.trip_new_title)
            else -> stringResource(Res.string.trip_edit_title)
        },
        { if (choosing != null) choosing = null else if (listing) listing = false else onBack() },
        Modifier.background(AppColors.page),
    ) {
        choosing?.let { which ->
            EndPicker(
                container,
                around = if (which == "start") start else end,
                chosen = if (which == "start") from else to,
                onTime = { picking = which },
            ) { uid, at ->
                if (which == "start") { from = uid; start = at } else { to = uid; end = at }
                choosing = null
            }
            return@SubPage
        }
        if (listing) {
            PageWidth(maxColumns = 1) { columns ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current)
                        .navigationBarsPadding().padding(horizontal = columns.margin).padding(bottom = Space.xl),
                ) {
                    SegmentedGroup(
                        null,
                        null,
                        inDays.map<cc.hyoban.moments.data.spec.MomentRecord, cc.hyoban.moments.ui.settings.Segment> { moment ->
                            { shapes ->
                                val on = moment.uid !in exclude
                                val supporting = listOfNotNull(formatDay(moment.dateKey), formatTime(moment.occurredAt)).joinToString(" ")
                                SwitchRow(on, AppIcons.Calendar, memberTitle(moment), "member_${moment.uid}", shapes, supporting = supporting) {
                                    exclude = if (on) exclude + moment.uid else exclude - moment.uid
                                }
                            }
                        },
                    )
                }
            }
            return@SubPage
        }
        val form: @Composable () -> Unit = {
            OutlinedTextField(
                title,
                { title = it },
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).testTag("trip_title"),
                label = { Text(stringResource(Res.string.trip_edit_name)) },
                singleLine = true,
            )
            // Each end a record, its time that record's and not shown, or a time set by hand.
            SegmentedGroup(
                stringResource(Res.string.trip_edit_bounds),
                null,
                listOf(
                    { shapes -> EndRow(container, from, start, stringResource(Res.string.trip_edit_leaving), shapes, "trip_start") { choosing = "start" } },
                    { shapes -> EndRow(container, to, end, stringResource(Res.string.trip_edit_back), shapes, "trip_end") { choosing = "end" } },
                ),
            )
        }
        val membersGroup: @Composable () -> Unit = {
            // Which records it holds: its days', in by themselves and counted in one row that
            // opens them, then each of the two days either side, out until added.
            if (inDays.isNotEmpty()) SegmentedGroup(
                stringResource(Res.string.trip_edit_members),
                null,
                listOf { shapes ->
                    val left = inDays.count { it.uid in exclude }
                    SegmentedListItem(
                        onClick = { listing = true },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Timeline, contentDescription = null) },
                        supportingContent = {
                            Text(if (left > 0) pluralStringResource(Res.plurals.trip_edit_left_out, left, left) else stringResource(Res.string.trip_edit_taken_in))
                        },
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("trip_members"),
                    ) { Text(pluralStringResource(Res.plurals.trip_edit_in_days, inDays.size, inDays.size)) }
                },
            )
            if (around.isNotEmpty()) SegmentedGroup(
                stringResource(Res.string.trip_edit_around),
                null,
                around.map<cc.hyoban.moments.data.spec.MomentRecord, cc.hyoban.moments.ui.settings.Segment> { moment ->
                    { shapes ->
                        val on = moment.uid in include
                        val toggle = { include = if (on) include - moment.uid else include + moment.uid }
                        val words = memberTitle(moment)
                        val date = LocalDate.parse(moment.dateKey)
                        val offset = if (date.isBefore(LocalDate.parse(firstDay))) -ChronoUnit.DAYS.between(date, LocalDate.parse(firstDay)) else ChronoUnit.DAYS.between(LocalDate.parse(lastDay), date).coerceAtLeast(1)
                        val side = stringResource(when (offset) { -2L -> Res.string.trip_edit_two_before; -1L -> Res.string.trip_edit_day_before; 1L -> Res.string.trip_edit_day_after; else -> Res.string.trip_edit_two_after })
                        SegmentedListItem(
                            onClick = toggle,
                            shapes = shapes,
                            colors = segmentColors(),
                            supportingContent = { Text(formatDay(moment.dateKey) + " " + side) },
                            trailingContent = {
                                IconButton(onClick = toggle, modifier = Modifier.testTag("member_${moment.uid}")) {
                                    if (on) {
                                        Icon(AppIcons.Check, contentDescription = stringResource(Res.string.trip_edit_leave_out, words), tint = tokens.accent)
                                    } else {
                                        Icon(AppIcons.Plus, contentDescription = stringResource(Res.string.trip_edit_take_in, words))
                                    }
                                }
                            },
                        ) { Text(words) }
                    }
                },
            )
        }
        val rest: @Composable () -> Unit = {
            // Its covers, chosen from its records' photos in the order tapped.
            val photos = members.flatMap { moment -> moment.media.filter { it.type != cc.hyoban.moments.data.spec.MediaType.VIDEO }.map { moment.uid to it } }
            if (photos.isNotEmpty()) {
                cc.hyoban.moments.ui.settings.Caption(stringResource(Res.string.trip_edit_covers))
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    items(photos.size) { index ->
                        val (uid, media) = photos[index]
                        val key = "$uid/${media.uid}"
                        val order = covers.indexOf(key)
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(88.dp)
                                .clip(AppTheme.shapes.medium)
                                .clickable { covers = if (order >= 0) covers - key else covers + key }
                                .testTag("cover_${media.uid}"),
                        ) {
                            cc.hyoban.moments.ui.components.Thumbnail(media, container.mediaUrls, size = 88)
                            if (order >= 0) {
                                Text(
                                    (order + 1).toString(),
                                    style = AppTheme.typography.caption,
                                    color = cc.hyoban.moments.ui.theme.tokens.onAccent,
                                    modifier = Modifier
                                        .padding(Space.xs)
                                        .background(cc.hyoban.moments.ui.theme.tokens.accent, cc.hyoban.moments.ui.theme.Corners.full)
                                        .padding(horizontal = Space.s, vertical = Space.xs),
                                )
                            }
                        }
                    }
                }
            }
            SegmentedGroup(
                null,
                // What reaches the site, said only with an API to publish to.
                stringResource(if (private) Res.string.moment_private_note else Res.string.trip_edit_public_note).takeIf { LocalSettings.current.connected },
                listOfNotNull<Segment>(
                    { shapes ->
                        SwitchRow(private, AppIcons.EyeOff, stringResource(Res.string.visibility_private), "trip_private", shapes) { private = !private }
                    },
                    if (showTrack) { shapes ->
                        SwitchRow(trackPrivate == true, AppIcons.Route, stringResource(Res.string.trip_track_private), "trip_track_private", shapes,
                            supporting = stringResource(Res.string.trip_track_private_note)) { trackPrivate = trackPrivate != true }
                    } else null,
                ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s, androidx.compose.ui.Alignment.End),
            ) {
                // Only one not yet published goes from here; the site's trips have no deleting yet.
                if (unpublished) {
                    TextButton(onClick = { deleting = true }, modifier = Modifier.testTag("trip_delete")) { Text(stringResource(Res.string.trip_delete)) }
                }
                Button(
                    enabled = valid,
                    onClick = {
                        scope.launch {
                            val visibility = if (private) Visibility.PRIVATE else Visibility.PUBLIC
                            val chosen = covers.map { it.substringBefore('/') to it.substringAfter('/') }
                            if (id == null) {
                                onMade(container.repository.createTrip(title.trim(), start, end, visibility, include, exclude, chosen, from, to))
                            } else {
                                container.repository.editTrip(id, title.trim(), start, end, visibility, include = include, exclude = exclude, covers = chosen, from = from, to = to)
                                // The track follows its switch, on the trip's days as saved.
                                val shared = !private && trackPrivate == false
                                if (trip != null && trackShared != null && shared != trackShared) {
                                    setTrackShared(
                                        container,
                                        trip.copy(start = firstDay, end = lastDay, startAt = start.takeIf { it.length > 10 }, endAt = end.takeIf { it.length > 10 }),
                                        shared,
                                    )
                                }
                                onBack()
                            }
                        }
                    },
                    modifier = Modifier.testTag("trip_save"),
                ) { Text(stringResource(Res.string.action_save)) }
            }
        }
        // On a phone on its side, its name, days, covers and saving at the start, the records it
        // holds in a list of their own beside them, each scrolling by itself (H13).
        if (isPhoneLandscape()) {
            Row(Modifier.fillMaxSize().railPadding().imePadding()) {
                Column(
                    Modifier.width(FORM_COLUMN).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current, bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    form()
                    rest()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = LocalTopClearance.current, bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) { membersGroup() }
            }
        } else {
            PageWidth(maxColumns = 1) { columns ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = LocalTopClearance.current)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = columns.margin)
                        .padding(bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    form()
                    membersGroup()
                    rest()
                }
            }
        }
    }
    if (deleting && id != null) {
        cc.hyoban.moments.ui.design.AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(Res.string.trip_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        container.repository.revertTrip(id)
                        onDeleted()
                    }
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    picking?.let { which ->
        val now = if (which == "start") start else end
        val time = now.takeIf { it.length > 10 }?.let { java.time.OffsetDateTime.parse(it) }
        DateTimeDialog(
            LocalDate.parse(now.take(10)),
            time?.hour ?: if (which == "start") 0 else 23,
            time?.minute ?: if (which == "start") 0 else 59,
            onDismiss = { picking = null },
        ) { picked, hour, minute ->
            // The day's very start or end is the whole day; any other time, to the minute.
            val whole = if (which == "start") hour == 0 && minute == 0 else hour == 23 && minute == 59
            val chosen = if (whole) picked.toString() else stampOf(picked, hour, minute)
            // Set by hand, that end follows no record any more.
            if (which == "start") { start = chosen; from = null } else { end = chosen; to = null }
            picking = null
            choosing = null
        }
    }
}

/** An end of a trip as written: its day, and its time when known to the minute. */
private fun whenOf(at: String): String = listOfNotNull(formatDay(at.take(10)), formatTime(at)).joinToString(" ")

/** A day and a time on it as the phone's clock has it, with its offset. */
private fun stampOf(day: LocalDate, hour: Int, minute: Int): String =
    day.atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime()
        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** A record a trip's end can follow: its uid, its time, what it is called, where, and its icon. */
private data class EndRecord(val uid: String, val at: String, val name: String, val place: String?, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/** Every Moment and check-in, as records an end can follow. */
@Composable
private fun endRecords(container: AppContainer): List<EndRecord> {
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val checkins by container.repository.checkins.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val photos = pluralStringResource(Res.plurals.count_photos, 1, 1)
    return remember(moments, checkins, places) {
        val kindOf = { place: String? -> place?.let(places::get)?.let { cc.hyoban.moments.data.spec.PlaceKind.of(it.kind) }?.icon }
        moments.map { moment ->
            val place = moment.places.firstNotNullOfOrNull { places[it]?.name }
            val name = moment.text.lines().firstOrNull()?.take(24)?.ifBlank { null } ?: place ?: if (moment.media.isNotEmpty()) photos else formatDay(moment.dateKey)
            EndRecord(moment.uid, moment.occurredAt, name, place, AppIcons.Timeline)
        } + checkins.byDay.values.flatten().map { EndRecord(it.uid, it.at, it.venue, it.place?.let(places::get)?.name, kindOf(it.place) ?: AppIcons.Pin) }
    }
}

/**
 * An end of the trip: the record it follows, by its name alone, or, with none, the time set by
 * hand; under it which end it is.
 */
@Composable
private fun EndRow(container: AppContainer, uid: String?, at: String, side: String, shapes: cc.hyoban.moments.ui.design.ListItemShapes, tag: String, onClick: () -> Unit) {
    val record = endRecords(container).firstOrNull { it.uid == uid }
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { Icon(record?.icon ?: AppIcons.Calendar, contentDescription = null) },
        supportingContent = { Text(side) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
        modifier = Modifier.testTag(tag),
    ) { Text(record?.name ?: whenOf(at), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}

/**
 * Choosing an end: 手动设定时间 first, opening [onTime], then every record, searched by its words
 * and place, the nearest in time to [around] first, [chosen] ticked; [onPick] a record's uid and time.
 */
@Composable
private fun EndPicker(container: AppContainer, around: String, chosen: String?, onTime: () -> Unit, onPick: (String, String) -> Unit) {
    val records = endRecords(container)
    val query = androidx.compose.foundation.text.input.rememberTextFieldState()
    val words = query.text.toString().trim()
    val target = cc.hyoban.moments.data.trips.epochOf(around)
    val shown = remember(records, target, words) {
        records.filter { words.isEmpty() || it.name.contains(words, ignoreCase = true) || it.place?.contains(words, ignoreCase = true) == true }
            .sortedBy { kotlin.math.abs(cc.hyoban.moments.data.trips.epochOf(it.at) - target) }
    }
    PageWidth(maxColumns = 1) { columns ->
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = columns.margin + Space.gutter,
                end = columns.margin + Space.gutter,
                top = LocalTopClearance.current + Space.l,
                bottom = Space.xl + androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            item(key = "time") {
                SegmentedListItem(
                    onClick = onTime,
                    shapes = cc.hyoban.moments.ui.design.groupedShapes(0, 1),
                    colors = segmentColors(),
                    leadingContent = { Icon(AppIcons.Calendar, contentDescription = null) },
                    supportingContent = { Text(whenOf(around)) },
                    trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.groupedRow(0, 1).testTag("end_time"),
                ) { Text(stringResource(Res.string.trip_edit_set_time)) }
            }
            item(key = "search") {
                cc.hyoban.moments.ui.design.Surface(
                    shape = cc.hyoban.moments.ui.theme.Corners.full,
                    color = cc.hyoban.moments.ui.theme.SurfaceLevel.Card.fill(),
                    border = cc.hyoban.moments.ui.theme.cardBorder(),
                    modifier = Modifier.padding(vertical = Space.l).fillMaxWidth(),
                ) {
                    cc.hyoban.moments.ui.design.SearchField(
                        state = query,
                        onSearch = {},
                        placeholder = { Text(stringResource(Res.string.trip_edit_search_records), maxLines = 1) },
                        leadingIcon = {
                            androidx.compose.foundation.layout.Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Icon(AppIcons.Search, contentDescription = null)
                            }
                        },
                        modifier = Modifier.testTag("end_search"),
                    )
                }
            }
            items(shown.size, key = { shown[it].uid }) { index ->
                val record = shown[index]
                SegmentedListItem(
                    onClick = { onPick(record.uid, record.at) },
                    shapes = cc.hyoban.moments.ui.design.groupedShapes(index, shown.size),
                    colors = segmentColors(),
                    leadingContent = { Icon(record.icon, contentDescription = null) },
                    supportingContent = { Text(whenOf(record.at)) },
                    trailingContent = if (record.uid == chosen) { { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) } } else null,
                    modifier = Modifier.groupedRow(index, shown.size).testTag("end_${record.uid}"),
                ) { Text(record.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
    }
}

/** A record by its first words, else by its photos, else by when it was. */
@Composable
private fun memberTitle(moment: cc.hyoban.moments.data.spec.MomentRecord): String =
    moment.text.lines().firstOrNull()?.take(24)?.ifBlank { null }
        ?: moment.media.size.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.count_photos, it, it) }
        ?: formatTime(moment.occurredAt) ?: formatDay(moment.dateKey)

/** How wide the trip's name, days and covers stand at the start on a phone on its side, its records beside them. */
private val FORM_COLUMN = 320.dp
