package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.layout.height
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.components.restingElevation
import cc.hyoban.moments.ui.components.floatingElevation
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.ExtendedFloatingActionButton
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.FloatingActionButton
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.ui.compose.label
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.Feed
import cc.hyoban.moments.ui.LocalNavAccessory
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.RailSpace
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.TabPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.isExpandedWidth
import cc.hyoban.moments.ui.components.isMediumWidth
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.ledger.LedgerFilter
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.ledger.SpendingCard
import cc.hyoban.moments.ui.ledger.SpendingOf
import cc.hyoban.moments.ui.map.navigateTo
import cc.hyoban.moments.ui.moment.MomentScreen
import cc.hyoban.moments.ui.publish.PendingChangesRow
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlin.math.roundToInt

/**
 * The timeline tab: every Moment the site shows and those written on the phone, newest first,
 * month by month, with the ledger's bills among them and a banner where each trip begins; its
 * button records a Moment or a bill. Pull down to bring them up to date.
 */
@Composable
fun FeedScreen(
    container: AppContainer,
    onMoment: (String) -> Unit,
    onDay: (String) -> Unit,
    onNew: () -> Unit,
    onChanges: () -> Unit,
    onSearch: () -> Unit = {},
    onMedia: (String, Int) -> Unit = { _, _ -> },
    onPlace: (String) -> Unit = {},
    onTrip: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    /** A day opened from the row for it on the Moment beside the feed. */
    onDayRow: (String) -> Unit = onDay,
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
    // Recording is the tab's one main action: a round ink button beside the floating tabs, or at
    // the top of the rail down the side.
    val accessory = LocalNavAccessory.current
    val currentOnNew by rememberUpdatedState(onNew)
    val newBeside = remember<@Composable () -> Unit> {
        { NewMomentButton(onClick = { currentOnNew() }, inRail = isMediumWidth()) }
    }
    DisposableEffect(accessory) {
        accessory.value = newBeside
        onDispose { if (accessory.value === newBeside) accessory.value = null }
    }
    val moments by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    // The app counts as started once the Moments on the phone are read, as the system measures
    // a start to the screen people came for, not the first frame of its skeleton.
    ReportDrawnWhen { loaded }
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
        pluralStringResource(R.plurals.count_moments, shown.size, shown.size) + "\u2003" + pluralStringResource(R.plurals.count_places, placeCount, placeCount)
    val bills = rememberTimelineBills(container, shown, filter)
    val ledgerNav = LocalLedgerNav.current
    val feedState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    // A new filter starts both views from the top; coming back to the tab doesn't.
    var lastFilter by rememberSaveable(stateSaver = TimeFilterSaver) { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (filter != lastFilter) {
            lastFilter = filter
            feedState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }
    // The header over the views folds away as they scroll and opens again at their top; back
    // open with a new filter or the tab tapped again, as either goes back to its top.
    val headerScroll = remember { HeaderScroll() }
    LaunchedEffect(filter) { headerScroll.offset = 0f }
    // Opening out with the list as it goes back to its top, on the same spring.
    val headerSpec = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    OnTabReselect(Feed) { animate(headerScroll.offset, 0f, animationSpec = headerSpec) { value, _ -> headerScroll.offset = value } }
    // The rows open out as they come and fold away as they go, the Moments under them moving
    // with them, rather than the whole list jumping.
    val rowMotion = AppTheme.motionScheme
    val rowIn = expandVertically(rowMotion.defaultSpatialSpec()) + fadeIn(rowMotion.defaultEffectsSpec())
    val rowOut = shrinkVertically(rowMotion.defaultSpatialSpec()) + fadeOut(rowMotion.defaultEffectsSpec())
    val header: FeedLeading.() -> Unit = {
        item(key = "changes") {
            AnimatedVisibility(publishable, enter = rowIn, exit = rowOut) {
                PendingChangesRow(container, onClick = onChanges, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
            }
        }
        item(key = "filter") {
            AnimatedVisibility(years.size > 1, enter = rowIn, exit = rowOut) {
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
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)).padding(start = rail.left).width(LIST_PANE_WIDTH + Space.gutter * 2)
        } else {
            Modifier.weight(1f)
        }
        Box(listPane) {
            CompositionLocalProvider(LocalRailSpace provides if (twoPane) RailSpace(0.dp, 0.dp, clearOfInsets = true) else rail) {
                TabPage(
                    stringResource(R.string.title_moments),
                    tab = Feed,
                    refresh = pull.onRefresh,
                    subtitle = subtitle,
                    actions = {
                        // The bar's one action: searching the timeline. The settings are under 我.
                        Tooltip(stringResource(R.string.action_search), below = true) {
                            IconButton(onClick = onSearch, modifier = Modifier.testTag("search")) {
                                Icon(AppIcons.Search, contentDescription = stringResource(R.string.action_search))
                            }
                        }
                    },
                ) {
                    TabRefreshBox(pull) {
                        LoadedContent(moments.isEmpty(), loading = !loaded || syncing, message = R.string.empty_moments) {
                            // The rows over the Moments, the changes, list or calendar and the
                            // filter, stand once above both views rather than heading each, so
                            // switching cross-fades only what changes and leaves them still.
                            // They scroll away with either and come back at its top.
                            Column(Modifier.fillMaxSize().nestedScroll(headerScroll.connection)) {
                                // As wide as the Moments under them and as far in, clear of the rail
                                // and the cutout as they are.
                                CollapsingHeader(headerScroll) {
                                    PageWidth { columns ->
                                        Column(Modifier.padding(horizontal = columns.margin)) { FeedLeading().apply(header).rows.forEach { (_, row) -> row() } }
                                    }
                                }
                                MomentFeed(
                                    shown,
                                    places,
                                    trips,
                                    container.mediaUrls,
                                    open,
                                    Modifier.weight(1f),
                                    state = feedState,
                                    gridState = gridState,
                                    tab = Feed,
                                    selected = chosen.takeIf { twoPane },
                                    onDay = onDay,
                                    bills = bills,
                                    onBill = { ledgerNav?.bill?.invoke(it) },
                                    onTrip = onTrip,
                                )
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
                    .padding(top = Space.s, end = Space.gutter, bottom = Space.s)
                    .clip(AppTheme.shapes.extraLarge),
            ) {
                // One Moment giving way to another, or to the placeholder, as a fade through: the
                // pane's content changes while the pane itself stays.
                val shownUid = chosen?.takeIf { uid -> moments.any { it.uid == uid } }
                AnimatedContent(
                    shownUid,
                    Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn(tween(VIEW_FADE_IN_MS, delayMillis = VIEW_FADE_OUT_MS, easing = LinearOutSlowInEasing)) togetherWith
                            fadeOut(tween(VIEW_FADE_OUT_MS, easing = FastOutLinearInEasing))
                    },
                    label = "detail",
                ) { uid ->
                    if (uid != null) {
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
                                onDay = onDayRow,
                                pane = true,
                                onChanges = onChanges,
                            )
                        }
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(LocalSurfaceLevel.current.raised.fill()).testTag("detail_placeholder"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.label_pick_moment), color = AppTheme.colorScheme.onSurfaceVariant, style = AppTheme.typography.bodyReading)
                        }
                    }
                }
            }
        }
    }
}

