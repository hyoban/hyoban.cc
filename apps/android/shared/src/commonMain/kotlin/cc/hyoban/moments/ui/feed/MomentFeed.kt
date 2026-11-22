package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.data.trips.countOf
import cc.hyoban.moments.ui.format.formatDay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.data.trips.tripOf
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalFabClearance
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.components.DayPill
import cc.hyoban.moments.ui.components.MomentCard
import cc.hyoban.moments.ui.components.MonthHeader
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.format.failureReason
import cc.hyoban.moments.ui.format.formatDayOfMonth
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * What goes above a feed's Moments, a row at a time, each by its key: the page's header,
 * room for a back button. Kept apart from the list it goes in, as the feed is a list in
 * one column and a staggered grid in two.
 */
class FeedLeading {
    val rows = mutableListOf<Pair<String, @Composable () -> Unit>>()

    fun item(key: String, content: @Composable () -> Unit) {
        rows += key to content
    }
}

// The feed's days and rows, kept past its composition, as [Kept] says.
private val keptDays = Kept<List<Pair<String, List<MomentRecord>>>>(4)
private val keptTimeline = Kept<List<Triple<String, List<MomentRecord>, List<DayItem>>>>(4)

/** The rows of [leading] in a list. */
fun LazyListScope.leadingRows(leading: FeedLeading.() -> Unit) {
    FeedLeading().apply(leading).rows.forEach { (key, content) -> item(key = key) { Box(Modifier.animateItem()) { content() } } }
}

