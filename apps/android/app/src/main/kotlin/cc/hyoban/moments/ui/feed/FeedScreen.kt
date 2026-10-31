package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.LocalTabsHidden
import androidx.compose.foundation.layout.wrapContentHeight
import cc.hyoban.moments.ui.components.FloatingButton
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.height
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.components.restingElevation
import cc.hyoban.moments.ui.components.floatingElevation
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import cc.hyoban.moments.ui.components.FLOATING_BAR
import cc.hyoban.moments.ui.components.FloatingBack
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
    onTrackDay: (String) -> Unit = onDay,
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
    val tracks = rememberTimelineTracks(container)
    // The app counts as started once the Moments on the phone are read, as the system measures
    // a start to the screen people came for, not the first frame of its skeleton.
    ReportDrawnWhen { loaded && tracks.ready }
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    val pull = rememberPulledRefresh(container)
    ShowRefreshErrors(container)
    // Everything, unfiltered: narrowing by kind and time is the search page's.
    val shown = moments
    val bills = rememberTimelineBills(container, shown, TimeFilter())
    val ledgerNav = LocalLedgerNav.current
    val feedState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    // The header over the views folds away as they scroll on and opens again as they scroll back,
    // or as the tab tapped again takes them to their top. How far it is folded is kept, so a page
    // made again (the theme switched) doesn't open it mid-list.
    val headerScroll = rememberSaveable(saver = HeaderScroll.Saver) { HeaderScroll() }
    // Opening out with the list as it goes back to its top, on the same spring.
    val headerSpec = AppTheme.motionScheme.defaultSpatialSpec<Float>()
    OnTabReselect(Feed) { animate(headerScroll.offset, 0f, animationSpec = headerSpec) { value, _ -> headerScroll.offset = value } }
    // The rows open out as they come and fold away as they go, the Moments under them moving
    // with them, rather than the whole list jumping.
    val rowMotion = AppTheme.motionScheme
    val rowIn = expandVertically(rowMotion.defaultSpatialSpec()) + fadeIn(rowMotion.defaultEffectsSpec())
    val rowOut = shrinkVertically(rowMotion.defaultSpatialSpec()) + fadeOut(rowMotion.defaultEffectsSpec())
    // The tabs fold away down the screen as the timeline is scrolled on, and come back as it is
    // scrolled back, or when it is left.
    val tabsHidden = LocalTabsHidden.current
    DisposableEffect(tabsHidden) { onDispose { tabsHidden.value = false } }
    val tabsFold = remember(tabsHidden) {
        object : NestedScrollConnection {
            // Seen before the header and the list take any of it, so the tabs follow the finger
            // whichever of them moves.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -TABS_FOLD_STEP) tabsHidden.value = true
                else if (available.y > TABS_FOLD_STEP) tabsHidden.value = false
                return Offset.Zero
            }
        }
    }
    val header: FeedLeading.() -> Unit = {
        item(key = "title") {
            // The title as a folded top bar has it from the start: small in the middle, search a
            // circle at the end, the two going up with the Moments as they scroll on.
            Box(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.title_moments),
                    style = AppTheme.typography.body.strong,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)
                        .height(FLOATING_BAR).wrapContentHeight().semantics { heading() },
                )
                Box(Modifier.align(Alignment.TopEnd)) {
                    FloatingButton(AppIcons.Search, stringResource(R.string.action_search), onSearch, tag = "search")
                }
            }
        }
        item(key = "changes") {
            AnimatedVisibility(publishable, enter = rowIn, exit = rowOut) {
                PendingChangesRow(container, onClick = onChanges, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.s))
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
                // No bar: the title, its counts and search head the timeline and scroll away with
                // it, the page running up under the status bar, which a short fade of the page
                // keeps the system's icons clear on.
                Box(Modifier.fillMaxSize().background(AppColors.page)) {
                    TabRefreshBox(pull) {
                        LoadedContent(moments.isEmpty() || !tracks.ready, loading = !loaded || syncing || !tracks.ready, message = R.string.empty_moments, hint = R.string.empty_moments_hint) {
                            // The rows over the Moments, the changes, list or calendar and the
                            // filter, stand once above both views rather than heading each, so
                            // switching cross-fades only what changes and leaves them still.
                            // They scroll away with either and come back as it is scrolled back.
                            Column(Modifier.fillMaxSize().nestedScroll(tabsFold).nestedScroll(headerScroll.connection)) {
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
                                    tracks = tracks,
                                    onTrackDay = onTrackDay,
                                    chat = true,
                                )
                            }
                        }
                    }
                    // Immersive: the Moments run up under the status bar, a short fade of the page
                    // there keeping its icons clear of what passes under.
                    StatusBarFade()
                }
            }
        }
        if (twoPane) {
            // The detail pane, a container of its own beside the list, clear of the bars.
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))
                    .padding(top = Space.s, end = Space.gutter, bottom = Space.s)
                    .clip(AppTheme.shapes.large),
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
                            Text(stringResource(R.string.label_pick_moment), color = tokens.tx2, style = AppTheme.typography.bodyReading)
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
        // Folding away as the list is scrolled on and coming back as soon as it is scrolled back,
        // wherever it is, as the floating tabs do, rather than only at the list's top.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, move(available.y))
    }

    /** Moves it by [delta] within its height, saying how much of that it took. */
    private fun move(delta: Float): Float {
        val next = (offset + delta).coerceIn(-height, 0f)
        val taken = next - offset
        offset = next
        return taken
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<HeaderScroll, Float>(save = { it.offset }, restore = { HeaderScroll().apply { offset = it } })
    }
}