/**
 * How wide the Moments stand beside a detail pane, Material 3's fixed pane; the margins each
 * side of them make the window's margin before it and the spacer between the panes after it.
 */
private val LIST_PANE_WIDTH = 360.dp

/**
 * How far the header over the Moments has folded away, as the views under it scroll: it goes
 * first as they scroll up, and comes back only once the view is at its top and pulled further,
 * as an app bar that exits until collapsed does.
 */
class HeaderScroll {
    var height by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0) Offset(0f, move(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0) Offset(0f, move(available.y)) else Offset.Zero
    }

    /** Moves it by [delta] within its height, saying how much of that it took. */
    private fun move(delta: Float): Float {
        val next = (offset + delta).coerceIn(-height, 0f)
        val taken = next - offset
        offset = next
        return taken
    }
}

/** [content] laid out at its own height, less as much as [scroll] has folded it away. */
@Composable
private fun CollapsingHeader(scroll: HeaderScroll, content: @Composable () -> Unit) {
    Box(
        Modifier.clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            scroll.height = placeable.height.toFloat()
            val shown = (placeable.height + scroll.offset.roundToInt()).coerceIn(0, placeable.height)
            layout(placeable.width, shown) { placeable.place(0, shown - placeable.height) }
        },
    ) { content() }
}

// Material's fade-through timings, as switching tabs has.
private const val VIEW_FADE_OUT_MS = 90
private const val VIEW_FADE_IN_MS = 210

/**
 * Records a Moment or a bill: a round ink button, named by a tooltip and for a screen reader;
 * beside the floating tabs it has their soft shadow, [inRail] none, level with the rail.
 */
@Composable
private fun NewMomentButton(onClick: () -> Unit, inRail: Boolean = false) {
    val label = stringResource(R.string.action_new_moment)
    Tooltip(label) {
        FloatingActionButton(
            onClick = onClick,
            elevation = if (inRail) restingElevation() else floatingElevation(),
            shape = Corners.full,
            containerColor = tokens.ink,
            contentColor = tokens.onInk,
            modifier = Modifier.testTag("new_moment"),
        ) {
            Icon(AppIcons.Pen, contentDescription = label)
        }
    }
}