/**
 * Moments as cards, a large header where a month starts and a pill for each day that
 * floats over its Moments as they scroll; [leading] goes above the first. [dayLabel]
 * says what a day's pill reads. Where the width holds two columns, the cards fall into
 * them as a waterfall, each going under the shorter column, and each card says its day.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MomentFeed(
    moments: List<MomentRecord>,
    places: Map<String, Place>,
    trips: List<TripRecord>,
    urls: MediaUrls,
    onMoment: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    months: Boolean = true,
    dayLabel: (String) -> String = ::formatDayOfMonth,
    showTrip: Boolean = true,
    /** The tab the feed is on, which, tapped again while shown, takes it back to its top. */
    tab: Any? = null,
    /** The Moment shown in a detail pane beside the feed, marked as chosen. */
    selected: String? = null,
    /** Opens a day's page from its pill, `YYYY-MM-DD`; the pills stay put without it. */
    onDay: ((String) -> Unit)? = null,
    /** Whether a pill heads each day's Moments; not on a page that is one day already. */
    dayPills: Boolean = true,
    /** What a day's header says at its end, as a trip's 第 n 天. */
    dayNote: (String) -> String? = { null },
    /** The place the page is of, which its cards needn't name. */
    hidePlace: String? = null,
    /** The ledger's bills on these days, in the Moments' cards and between them, and each day's spending. */
    bills: TimelineBills = TimelineBills.EMPTY,
    /** Opens a bill's page. */
    onBill: (String) -> Unit = {},
    /** Opens a trip's page from the banner where it begins; without it, no banners. */
    onTrip: ((String) -> Unit)? = null,
    /** The footprints, each day's cut at its records into rows between them; they open the day. */
    tracks: TimelineTracks = TimelineTracks.EMPTY,
    /** Each day read from its morning, as a day's own page reads it, rather than newest first. */
    oldestFirst: Boolean = false,
    /** Every Moment, for a trip's banner to count all of its own, on a page that shows only some. */
    allMoments: List<MomentRecord> = moments,
    /**
     * The single column as a conversation: each day its date in a capsule, then read from its
     * morning, Moments as bubbles from the right and the records between them from the left.
     */
    chat: Boolean = false,
    /** The days from the first, as a trip's are read, rather than newest first. */
    chronological: Boolean = false,
    /**
     * Opens the single column at its end, where the newest of a [chronological] feed is, and keeps
     * it there as records arrive while it is read there; the tab tapped again goes back to it.
     */
    fromEnd: Boolean = false,
    /** The ways between the conversation's stops: in the footprints' lines before a stop, else a bubble of their own. */
    legs: cc.hyoban.moments.ui.trips.LegLines? = null,
    leading: FeedLeading.() -> Unit = {},
) {
    val days = remember(moments) { keptDays.get(moments) { moments.groupBy { it.dateKey }.toList() } }
    // The single column's days: those with Moments and those with bills alone, newest first.
    // Footprints join a day that is there, never make one of their own.
    // Each day's footprints as read so far, the day laid out again as its own are read.
    val read = tracks.days.associateWith { tracks.cached(it) }
    val timeline = remember(days, bills, read, oldestFirst, chat, chronological) { keptTimeline.get(days, bills, read, oldestFirst, chat, chronological) {
        val byDay = days.toMap()
        (byDay.keys + bills.loose.keys + bills.charges.keys + bills.things.keys + bills.checkins.keys).sortedDescending().let { if (chronological) it.reversed() else it }.map { day ->
            val items = dayItems(byDay[day].orEmpty(), bills.loose[day].orEmpty(), bills.charges[day].orEmpty(), bills.things[day].orEmpty(), bills.checkins[day].orEmpty(), oldestFirst = oldestFirst || chat, day = day, track = read[day], drive = bills.drives[day])
            Triple(day, byDay[day].orEmpty(), items)
        }
    } }
    val bottom = LocalBottomClearance.current + LocalFabClearance.current + Space.xl
    // The day the list opens at when it opens at its end: today, or the last day before it, the
    // planned days to come after it.
    val today = remember(timeline, fromEnd) {
        val now = java.time.LocalDate.now().toString()
        if (fromEnd && chronological) timeline.lastOrNull { it.first <= now }?.first else null
    }
    val todayEnd = remember { TodayEnd() }
    val card: @Composable (MomentRecord, Modifier, Boolean) -> Unit = { moment, cardModifier, showDate ->
        MomentCard(
            moment,
            places,
            trip = if (showTrip) tripOf(moment, trips) else null,
            urls = urls,
            onOpen = { onMoment(moment.uid) },
            modifier = cardModifier,
            showDate = showDate,
            selected = moment.uid == selected,
            hidePlace = hidePlace,
            bills = bills.linked[moment.uid].orEmpty(),
            ledger = bills.ledger,
            onBill = onBill,
        )
    }

    // A conversation stays one column at any width, as the design has the timeline on its side (H01).
    PageWidth(modifier, maxColumns = if (chat) 1 else 2) { columns ->
        if (columns.count > 1) {
            OnTabReselect(tab) { gridState.animateScrollToItem(0) }
            val rows = remember(leading) { FeedLeading().apply(leading).rows }
            // Headers run across both columns, out to the page's own edges.
            val across = Modifier.wide(Space.gutter)
            LazyVerticalStaggeredGrid(
                StaggeredGridCells.Fixed(columns.count),
                Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = columns.margin + Space.gutter, top = LocalTopClearance.current, end = columns.margin + Space.gutter, bottom = bottom),
                // Material 3's gap between cards, 8dp at most.
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalItemSpacing = Space.s,
            ) {
                rows.forEach { (key, content) ->
                    item(key = key, span = StaggeredGridItemSpan.FullLine) { Box(Modifier.animateItem().then(across)) { content() } }
                }
                // A month at a time, or, where there are no months to head, a day at a time.
                val groups = if (months) byMonth(moments) else days
                groups.forEach { (group, here) ->
                    if (months || dayPills) item(key = "group-$group", contentType = "group", span = StaggeredGridItemSpan.FullLine) {
                        Box(Modifier.animateItem().then(across)) {
                            if (months) {
                                val (name, year) = formatMonth(here.first().dateKey)
                                MonthHeader(name, year)
                            } else {
                                DayPill(dayPillText(dayLabel(group), here.size), group, onDay?.let { { it(group) } })
                            }
                        }
                    }
                    items(here, key = { it.uid }, contentType = { "moment" }) { moment ->
                        card(moment, Modifier.animateItem(), months)
                    }
                }
                // Across the end, so both columns end together: a column left short, a month
                // with one Moment leaving the other empty, has the grid finding its end over
                // and over while it is pushed against it, jumping back and forth.
                item(key = "end", contentType = "end", span = StaggeredGridItemSpan.FullLine) { Spacer(Modifier) }
            }
        } else {
            if (fromEnd) {
                // At today until it is scrolled away from, and again once scrolled back to it: the
                // end of today, or of the last day before it, at the bottom, the planned days still
                // to come below it.
                var atEnd by rememberSaveable { mutableStateOf(true) }
                fun target() = todayEnd.index.takeIf { it >= 0 } ?: (state.layoutInfo.totalItemsCount - 1)
                LaunchedEffect(state) {
                    launch {
                        snapshotFlow { state.isScrollInProgress }.drop(1).filter { !it }.collect {
                            val end = target()
                            atEnd = !state.canScrollForward || state.layoutInfo.visibleItemsInfo.any { it.index == end }
                        }
                    }
                    snapshotFlow { state.layoutInfo.totalItemsCount }.collect { count ->
                        if (atEnd && count > 0) state.showEnd(target(), animated = false)
                    }
                }
                OnTabReselect(tab) {
                    if (state.layoutInfo.totalItemsCount > 0) state.showEnd(target(), animated = true)
                    atEnd = true
                }
            } else {
                OnTabReselect(tab) { state.animateScrollToItem(0) }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = columns.margin, top = LocalTopClearance.current, end = columns.margin, bottom = bottom),
            ) { CountingScope(this).run {
                todayEnd.index = -1
                leadingRows(leading)
                var month: String? = null
                val bannered = mutableSetOf<String>()
                timeline.forEach { (day, dayMoments, dayItems) ->
                    // A day's footprints read from the phone as the day comes into view, then cut among its records.
                    if (tracks.unread(day)) item(key = "trackread-$day", contentType = "trackread") {
                        LaunchedEffect(day, tracks) { tracks.load(day) }
                    }
                    // Right under a month's header or a trip's banner, a day needs less room above it; so
                    // does the conversation's first day, right under the page's own top.
                    var headed = chat && day == timeline.first().first
                    // The conversation has no month headings: its capsules carry the date, and the year when it isn't this one.
                    if (months && !chat && day.take(7) != month) {
                        headed = true
                        month = day.take(7)
                        val (name, year) = formatMonth(day)
                        val monthKey = day.take(7)
                        item(key = "month-$monthKey", contentType = "month") {
                            Box(Modifier.animateItem()) { MonthHeader(name, year) }
                        }
                    }
                    // Where a trip begins, reading down: above its last day on the timeline, or, read from
                    // the first day, at the time it began on its first, when that is known to the minute.
                    val trip = if (onTrip != null) trips.firstOrNull { day >= it.start && day <= it.end && it.id !in bannered } else null
                    if (trip != null) bannered += trip.id
                    val tripBanner: (Dp, Dp) -> Unit = { top, bottom ->
                        if (trip != null && onTrip != null) item(key = "trip-${trip.id}", contentType = "trip") {
                            val count = remember(trip, allMoments, trips) { countOf(trip, allMoments, trips) }
                            TripBanner(trip, count, onClick = { onTrip(trip.id) }, modifier = Modifier.animateItem().padding(horizontal = Space.gutter).padding(top = top, bottom = bottom))
                        }
                    }
                    val begins = trip?.takeIf { chat && chronological && day == it.start }?.startAt?.let(::instantOf)
                    if (trip != null && begins == null) {
                        headed = true
                        tripBanner(if (chat && day != timeline.first().first) Space.xl else Space.s, Space.s)
                    }
                    if (chat) {
                        // The day's date in a capsule at the middle, a section's gap above it; then
                        // its Moments and records, a run of bubbles from the same side drawn
                        // together, barely apart, and a wider gap where the side changes.
                        if (dayPills) item(key = "dayhead-$day", contentType = "datecap") {
                            DateCapsule(
                                day,
                                onDay?.let { { it(day) } },
                                Modifier.animateItem().then(if (day == timeline.first().first) Modifier.pullUp(CAPSULE_SLACK - Space.xs) else Modifier).padding(horizontal = Space.gutter).padding(top = if (headed) 0.dp else (Space.xl - CAPSULE_SLACK).coerceAtLeast(0.dp), bottom = (Space.m - CAPSULE_SLACK).coerceAtLeast(0.dp)),
                                note = dayNote(day),
                                description = pluralStringResource(Res.plurals.count_moments, dayMoments.size, dayMoments.size),
                            )
                        }
                        val all = chatItems(dayItems, breakAt = begins)
                        val lastChat = all.lastOrNull()?.key
                        // The trip's banner among the day's bubbles, before the first from when it began.
                        val cut = if (begins == null) all.size else all.indexOfFirst { (it.at() ?: Long.MIN_VALUE) >= begins }.let { if (it < 0) all.size else it }
                        val parts = if (begins == null) listOf(all) else listOf(all.subList(0, cut), all.subList(cut, all.size))
                        parts.forEachIndexed { part, chats ->
                            if (part == 1) tripBanner(if (cut == 0) 0.dp else Space.m, Space.m)
                            items(chats, key = { it.key }, contentType = { it::class }) { item ->
                                val index = chats.indexOf(item)
                                // Moments and check-ins come from the right, records from the left.
                                fun sent(at: ChatItem) = at !is ChatItem.Records
                                val joinedAbove = index > 0 && sent(chats[index - 1]) == sent(item)
                                val joinedBelow = index < chats.lastIndex && sent(chats[index + 1]) == sent(item)
                                // The way to a stop: told in the footprints' lines just before it, else a bubble of its own over it.
                                val tracked = { at: ChatItem? -> at is ChatItem.Records && at.lines.any { it is RecordLine.Track } }
                                val alone = legs?.takeIf { item !is ChatItem.Records && it.has(item.key) && !tracked(chats.getOrNull(index - 1)) }
                                val itemModifier = Modifier.animateItem().padding(
                                    start = Space.xs,
                                    end = Space.xs,
                                    top = if (index == 0) 0.dp else if (joinedAbove && alone == null) Space.xs else Space.m,
                                    bottom = if (item.key == lastChat) Space.s else 0.dp,
                                )
                                androidx.compose.foundation.layout.Column(itemModifier.fillMaxWidth()) {
                                if (alone != null) androidx.compose.foundation.layout.Box(Modifier.padding(bottom = Space.m)) { alone.Alone(item.key) }
                                val itemModifier = Modifier
                                when (item) {
                                    // Where a trip's banner marks its days, the bubbles within them needn't name it.
                                    is ChatItem.Moment -> MomentCard(
                                        item.moment, places,
                                        trip = if (showTrip && onTrip == null) tripOf(item.moment, trips) else null,
                                        urls = urls,
                                        onOpen = { onMoment(item.moment.uid) },
                                        modifier = itemModifier,
                                        selected = item.moment.uid == selected,
                                        hidePlace = hidePlace,
                                        bubble = true,
                                        joinedAbove = joinedAbove,
                                        joinedBelow = joinedBelow,
                                        bills = bills.linked[item.moment.uid].orEmpty(),
                                        ledger = bills.ledger,
                                        onBill = onBill,
                                        charges = bills.linkedCharges[item.moment.uid].orEmpty(),
                                    )
                                    is ChatItem.CheckinStop -> CheckinBubble(
                                        item.checkin, joinedAbove, joinedBelow, itemModifier,
                                        bills.linked[item.checkin.uid].orEmpty(), bills.ledger, onBill,
                                        charges = bills.linkedCharges[item.checkin.uid].orEmpty(),
                                    )
                                    is ChatItem.Records -> {
                                        // Its footprints' lines say how the way to the stop after them was gone.
                                        val next = chats.getOrNull(index + 1)?.key
                                        val openDay = onDay?.let { { it(day) } }
                                        RecordsBubble(
                                            item.lines, bills.ledger, onBill,
                                            onDay = openDay,
                                            modifier = itemModifier,
                                            trackRow = next?.takeIf { legs?.has(it) == true }?.let { key -> { line -> legs!!.Track(key, line, openDay) } },
                                        )
                                    }
                                }
                                }
                            }
                        }
                        if (day == today) todayEnd.index = count - 1
                        return@forEach
                    }
                    // The day's header row above it, then the day across the column, days set
                    // apart by space alone, further than anything within a day; right under a
                    // month's header or a trip's banner, only the item gap.
                    if (dayPills) item(key = "dayhead-$day", contentType = "dayhead") {
                        DayHeader(
                            day,
                            pluralStringResource(Res.plurals.count_moments, dayMoments.size, dayMoments.size),
                            onDay?.let { { it(day) } },
                            Modifier.animateItem().padding(horizontal = Space.gutter).dayHeaderSpacing(if (headed) Space.l else Space.xl, below = if (dayItems.firstOrNull() is DayItem.Moment) Space.m else 0.dp),
                            note = dayNote(day),
                        )
                    }
                    // Rows one after another sit together, their own height spacing them; a
                    // Moment has room above and below it.
                    val last = dayItems.lastOrNull()?.key
                    val tops = dayItems.mapIndexed { index, item ->
                        item.key to when {
                            index == 0 -> if (!dayPills) Space.s else 0.dp
                            item is DayItem.Moment || dayItems[index - 1] is DayItem.Moment -> Space.l
                            else -> 0.dp
                        }
                    }.toMap()
                    items(dayItems, key = { it.key }, contentType = { it::class }) { item ->
                        val itemModifier = Modifier.animateItem().padding(
                            start = Space.gutter,
                            end = Space.gutter,
                            top = tops[item.key] ?: 0.dp,
                            bottom = if (item.key == last) Space.s else 0.dp,
                        )
                        when (item) {
                            is DayItem.Moment -> bills.linkedCharges[item.moment.uid]?.takeIf { it.isNotEmpty() }?.let { charges ->
                                Column(itemModifier) {
                                    card(item.moment, Modifier, false)
                                    charges.forEach { ChargeRow(it, ledger = bills.ledger) }
                                }
                            } ?: card(item.moment, itemModifier, false)
                            is DayItem.Bills -> BillGroup(item.txns, bills.ledger, onBill, itemModifier)
                            is DayItem.ChargeItem -> ChargeRow(item.charge, itemModifier, bills.ledger)
                            is DayItem.ThingItem -> ThingRow(item.thing, itemModifier)
                            is DayItem.CheckinItem -> Column(itemModifier) {
                                CheckinRow(item.checkin)
                                bills.linkedCharges[item.checkin.uid]?.forEach { ChargeRow(it, ledger = bills.ledger) }
                                bills.linked[item.checkin.uid]?.takeIf { it.isNotEmpty() }?.let { BillGroup(it, bills.ledger, onBill) }
                            }
                            is DayItem.Track -> TrackRow(item.day, item.piece, onClick = { onDay?.invoke(item.day) }, itemModifier)
                            is DayItem.Drive -> DriveRow(item.drive, itemModifier)
                        }
                    }
                    if (day == today) todayEnd.index = count - 1
                }
            } }
        }
    }
}

