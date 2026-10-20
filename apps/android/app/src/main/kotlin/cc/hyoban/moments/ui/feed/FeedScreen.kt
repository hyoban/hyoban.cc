package cc.hyoban.moments.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.RailSpace
import cc.hyoban.moments.ui.calendar.CalendarView
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Symbols
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.dayCardKey
import cc.hyoban.moments.ui.components.isExpandedWidth
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.map.navigateTo
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.publish.PendingChangesRow
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

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
    onMedia: (String, Int) -> Unit = { _, _ -> },
    onPlace: (String) -> Unit = {},
    onTrip: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
) {
    // From Material 3's expanded breakpoint, its list and detail: a Moment chosen opens in a
    // pane beside the Moments rather than as a page over them. Turned upright with one chosen,
    // it goes on as its page; back clears the pane first.
    val twoPane = isExpandedWidth()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val details = rememberSaveableStateHolder()
    val open: (String) -> Unit = { uid -> if (twoPane) chosen = uid else onMoment(uid) }
    LaunchedEffect(twoPane) {
        if (!twoPane) chosen?.let { uid ->
            chosen = null
            onMoment(uid)
        }
    }
    BackHandler(twoPane && chosen != null) { chosen = null }
    val pending by container.repository.pending.collectAsStateWithLifecycle()
    // The changes kept on the phone, to look over and publish, head the Moments while there are any.
    val publishable = container.canPublish && pending.size > 0
    // Writing a Moment is the tab's one floating action button: upright a medium one above the
    // navigation bar, as Material 3 has a phone's FAB; down the side, at the top of the rail.
    val accessory = LocalNavAccessory.current
    val currentOnNew by rememberUpdatedState(onNew)
    val newBeside = remember<@Composable () -> Unit> {
        { NewMomentButton(onClick = { currentOnNew() }, inRail = isLandscape()) }
    }
    DisposableEffect(accessory) {
        accessory.value = newBeside
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

    Row(Modifier.fillMaxSize()) {
        // Beside a detail pane, the list is a pane of its own, as wide as Material 3's list
        // pane, starting past the cutout and the rail, which it then needn't keep clear of.
        val rail = LocalRailSpace.current
        val listPane = if (twoPane) {
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)).padding(start = rail.left).width(LIST_PANE_WIDTH)
        } else {
            Modifier.weight(1f)
        }
        Box(listPane) {
            CompositionLocalProvider(LocalRailSpace provides if (twoPane) RailSpace(0.dp, 0.dp, clearOfInsets = true) else rail) {
                TabPage(
                    stringResource(R.string.title_moments),
                    tab = Feed,
                    subtitle = subtitle,
                    actions = {
                        // How the Moments are laid out is chosen above them; the bar keeps the settings.
                        Tooltip(stringResource(R.string.title_settings), below = true) {
                            IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.title_settings))
                            }
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
                                        onDay = { date, here -> if (here.size == 1) open(here.single().uid) else onDay(date) },
                                        state = calendarState,
                                        leading = { leadingRows(header) },
                                        tab = Feed,
                                    )
                                } else {
                                    MomentFeed(shown, places, trips, container.mediaUrls, open, state = feedState, gridState = gridState, leading = header, tab = Feed, selected = chosen.takeIf { twoPane })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (twoPane) {
            // The detail pane, a container of its own beside the list, clear of the bars.
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))
                    .padding(top = Space.s, end = Space.l, bottom = Space.s)
                    .clip(MaterialTheme.shapes.extraLarge),
            ) {
                val uid = chosen
                if (uid != null && moments.any { it.uid == uid }) {
                    // Each Moment keeps where it was scrolled to while another is shown.
                    details.SaveableStateProvider(uid) {
                        MomentScreen(
                            container,
                            uid,
                            onMedia = onMedia,
                            onPlace = onPlace,
                            onTrip = onTrip,
                            onBack = { chosen = null },
                            onEdit = onEdit,
                            onDay = onDay,
                            pane = true,
                        )
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize().background(LocalSurfaceLevel.current.raised.fill()).testTag("detail_placeholder"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.label_pick_moment), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** How wide the Moments stand beside a detail pane, Material 3's list pane. */
private val LIST_PANE_WIDTH = 400.dp

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
                // The one not chosen outlined, so each stands apart from the page at the 3:1 a group needs.
                border = if (calendar == isCalendar) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }.testTag(if (isCalendar) "view_calendar" else "view_list"),
            ) {
                // Filled once chosen, outlined while not, as Material 3 marks a toggle's state twice.
                val checked = calendar == isCalendar
                Icon(
                    when {
                        isCalendar && checked -> Symbols.CalendarMonthFilled
                        isCalendar -> Symbols.CalendarMonthOutlined
                        checked -> Symbols.ViewAgendaFilled
                        else -> Symbols.ViewAgendaOutlined
                    },
                    contentDescription = null,
                    modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(stringResource(label), maxLines = 1, style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Starts a new Moment: the tab's floating action button, its container the primary one, as
 * Material 3 has a create. A medium FAB above the navigation bar; [inRail], a FAB at the
 * rail's top, level with it, as one nested in a rail rests.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewMomentButton(onClick: () -> Unit, inRail: Boolean = false) {
    val label = stringResource(R.string.action_new_moment)
    Tooltip(label) {
        if (inRail) {
            FloatingActionButton(
                onClick = onClick,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier.testTag("new_moment"),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = label)
            }
        } else {
            MediumFloatingActionButton(onClick = onClick, modifier = Modifier.testTag("new_moment")) {
                Icon(Icons.Rounded.Edit, contentDescription = label, modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize))
            }
        }
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
                Tooltip(stringResource(R.string.action_navigate), below = true) {
                    IconButton(onClick = { if (!navigateTo(context, place)) show(context.getString(R.string.no_map_app)) }, modifier = Modifier.testTag("navigate")) {
                        Icon(Icons.Rounded.Directions, contentDescription = stringResource(R.string.action_navigate), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    ) {
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment)
    }
}