/** The Moments of one day, `YYYY-MM-DD`, as the feed shows them, and the trip they were on. */
@Composable
fun DayScreen(
    container: AppContainer,
    date: String,
    onMoment: (String) -> Unit,
    onBack: () -> Unit,
    onTrip: (String) -> Unit = {},
    /** The key of the card the page grows out of; none when it was opened from elsewhere, as a menu. */
    card: String? = null,
) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, date) { all.filter { it.occurredAt.startsWith(date) } }
    val trip = remember(moments, trips, date) { moments.firstNotNullOfOrNull { tripOf(it, trips) } ?: trips.firstOrNull { date >= it.start && date <= it.end } }

    // The page is the calendar's day, its pill or a Moment's row for it grown to the screen.
    SubPage(
        formatDay(date),
        onBack,
        (if (card != null) Modifier.sharedCard(card, page = true) else Modifier).background(AppColors.page),
        subtitle = moments.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_moments, it, it) },
        actions = {
            // The trip the day was on, as a Moment's page offers its own.
            if (trip != null) {
                Tooltip(trip.title, below = true) {
                    IconButton(onClick = { onTrip(trip.id) }, modifier = Modifier.testTag("day_trip")) {
                        Icon(AppIcons.Luggage, contentDescription = trip.title)
                    }
                }
            }
        },
    ) {
        // The day is the page's title and its trip in the bar, so its cards say neither again:
        // no pill heads them, and the trip goes unsaid when all of them were on it.
        val oneTrip = trip != null && moments.all { tripOf(it, trips)?.id == trip.id }
        val ledgerNav = LocalLedgerNav.current
        // The day's own footprints, private and the phone's, read once for the page.
        val track by androidx.compose.runtime.produceState<List<cc.hyoban.moments.footprints.TrackPoint>>(emptyList(), date) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.footprints.points(cc.hyoban.moments.footprints.TrackSelection(day = date))
            }
        }
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment, months = false, showTrip = !oneTrip, dayPills = false) {
            if (track.size > 1) item("track") { DayTrack(track, moments, places) }
            // What the day cost, from the ledger kept on the phone.
            item("spending") {
                SpendingCard(container, SpendingOf.Day(date), onOpen = { ledgerNav?.list(LedgerFilter(day = date)) }, Modifier.padding(top = Space.s, bottom = Space.s))
            }
        }
    }
}

/**
 * The day's track on a map, its Moments as dots on it, and under it how far it went and for how
 * long it moved, in figures.
 */
@Composable
private fun DayTrack(points: List<cc.hyoban.moments.footprints.TrackPoint>, moments: List<cc.hyoban.moments.data.spec.MomentRecord>, places: Map<String, cc.hyoban.moments.data.spec.Place>) {
    val stretches = remember(points) {
        cc.hyoban.moments.footprints.trackSegments(points, budget = 2000).filter { it.size > 1 }.map { stretch -> stretch.map { it.longitude to it.latitude } }
    }
    val stats = remember(points) { cc.hyoban.moments.footprints.trackStats(points) }
    val dots = remember(moments, places) { moments.flatMap { it.places }.mapNotNull { places[it] }.map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) } }
    Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.s, bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        cc.hyoban.moments.ui.map.DayTrackMap(stretches, dots, Modifier.fillMaxWidth().height(220.dp).clip(AppTheme.shapes.large).testTag("day_track"))
        Text(
            stringResource(R.string.day_track_stats, stats.metres / 1000, stats.recordedMillis / 60_000),
            style = AppTheme.typography.numCaption,
            color = AppTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Moments at one place, newest first. */
@Composable
fun PlaceScreen(
    container: AppContainer,
    id: String,
    onMoment: (String) -> Unit,
    onBack: () -> Unit,
    onDay: (String) -> Unit = {},
    onMap: (String) -> Unit = {},
) {
    val all by container.repository.moments.collectAsStateWithLifecycle()
    val places by container.repository.places.collectAsStateWithLifecycle()
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val moments = remember(all, id) { all.filter { id in it.places } }

    val place = places[id]
    SubPage(
        place?.name ?: id,
        onBack,
        Modifier.background(AppColors.page),
        // What sort of place, where it is, then how many Moments are there.
        subtitle = listOfNotNull(PlaceKind.of(place?.kind)?.let { stringResource(it.label) }, place?.division?.label(), pluralStringResource(R.plurals.count_moments, moments.size, moments.size)).joinToString("\u2003"),
        actions = {
            // On the map tab, its panel up, and the way there, as from that panel.
            if (place != null) {
                val context = LocalContext.current
                val show = rememberShowMessage()
                val onMapLabel = stringResource(R.string.action_show_on_map)
                Tooltip(onMapLabel, below = true) {
                    IconButton(onClick = { onMap(id) }, modifier = Modifier.testTag("show_on_map")) {
                        Icon(AppIcons.Map, contentDescription = onMapLabel)
                    }
                }
                Tooltip(stringResource(R.string.action_navigate), below = true) {
                    IconButton(onClick = { if (!navigateTo(context, place)) show(context.getString(R.string.no_map_app)) }, modifier = Modifier.testTag("navigate")) {
                        Icon(AppIcons.Directions, contentDescription = stringResource(R.string.action_navigate))
                    }
                }
            }
        },
    ) {
        // The place is the page's title, so its cards name only the other places they were at.
        val ledgerNav = LocalLedgerNav.current
        MomentFeed(moments, places, trips, container.mediaUrls, onMoment, onDay = onDay, hidePlace = id) {
            // What was spent there, from the ledger kept on the phone.
            item("spending") {
                SpendingCard(container, SpendingOf.Place(id), onOpen = { ledgerNav?.list(LedgerFilter(place = id)) }, Modifier.padding(top = Space.s, bottom = Space.s))
            }
        }
    }
}