/** Where the list's day of today ends, worked out as its rows are laid out. */
private class TodayEnd {
    var index = -1
}

/** A list's rows, counted as they are added, so a row's index is known by where it was added. */
private class CountingScope(private val list: LazyListScope) : LazyListScope by list {
    var count = 0
        private set

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        count++
        list.item(key, contentType, content)
    }

    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
        this.count += count
        list.items(count, key, contentType, itemContent)
    }
}

/** Scrolls so that the row at [index] ends at the bottom of the list, above its padding. */
private suspend fun LazyListState.showEnd(index: Int, animated: Boolean) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        if (animated) animateScrollToItem(index) else scrollToItem(index)
    }
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val by = (row.offset + row.size - (info.viewportEndOffset - info.afterContentPadding)).toFloat()
    if (animated) animateScrollBy(by) else scrollBy(by)
}

/**
 * A day's header row on a timeline, a ledger or a trip: the day of the month in figures and its
 * weekday on one baseline, today's figures in the accent, and at the end what the day came to
 * ([note]), which drops under them when it doesn't fit. Tapped for the whole day, which grows
 * out of it. No line or dot joins one day to the next.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DayHeader(day: String, description: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, note: String? = null, noteFigures: Boolean = false) {
    val date = java.time.LocalDate.parse(day)
    val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, cc.hyoban.moments.ui.components.currentLocale())
    // At least a touch target high, the row's words at its foot, so the target's extra height
    // falls in the gap above the day rather than between the header and what it heads.
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = DAY_HEADER_TARGET)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(Res.string.label_whole_day), onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { heading(); contentDescription = listOfNotNull(formatDay(day), note, description.takeIf { it.isNotBlank() }).joinToString(" ") }
            .testTag("day_header_$day"),
        contentAlignment = Alignment.BottomStart,
    ) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                // Every day's figure in the ink, today's too: the date says which day it is.
                Text(date.dayOfMonth.toString(), style = AppTheme.typography.title, color = tokens.tx, modifier = Modifier.alignByBaseline())
                Text(weekday, style = AppTheme.typography.caption, color = tokens.tx2, modifier = Modifier.alignByBaseline())
            }
            note?.let {
                Spacer(Modifier.weight(1f))
                Text(it, style = if (noteFigures) AppTheme.typography.caption else AppTheme.typography.caption, color = tokens.tx2, maxLines = 1, modifier = Modifier.padding(bottom = Space.xs))
            }
        }
    }
}

private val DAY_HEADER_TARGET = 48.dp

/** How much of a capsule's 48dp touch target lies above and below its words, counted into the gaps around it. */
private val CAPSULE_SLACK = 14.dp