/** A fade of the page from behind the status bar to a little below it, which the Moments pass under. */
@Composable
private fun StatusBarFade() {
    val page = AppColors.page
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = Space.l)))
            .background(Brush.verticalGradient(0f to page.copy(alpha = 0.95f), 0.6f to page.copy(alpha = 0.7f), 1f to page.copy(alpha = 0f))),
    )
}

/** [content] laid out at its own height, less as much as [scroll] has folded it away. */
@Composable
private fun CollapsingHeader(scroll: HeaderScroll, content: @Composable () -> Unit) {
    Box(
        // Not clipped: what it has folded away goes up off the top of the screen, and the search
        // circle's shadow may fall over the Moments under it.
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            scroll.height = placeable.height.toFloat()
            val shown = (placeable.height + scroll.offset.roundToInt()).coerceIn(0, placeable.height)
            layout(placeable.width, shown) { placeable.place(0, shown - placeable.height) }
        },
    ) { content() }
}

/** How far a scroll has to move in one go, in pixels, to fold the tabs away or bring them back. */
private const val TABS_FOLD_STEP = 4f

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
            containerColor = tokens.tx,
            contentColor = tokens.bg,
            modifier = Modifier.testTag("new_moment"),
        ) {
            // A plus, as the design's every new thing is; the pen is for changing what's there.
            Icon(AppIcons.Plus, contentDescription = label)
        }
    }
}

/**
 * One day, `YYYY-MM-DD`: its footprints on a map across the top, under the status bar, its
 * Moments as dots and its bills as rings where the footprints were when they were paid; the day
 * and its figures under it; then the day as the timeline lists it, the trip it was on first,
 * its Moments, bills and charges from the morning on. Only back floats over it.
 */
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
    val ledgerNav = LocalLedgerNav.current
    // The day's own footprints, private and the phone's, read once for the page; null until read.
    val track by androidx.compose.runtime.produceState<List<cc.hyoban.moments.footprints.TrackPoint>?>(null, date) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.footprints.points(cc.hyoban.moments.footprints.TrackSelection(day = date))
        }
    }
    // Whether today is still being recorded, said after its figures; read with the footprints.
    val recording by androidx.compose.runtime.produceState(false, date) {
        if (date == java.time.LocalDate.now().toString()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.footprints.state().recording }
        }
    }
    // The day's bills, as the timeline has them for its month, kept to this day.
    val month = remember(date) { TimeFilter(date.take(4).toInt(), date.substring(5, 7).toInt()) }
    val monthBills = rememberTimelineBills(container, moments, month)
    val bills = remember(monthBills, date) {
        TimelineBills(
            monthBills.ledger,
            monthBills.linked,
            monthBills.loose.filterKeys { it == date },
            monthBills.spent.filterKeys { it == date },
            monthBills.currency,
            monthBills.charges.filterKeys { it == date },
        )
    }
    // The trip goes unsaid on the cards when all of them were on it: its row heads the day.
    val oneTrip = trip != null && moments.all { tripOf(it, trips)?.id == trip.id }

    // The page is the calendar's day, its pill or a Moment's row for it grown to the screen.
    Box(Modifier.fillMaxSize().then(if (card != null) Modifier.sharedCard(card, page = true) else Modifier).background(AppColors.page)) {
        MomentFeed(
            moments, places, trips, container.mediaUrls, onMoment,
            months = false,
            showTrip = !oneTrip,
            dayPills = false,
            bills = bills,
            onBill = { ledgerNav?.bill?.invoke(it) },
            onTrip = onTrip.takeIf { trip != null },
            oldestFirst = true,
            allMoments = all,
        ) {
            item("head") { DayHead(date, track, moments, places, bills, recording) }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
    }
}

