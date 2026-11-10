package cc.hyoban.moments.ui.trips

import cc.hyoban.moments.res.*
import androidx.activity.compose.BackHandler
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
import cc.hyoban.moments.PhoneContainer
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
    container: PhoneContainer,
    id: String?,
    onBack: () -> Unit,
    onMade: (String) -> Unit = {},
    onDeleted: () -> Unit = onBack,
    startDay: String? = null,
    endDay: String? = null,
    name: String? = null,
) {
    val trips by container.shownTrips.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { id?.let { trips.firstOrNull { it.id == id } } }
    if (id != null && trip == null) return
    val today = remember { LocalDate.now().toString() }
    var title by rememberSaveable { mutableStateOf(trip?.title ?: name.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(trip?.start ?: startDay ?: today) }
    var end by rememberSaveable { mutableStateOf(trip?.end ?: endDay ?: today) }
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
    var include by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.strings(it, "include") }.orEmpty()) }
    var exclude by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.strings(it, "exclude") }.orEmpty()) }
    var covers by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.coversOf(it) }.orEmpty().map { "${it.first}/${it.second}" }) }
    val all by container.shownMoments.collectAsStateWithLifecycle()
    // The Moments of its days and of two days either side, which can be taken in from outside.
    val nearby = remember(all, start, end) {
        val from = LocalDate.parse(start).minusDays(2).toString()
        val to = LocalDate.parse(end).plusDays(2).toString()
        all.filter { it.dateKey in from..to }.sortedBy { it.occurredAt }
    }
    val members = nearby.filter { moment ->
        val inside = moment.dateKey in start..end
        (inside && moment.uid !in exclude) || (!inside && moment.uid in include)
    }
    val (inDays, around) = nearby.partition { it.dateKey in start..end }
    // Its days' records, listed on a page of their own from the row that counts them.
    var listing by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = listing) { listing = false }
    val scope = rememberCoroutineScope()
    val valid = title.isNotBlank() && start <= end
    SubPage(
        stringResource(if (listing) Res.string.trip_edit_in_days_title else if (id == null) Res.string.trip_new_title else Res.string.trip_edit_title),
        { if (listing) listing = false else onBack() },
        Modifier.background(AppColors.page),
    ) {
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
            SegmentedGroup(
                stringResource(Res.string.trip_edit_days),
                null,
                listOf(
                    { shapes -> cc.hyoban.moments.footprints.ActionRow(AppIcons.Calendar, stringResource(Res.string.trip_edit_start), shapes, supporting = formatDay(start), tag = "trip_start") { picking = "start" } },
                    { shapes -> cc.hyoban.moments.footprints.ActionRow(AppIcons.Calendar, stringResource(Res.string.trip_edit_end), shapes, supporting = formatDay(end), tag = "trip_end") { picking = "end" } },
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
                        val offset = if (date.isBefore(LocalDate.parse(start))) -ChronoUnit.DAYS.between(date, LocalDate.parse(start)) else ChronoUnit.DAYS.between(LocalDate.parse(end), date)
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
                                onMade(container.repository.createTrip(title.trim(), start, end, visibility, include, exclude, chosen))
                            } else {
                                container.repository.editTrip(id, title.trim(), start, end, visibility, include = include, exclude = exclude, covers = chosen)
                                // The track follows its switch, on the trip's days as saved.
                                val shared = !private && trackPrivate == false
                                if (trip != null && trackShared != null && shared != trackShared) {
                                    setTrackShared(container, trip.copy(start = start, end = end), shared)
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
        val day = LocalDate.parse(if (which == "start") start else end)
        DateTimeDialog(day, 0, 0, onDismiss = { picking = null }) { picked, _, _ ->
            if (which == "start") start = picked.toString() else end = picked.toString()
            picking = null
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