/**
 * [gap] above a day's header, less the room its touch target already leaves over its words, and
 * [below] under it: `space-m` over a Moment, nothing over a row, which spaces itself.
 */
@Composable
fun Modifier.dayHeaderSpacing(gap: Dp, below: Dp = Space.m): Modifier {
    val line = with(LocalDensity.current) { AppTheme.typography.title.lineHeight.toDp() }
    val slack = (DAY_HEADER_TARGET - line).coerceAtLeast(0.dp)
    return padding(top = (gap - slack).coerceAtLeast(0.dp), bottom = below)
}

/** A day's pill: its label, how many Moments it has when more than one, and what it spent. */
@Composable
private fun dayPillText(label: String, count: Int, spent: String? = null): String =
    listOfNotNull(label, if (count > 1) pluralStringResource(Res.plurals.count_moments, count, count) else null, spent).joinToString(" ")

/**
 * Up by [by], taking that much off its height: the first day's capsule, its touch target's room
 * above its words laid over the page's top rather than adding to the gap under it.
 */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val cut = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - cut) { placeable.place(0, -cut) }
}

/** The Moments month by month, as `YYYY-MM` and that month's, in the order given. */
private fun byMonth(moments: List<MomentRecord>): List<Pair<String, List<MomentRecord>>> =
    moments.groupBy { it.dateKey.take(7) }.toList()