/**
 * The head of a day's page: its footprints on a map across the top, or room for back where it
 * has none, then the day and, in one line under it, how far it went, how long it moved and
 * what it spent.
 */
@Composable
private fun DayHead(
    date: String,
    /** The day's footprints; null while they are read. */
    track: List<cc.hyoban.moments.footprints.TrackPoint>?,
    moments: List<cc.hyoban.moments.data.spec.MomentRecord>,
    places: Map<String, cc.hyoban.moments.data.spec.Place>,
    bills: TimelineBills,
    /** Today, with the footprints still recording: its line ends where the phone is now. */
    recording: Boolean = false,
) {
    val points = track.orEmpty()
    // While they are read, the timeline's word on them keeps room for the map and gives the
    // figures, so the page grows out of its card whole and nothing moves when they come.
    val known = remember(date) { knownDayTrack(date) }
    val moving = if (track == null) known != null || knownFootprints(date) else points.size > 1
    // No more than half the screen, so a phone on its side still shows the day under it.
    val height = minOf(DAY_MAP, cc.hyoban.moments.ui.components.windowHeight() / 2)
    Column(Modifier.fillMaxWidth()) {
        if (moving && track == null) {
            Spacer(Modifier.fillMaxWidth().height(height))
        } else if (moving) {
            val stretches = remember(points) {
                cc.hyoban.moments.footprints.trackSegments(points, budget = 2000).filter { it.size > 1 }.map { stretch -> stretch.map { it.longitude to it.latitude } }
            }
            val dots = remember(moments, places) { moments.flatMap { it.places }.mapNotNull { places[it] }.map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) } }
            // Each bill where the footprints were when it was paid, within a few minutes.
            val rings = remember(points, bills) {
                bills.loose[date].orEmpty().mapNotNull { txn ->
                    points.minByOrNull { kotlin.math.abs(it.time - txn.time) }
                        ?.takeIf { kotlin.math.abs(it.time - txn.time) <= BILL_NEAR_MS }
                        ?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
                }
            }
            cc.hyoban.moments.ui.map.DayTrackMap(
                stretches,
                dots,
                Modifier.fillMaxWidth().height(height).testTag("day_track"),
                rings = rings,
                // Clear of the status bar and back over its top, as far as a short map can spare.
                topInset = minOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + FLOATING_BAR, height / 4),
            )
        } else {
            Spacer(Modifier.statusBarsPadding().height(FLOATING_BAR))
        }
        Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(formatDay(date), style = AppTheme.typography.titlePage, modifier = Modifier.semantics { heading() })
            val stats = remember(points) { cc.hyoban.moments.footprints.trackStats(points) }
            val metres = if (track == null) known?.metres else stats.metres
            val millis = if (track == null) known?.movingMillis else stats.recordedMillis
            val line = listOfNotNull(
                if (moving && metres != null && millis != null) stringResource(R.string.day_track_stats, metres / 1000, millis / 60_000) else null,
                daySpent(bills, date)?.let { stringResource(R.string.ledger_expense_short, it) },
            ).joinToString("\u2003")
            // Still recording is a state, so in the accent, after the figures it keeps growing.
            val still = if (recording) stringResource(R.string.day_still_recording) else null
            val accent = tokens.accent
            if (line.isNotEmpty() || still != null) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(line)
                        if (still != null) {
                            if (line.isNotEmpty()) append("\u2003")
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = accent))
                            append(still)
                            pop()
                        }
                    },
                    style = AppTheme.typography.caption,
                    color = tokens.tx2,
                )
            }
        }
    }
}

/** How tall a day's map is, its top under the status bar. */
private val DAY_MAP = 320.dp

/** How far from a bill's time the footprints may be to put it on the map. */
private const val BILL_NEAR_MS = 10 * 60_000L

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
                val resources = LocalResources.current
                val show = rememberShowMessage()
                val onMapLabel = stringResource(R.string.action_show_on_map)
                Tooltip(onMapLabel, below = true) {
                    IconButton(onClick = { onMap(id) }, modifier = Modifier.testTag("show_on_map")) {
                        Icon(AppIcons.Map, contentDescription = onMapLabel)
                    }
                }
                Tooltip(stringResource(R.string.action_navigate), below = true) {
                    IconButton(onClick = { if (!navigateTo(context, place)) show(resources.getString(R.string.no_map_app)) }, modifier = Modifier.testTag("navigate")) {
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
