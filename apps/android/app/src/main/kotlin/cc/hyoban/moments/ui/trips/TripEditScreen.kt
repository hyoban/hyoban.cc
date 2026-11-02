package cc.hyoban.moments.ui.trips

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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DateTimeDialog
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
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
) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { id?.let { trips.firstOrNull { it.id == id } } }
    if (id != null && trip == null) return
    val today = remember { LocalDate.now().toString() }
    var title by rememberSaveable { mutableStateOf(trip?.title ?: name.orEmpty()) }
    var start by rememberSaveable { mutableStateOf(trip?.start ?: startDay ?: today) }
    var end by rememberSaveable { mutableStateOf(trip?.end ?: endDay ?: today) }
    var private by rememberSaveable { mutableStateOf(id != null && container.repository.isTripPrivate(id)) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val unpublished = id != null && container.repository.isTripNew(id)
    val collection = remember(id) { id?.let { container.repository.tripCollection(it) } }
    var include by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.strings(it, "include") }.orEmpty()) }
    var exclude by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.strings(it, "exclude") }.orEmpty()) }
    var covers by rememberSaveable { mutableStateOf(collection?.let { cc.hyoban.moments.data.spec.Entries.coversOf(it) }.orEmpty().map { "${it.first}/${it.second}" }) }
    val all by container.repository.moments.collectAsStateWithLifecycle()
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
    val scope = rememberCoroutineScope()
    val valid = title.isNotBlank() && start <= end
    SubPage(stringResource(if (id == null) R.string.trip_new_title else R.string.trip_edit_title), onBack, Modifier.background(AppColors.page)) {
        val form: @Composable () -> Unit = {
            OutlinedTextField(
                title,
                { title = it },
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).testTag("trip_title"),
                label = { Text(stringResource(R.string.trip_edit_name)) },
                singleLine = true,
            )
            SegmentedGroup(
                stringResource(R.string.trip_edit_days),
                stringResource(R.string.trip_edit_days_note),
                listOf(
                    { shapes -> cc.hyoban.moments.footprints.ActionRow(AppIcons.Calendar, stringResource(R.string.trip_edit_start), shapes, supporting = formatDay(start), tag = "trip_start") { picking = "start" } },
                    { shapes -> cc.hyoban.moments.footprints.ActionRow(AppIcons.Calendar, stringResource(R.string.trip_edit_end), shapes, supporting = formatDay(end), tag = "trip_end") { picking = "end" } },
                ),
            )
        }
        val membersGroup: @Composable () -> Unit = {
            // Which records it holds: its days' by themselves, the rest of these taken in by hand.
            if (nearby.isNotEmpty()) SegmentedGroup(
                stringResource(R.string.trip_edit_members),
                stringResource(R.string.trip_edit_members_note),
                nearby.map<cc.hyoban.moments.data.spec.MomentRecord, cc.hyoban.moments.ui.settings.Segment> { moment ->
                    { shapes ->
                        val inside = moment.dateKey in start..end
                        val on = moment in members
                        SwitchRow(
                            on,
                            if (inside) AppIcons.Calendar else AppIcons.Plus,
                            moment.text.lines().firstOrNull()?.take(24)?.ifBlank { null } ?: formatDay(moment.dateKey),
                            "member_${moment.uid}",
                            shapes,
                            supporting = formatDay(moment.dateKey),
                        ) {
                            if (inside) exclude = if (on) exclude + moment.uid else exclude - moment.uid
                            else include = if (on) include - moment.uid else include + moment.uid
                        }
                    }
                },
            )
        }
        val rest: @Composable () -> Unit = {
            // Its covers, chosen from its records' photos in the order tapped.
            val photos = members.flatMap { moment -> moment.media.filter { it.type != cc.hyoban.moments.data.spec.MediaType.VIDEO }.map { moment.uid to it } }
            if (photos.isNotEmpty()) {
                cc.hyoban.moments.ui.settings.Caption(stringResource(R.string.trip_edit_covers))
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
                stringResource(if (private) R.string.moment_private_note else R.string.trip_edit_public_note).takeIf { LocalSettings.current.connected },
                listOf { shapes ->
                    SwitchRow(private, AppIcons.EyeOff, stringResource(R.string.visibility_private), "trip_private", shapes) { private = !private }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s, androidx.compose.ui.Alignment.End),
            ) {
                // Only one not yet published goes from here; the site's trips have no deleting yet.
                if (unpublished) {
                    TextButton(onClick = { deleting = true }, modifier = Modifier.testTag("trip_delete")) { Text(stringResource(R.string.trip_delete)) }
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
                                onBack()
                            }
                        }
                    },
                    modifier = Modifier.testTag("trip_save"),
                ) { Text(stringResource(R.string.action_save)) }
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
            title = { Text(stringResource(R.string.trip_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        container.repository.revertTrip(id)
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
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

/** How wide the trip's name, days and covers stand at the start on a phone on its side, its records beside them. */
private val FORM_COLUMN = 320.dp