/** Wider by [by] each side than the space it is given: a header across a grid out to the page's edges, past the grid's own padding. */
private fun Modifier.wide(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Up to [columns] cards side by side, each as wide as the others, a short row's last place left empty. */
@Composable
fun <T> CardRow(cards: List<T>, columns: Int, modifier: Modifier = Modifier, card: @Composable RowScope.(T) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top) {
        cards.forEach { card(it) }
        repeat(columns - cards.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * A tab's [content], or while it is [empty] its [EmptyState]; while it is still [loading] with
 * nothing to show, the first sync on a phone with nothing on it yet, a spinner in the middle.
 */
@Composable
fun LoadedContent(empty: Boolean, loading: Boolean, message: StringResource, content: @Composable () -> Unit) {
    val fade = AppTheme.motionScheme.fastEffectsSpec<Float>()
    // Three faces, the spinner, the message and the content, each kept as it was while it fades
    // out: the spinner leaving as the content comes doesn't turn into the message on its way.
    val face = when {
        !empty -> LoadedFace.Content
        loading -> LoadedFace.Loading
        else -> LoadedFace.Empty
    }
    AnimatedContent(
        face,
        // Emptied, the list fades out before the message fades in, rather than the two showing at once.
        transitionSpec = {
            if (targetState != LoadedFace.Content && initialState == LoadedFace.Content) fadeIn(tween(EMPTY_FADE_IN_MS, delayMillis = EMPTY_FADE_OUT_MS)) togetherWith fadeOut(tween(EMPTY_FADE_OUT_MS))
            else fadeIn(fade) togetherWith ExitTransition.KeepUntilTransitionsFinished
        },
        label = "loaded",
    ) { shown ->
        // On the page's own colour, so as it fades in it covers what was under it whole.
        when (shown) {
            LoadedFace.Content -> Box(Modifier.fillMaxSize().background(LocalSurfaceLevel.current.fill())) { content() }
            LoadedFace.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(Modifier.size(24.dp)) }
            LoadedFace.Empty -> EmptyState(message)
        }
    }
}

private enum class LoadedFace { Loading, Empty, Content }

/** A tab with nothing to show: [message] alone; scrollable, so it still pulls to refresh. */
@Composable
fun EmptyState(message: StringResource) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillParentMaxSize().padding(horizontal = Space.xl * 2),
                verticalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(message),
                    color = tokens.tx2,
                    style = AppTheme.typography.bodyReading,
                )
            }
        }
    }
}

