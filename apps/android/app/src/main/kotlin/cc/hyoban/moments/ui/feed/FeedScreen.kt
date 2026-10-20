package cc.hyoban.moments.ui.feed

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.calendar.CalendarView
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.NAV_HEIGHT
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.dayCardKey
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.map.navigateTo
import cc.hyoban.moments.ui.publish.PendingChangesRow
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.Space

/**
 * The Moments tab: every Moment the site shows and those written on the phone, newest
 * first, month by month, or as a calendar of the months they're in, switched from the
 * header, where a new one is written too. Pull down to bring them up to date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onDay: (String) -> Unit,
    onSettings: () -> Unit,
    onNew: () -> Unit,
    onChanges: () -> Unit,
) {
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    // The changes kept on the phone, to look over and publish, head the Moments while there are any.
    val publishable = container.canPublish && pending.size > 0
    // Writing a Moment is the tab's one floating action button: upright it stands beside the
    // tabs, as the map's locating does; down the side, at the page's bottom end.
    val landscape = isLandscape()
    val accessory = LocalNavAccessory.current
    val currentOnNew by rememberUpdatedState(onNew)
    val newBeside = remember<@Composable () -> Unit> {
        { NewMomentButton(onClick = { currentOnNew() }, modifier = Modifier.size(NAV_HEIGHT)) }
    }
    DisposableEffect(accessory, landscape) {
        if (!landscape) accessory.value = newBeside
        onDispose { if (accessory.value === newBeside) accessory.value = null }
    }
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    val pull = rememberPulledRefresh(container)
    ShowRefreshErrors(container)
    // The year and month the timeline is narrowed to, kept as two numbers to survive a rotation.
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var month by rememberSaveable { mutableStateOf<Int?>(null) }
    val filter = TimeFilter(year, month)
    val years = remember(moments) { yearsOf(moments) }
    val months = remember(moments, year) { year?.let { monthsOf(moments, it) }.orEmpty() }
    val shown = remember(moments, filter) { moments.filtered(filter) }
    val placeCount = remember(shown) { shown.flatMap { it.places }.toSet().size }
    val subtitle = if (shown.isEmpty()) null else
        pluralStringResource(R.plurals.count_moments, shown.size, shown.size) + " · " + pluralStringResource(R.plurals.count_places, placeCount, placeCount)
    var calendar by rememberSaveable { mutableStateOf(false) }
    // Each view keeps where it was scrolled to while the other shows.
    val feedState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    val calendarState = rememberLazyListState()
    // A new filter starts both views from the top; coming back to the tab doesn't.
    var lastFilter by rememberSaveable(stateSaver = TimeFilterSaver) { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            feedState.scrollToItem(0)
            gridState.scrollToItem(0)
            calendarState.scrollToItem(0)
        }
    }
    val header: FeedLeading.() -> Unit = {
        if (publishable) {
            item(key = "changes") {
                PendingChangesRow(container, onClick = onChanges, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
            }
        }
        item(key = "view") { ViewSwitch(calendar, onChange = { calendar = it }) }
        if (years.size > 1) {
            item(key = "filter") {
                TimeFilterRows(filter, years, months) { next ->
                    year = next.year
                    month = next.month
                }
            }
        }
    }

    TabPage(
        stringResource(R.string.title_moments),
        tab = Feed,
        subtitle = subtitle,
        actions = {
            // How the Moments are laid out is chosen above them; the bar keeps the settings.
            IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.title_settings))
            }
        },
    ) {
        TabRefreshBox(pull) {
            if (moments.isEmpty()) {
                EmptyState(loading = !loaded || syncing, message = R.string.empty_moments)
            } else {
                Crossfade(calendar, label = "feed or calendar") { showCalendar ->
                    if (showCalendar) {
                        CalendarView(
                            shown,
                            container.mediaUrls,
                            // One Moment opens on its own; more open as the day's list.
                            onDay = { date, here -> if (here.size == 1) onMoment(here.single().uid) else onDay(date) },
                            state = calendarState,
                            leading = { leadingRows(header) },
                            tab = Feed,
                        )
                    } else {
                        MomentFeed(shown, places, trips, container.mediaUrls, onMoment, state = feedState, gridState = gridState, leading = header, tab = Feed)
                    }
                }
            }
        }
        if (landscape) {
            NewMomentButton(
                onClick = onNew,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .padding(end = LocalRailSpace.current.right + Space.l, bottom = Space.l),
            )
        }
    }
}

/**
 * The Moments as a list or as a calendar, one chosen, as Material 3's connected button group
 * switches between views: across the page, each its icon and name.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewSwitch(calendar: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        listOf(false to R.string.label_view_list, true to R.string.label_view_calendar).forEachIndexed { index, (isCalendar, label) ->
            ToggleButton(
                checked = calendar == isCalendar,
                onCheckedChange = { onChange(isCalendar) },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }.testTag(if (isCalendar) "view_calendar" else "view_list"),
            ) {
                Icon(if (isCalendar) Icons.Outlined.CalendarMonth else Icons.Outlined.ViewAgenda, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
}

/** Starts a new Moment: the tab's floating action button, its container the primary one, as Material 3 has a create. */
@Composable
private fun NewMomentButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.testTag("new_moment"),
    ) {
        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_new_moment))
    }
}

/** The Moments of one day, `YYYY-MM-DD`, as the feed shows them. */
@Composable
fun DayScreen(container: AppContainer, date: String, onMoment: (String) -> Unit, onBack: () -> Unit) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, date) { all.filter { it.occurredAt.startsWith(date) } }

    // The page is the calendar's day grown to the screen.
    SubPage(
        formatDay(date),
        onBack,
        Modifier.sharedCard(dayCardKey(date), page = true).background(AppColors.page),
        subtitle = pluralStringResource(R.plurals.count_moments, moments.size, moments.size),
    ) {
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment, months = false)
    }
}

/** The Moments at one place, newest first. */
@Composable
fun PlaceScreen(container: AppContainer, id: String, onMoment: (String) -> Unit, onBack: () -> Unit) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, id) { all.filter { id in it.places } }

    val place = places[id]
    SubPage(
        place?.name ?: id,
        onBack,
        Modifier.background(AppColors.page),
        subtitle = pluralStringResource(R.plurals.count_moments, moments.size, moments.size),
        actions = {
            // The way there, as from the map's panel of the place.
            if (place != null) {
                val context = LocalContext.current
                val show = rememberShowMessage()
                IconButton(onClick = { if (!navigateTo(context, place)) show(context.getString(R.string.no_map_app)) }, modifier = Modifier.testTag("navigate")) {
                    Icon(Icons.Rounded.Directions, contentDescription = stringResource(R.string.action_navigate), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) {
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment)
    }
}