// Material's fade-through timings, for a list emptied.
private const val EMPTY_FADE_OUT_MS = 90
private const val EMPTY_FADE_IN_MS = 210

/** Says when a refresh failed, as a snackbar in the app's host. */
@Composable
fun ShowRefreshErrors(container: AppContainer) {
    val show = rememberShowMessage()
    val error by container.repository.error.collectAsStateWithLifecycle()

    LaunchedEffect(error) {
        error?.let { show(textOf(Res.string.message_refresh_failed, failureReason(it))) }
    }
}

/**
 * Pull to refresh that shows its indicator only for a refresh pulled for: the refreshes the
 * app makes by itself, on start, go on without it. Without an API to read it is [off], and the
 * list doesn't pull at all.
 */
class PulledRefresh(val refreshing: Boolean, val off: Boolean, val onRefresh: () -> Unit)

/** A tab's list under its top app bar, pulled down to refresh, the indicator coming down from the bar. */
@Composable
fun TabRefreshBox(pull: PulledRefresh, content: @Composable BoxScope.() -> Unit) {
    if (pull.off) return Box(Modifier.fillMaxSize(), content = content)
    PullToRefreshBox(isRefreshing = pull.refreshing, onRefresh = pull.onRefresh, modifier = Modifier.fillMaxSize(), content = content)
}

@Composable
fun rememberPulledRefresh(container: AppContainer): PulledRefresh {
    val syncing by container.repository.syncing.collectAsStateWithLifecycle()
    var pulled by remember { mutableStateOf(false) }
    // Over once the refresh it started is.
    LaunchedEffect(syncing) { if (!syncing) pulled = false }
    return PulledRefresh(pulled, off = !container.settings.connected) {
        pulled = true
        container.refresh()
    }
}
