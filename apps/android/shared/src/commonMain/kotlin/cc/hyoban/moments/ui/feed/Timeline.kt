package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import kotlin.math.roundToInt
import cc.hyoban.moments.ui.ledger.TIME
import cc.hyoban.moments.ui.ledger.amountColor
import cc.hyoban.moments.ui.ledger.incoming
import cc.hyoban.moments.ui.ledger.signedMoney
import cc.hyoban.moments.ui.ledger.txnIcon
import cc.hyoban.moments.ui.ledger.txnSupporting
import cc.hyoban.moments.ui.ledger.txnTitle
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.trips.tripSummary
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDate
import java.time.LocalDate


/** Opens a device's or a subscription's sheet on its page, by its entry's uid. */
val LocalThingNav = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * The bills to show beside [moments], of every book the ledger keeps open (all but the archived),
 * whichever the ledger's pages have chosen, on the days [filter] keeps.
 */
@Composable
fun rememberTimelineRecords(container: AppContainer, moments: List<MomentRecord>, filter: TimeFilter): TimelineRecords {
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    // Only the records of modules that are on (记录项与公开), each joined by its plugin.
    val joins = rememberJoinInputs(container)
    val off = LocalSettings.current.modulesOff
    val make: () -> TimelineRecords = {
        val span = JoinSpan.Days(filter)
        val own = cc.hyoban.moments.plugins.Plugins.registry.contributions(cc.hyoban.moments.plugins.Points.dayRecords).filter { c -> c.module?.let { it.key !in off } ?: true }
            .flatMap { it.item.records(DayRecordsScope(entries.values) { day -> span.keep(day) }) }.groupBy { it.first }.mapValues { (_, day) -> day.map { it.second } }
        joinRecords(JoinScope(span, moments), joins).build(own)
    }
    // Worked out in the frame for the first time and for a change of what is shown, so the page never
    // shows another's bills; when the records or the ledger change under it (each arriving on its own
    // as the app opens), off the main thread, the bills worked out last shown until then.
    val inputs = arrayOf<Any?>(*joins.map { it.second }.toTypedArray(), entries, moments, filter, off)
    val kept = remember(*inputs) { keptBills.find(*inputs) }
    val shown = remember(filter, off) { mutableStateOf(kept ?: keptBills.get(*inputs, make = make)) }
    LaunchedEffect(*inputs) {
        shown.value = kept ?: keptBills.find(*inputs) ?: withContext(Dispatchers.Default) { make() }.also { keptBills.put(it, *inputs) }
    }
    return kept ?: shown.value
}

private val keptBills = Kept<TimelineRecords>(4)

/**
 * The last few results worked out from the same inputs, kept past the composition that made them.
 * A page left and come back to, a Moment opened over it or another tab, is composed afresh, and
 * would otherwise work out every day's bills and rows again in the frame it comes back in. Only
 * read and written on the one thread that composes.
 */
internal class Kept<T>(private val size: Int) {
    private val kept = ArrayDeque<Pair<List<Any?>, T>>()

    fun get(vararg inputs: Any?, make: () -> T): T = find(*inputs) ?: make().also { put(it, *inputs) }

    fun find(vararg inputs: Any?): T? {
        val key = inputs.asList()
        return kept.firstOrNull { it.first == key }?.second
    }

    fun put(value: T, vararg inputs: Any?) {
        kept.addFirst(inputs.asList() to value)
        if (kept.size > size) kept.removeLast()
    }
}

/** When a Moment was, `15:04`, or the while it lasted, `15:04–16:30`. */
fun momentTime(moment: cc.hyoban.moments.data.spec.MomentRecord): String? = timeSpan(moment.occurredAt, moment.end)

/** `15:04`, or `15:04–16:30` with an [end]. */
fun timeSpan(at: String, end: String?): String? =
    formatTime(at)?.let { start -> end?.let(::formatTime)?.let { "$start–$it" } ?: start }

/** How many records a place has, its Moments and the modules' stops there (check-ins) counted as one. */
@Composable
fun placeCounts(records: Int): List<String> = listOf(pluralStringResource(Res.plurals.count_records, records, records))

/**
 * A row on the timeline's page, for what has no words of its own: an icon at the Moments' edge,
 * a title and a line under it, and figures at the end; pressed, its wash reaches a little past
 * its edges. It stands as tall as a touch target, so rows one after another sit evenly. With a
 * [time] (empty for what has none), the time leads in a column of its own, as the records
 * bubble has it, so a day's rows read down by the hour.
 */
@Composable
fun PageRow(
    leading: @Composable () -> Unit,
    title: String,
    supporting: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    time: String? = null,
    /** What goes under the supporting line, a tag. */
    below: @Composable () -> Unit = {},
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier
            .bleed(Space.s)
            .clip(AppTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.s, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (time != null) {
            // As wide as 00:00, the system's monospace not always keeping its digits one width.
            Box {
                Text("00:00", style = AppTheme.typography.caption, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                Text(time, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1)
            }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides tokens.tx2) { leading() }
        }
        // With the system's font set large the figures go under the words, which take the row's
        // width and two lines each, rather than a column at the end leaving them a word or two.
        val large = cc.hyoban.moments.ui.theme.isLargeFont()
        val lines = if (large) 2 else 1
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.typography.body, maxLines = lines, overflow = TextOverflow.Ellipsis)
            supporting?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = AppTheme.typography.caption, color = tokens.tx2, maxLines = lines, overflow = TextOverflow.Ellipsis)
            }
            below()
            if (large) trailing()
        }
        if (!large) Column(horizontalAlignment = Alignment.End, content = trailing)
    }
}

/** When an ISO date and time was, in epoch milliseconds; one written without an offset is read in this device's zone. */
internal fun instantOf(at: String): Long? = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()
    ?: runCatching { java.time.LocalDateTime.parse(at).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

/** Out past its edges by [by] each side, so a list row's own inset lines its content up with what is around it. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

/**
 * Where a trip begins on the timeline (its last day, as the newest come first): a row on the
 * page, its route icon, its name, its dates and length, and an arrow; it opens the trip's
 * page when tapped.
 */
@Composable
fun TripBanner(trip: TripRecord, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = Space.m)
            .testTag("trip_${trip.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(AppIcons.Route, contentDescription = null, tint = tokens.tx2)
        Column(Modifier.weight(1f)) {
            Text(trip.title, style = AppTheme.typography.body.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tripBannerSummary(trip, count), style = AppTheme.typography.caption, color = tokens.tx2, maxLines = 1)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(20.dp))
    }
}

/**
 * A trip's line under its name: its dates, days and Moments, or, while it goes on, the state first
 * (in the line's own colour, as the word says it), the day it began and which day this is.
 */
@Composable
private fun tripBannerSummary(trip: TripRecord, count: Int): AnnotatedString {
    val today = LocalDate.now()
    val ongoing = runCatching { !today.isBefore(LocalDate.parse(trip.start)) && !today.isAfter(LocalDate.parse(trip.end)) }.getOrDefault(false)
    if (!ongoing) return AnnotatedString(tripSummary(trip, count))
    val day = daysBetween(trip.start, today.toString())
    return AnnotatedString(
        listOf(
            stringResource(Res.string.trip_ongoing),
            stringResource(Res.string.trip_since, formatDate(trip.start)),
            stringResource(Res.string.trip_day, day),
            pluralStringResource(Res.plurals.count_moments, count, count),
        ).joinToString(" "),
    )
}

/** A stretch of a day's footprints between two of its records: when it began and ended, its line in lon/lat, and how far and long it moved. */
class TrackPiece(val start: Long, val end: Long, val stretches: List<List<Pair<Double, Double>>>, val metres: Double, val movingMillis: Long)

/**
 * A day's footprints as the timeline reads them: the line thinned to a few hundred samples, each
 * with the distance and moving time from the day's start worked out from every fix, so the day
 * can be cut at any of its records' times and each piece keeps its own figures.
 */
class DayTrack(
    private val times: LongArray,
    private val lons: DoubleArray,
    private val lats: DoubleArray,
    private val metresSoFar: DoubleArray,
    private val movingSoFar: LongArray,
    /** Whether a sample carries on the line from the one before it, rather than starting a new stretch. */
    private val joined: BooleanArray,
) {
    val metres: Double get() = metresSoFar.lastOrNull() ?: 0.0
    val movingMillis: Long get() = movingSoFar.lastOrNull() ?: 0L

    /**
     * The day cut at [cuts] (epoch milliseconds), oldest first: one piece before the first cut, one
     * between each two and one after the last, a fix at a cut going with the piece after it. A piece
     * that moved too little to list is left out.
     */
    fun pieces(cuts: List<Long>): List<TrackPiece> {
        val bounds = (listOf(Long.MIN_VALUE) + cuts.sorted().distinct() + Long.MAX_VALUE)
        var i = 0
        return bounds.zipWithNext().mapNotNull { (from, to) ->
            while (i < times.size && times[i] < from) i++
            val first = i
            while (i < times.size && times[i] < to) i++
            piece(first, i)
        }
    }

    private fun piece(from: Int, until: Int): TrackPiece? {
        if (until - from < 2) return null
        val stretches = mutableListOf<MutableList<Pair<Double, Double>>>()
        var metres = 0.0
        var moving = 0L
        for (k in from until until) {
            if (k == from || !joined[k]) stretches += mutableListOf<Pair<Double, Double>>()
            else {
                metres += metresSoFar[k] - metresSoFar[k - 1]
                moving += movingSoFar[k] - movingSoFar[k - 1]
            }
            stretches.last() += lons[k] to lats[k]
        }
        val lines = stretches.filter { it.size > 1 }
        // A piece with no stretch that moved, the phone only lying still, lists nothing.
        if (lines.isEmpty() || metres < MIN_TRACK_METRES) return null
        return TrackPiece(times[from], times[until - 1], lines, metres, moving)
    }

    companion object {
        /** [points] of one day, oldest first, thinned to about [budget] samples, every break and each stretch's ends kept. */
        fun of(points: List<cc.hyoban.moments.footprints.TrackPoint>, budget: Int = 600): DayTrack? {
            if (points.size < 2) return null
            val stride = maxOf(1, kotlin.math.ceil(points.size.toDouble() / budget).toInt())
            val kept = ArrayList<Int>()
            var metres = 0.0
            var moving = 0L
            val metresAt = DoubleArray(points.size)
            val movingAt = LongArray(points.size)
            val joinedAt = BooleanArray(points.size)
            points.forEachIndexed { index, point ->
                val prev = points.getOrNull(index - 1)
                val linked = prev != null && cc.hyoban.moments.footprints.connected(prev, point)
                if (linked) {
                    metres += cc.hyoban.moments.footprints.distanceMetres(prev!!, point)
                    moving += point.time - prev.time
                }
                metresAt[index] = metres
                movingAt[index] = moving
                joinedAt[index] = linked
                val next = points.getOrNull(index + 1)
                val ends = !linked || next == null || !cc.hyoban.moments.footprints.connected(point, next)
                if (ends || index % stride == 0) kept += index
            }
            if (metres < MIN_TRACK_METRES) return null
            // A kept sample joins the one kept before it only if every fix between them was joined too.
            val joined = BooleanArray(kept.size) { k -> k > 0 && (kept[k - 1] + 1..kept[k]).all { joinedAt[it] } }
            return DayTrack(
                LongArray(kept.size) { points[kept[it]].time },
                DoubleArray(kept.size) { points[kept[it]].longitude },
                DoubleArray(kept.size) { points[kept[it]].latitude },
                DoubleArray(kept.size) { metresAt[kept[it]] },
                LongArray(kept.size) { movingAt[kept[it]] },
                joined,
            )
        }
    }
}

/** Less than this and the footprints, a day's or a piece's, list nothing. */
private const val MIN_TRACK_METRES = 100.0

/**
 * The footprints on the timeline: the days that have any, and each day's line and figures, read
 * from the phone when the day comes into view and kept; today's again as it is recorded. What is
 * kept is state, so the timeline lays a day out again, its pieces between its records, once read.
 */
class TimelineTracks(
    val days: Set<String>,
    private val cache: MutableMap<String, DayTrack?>,
    /** Days whose footprints have grown since they were read, read again while the old ones still show. */
    private val stale: MutableSet<String> = mutableSetOf(),
    private val read: (String) -> DayTrack?,
    /**
     * Whether the days with footprints, and the newest days' lines, have been read: until then
     * the timeline waits, as its records bubbles would grow a line each as they came.
     */
    val ready: Boolean = true,
) {
    fun cached(day: String): DayTrack? = cache[day]

    /** Whether [day] has footprints still to be read, or read again. */
    fun unread(day: String): Boolean = day in days && (day in stale || !cache.containsKey(day))

    suspend fun load(day: String): DayTrack? {
        if (day !in stale && cache.containsKey(day)) return cache[day]
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { read(day) }.also {
            cache[day] = it
            stale -= day
        }
    }

    companion object {
        val EMPTY = TimelineTracks(emptySet(), mutableMapOf(), read = { null })
    }
}

/**
 * The timeline's footprints, kept for as long as the app runs, so the timeline, shown again after
 * another tab or a page, comes back with its rows as it left them rather than reading them again
 * and moving its rows as they arrive.
 */
private object TrackMemory {
    /** Each day read, null for one that didn't move; state, so what shows it follows a read. */
    val cache: MutableMap<String, DayTrack?> = androidx.compose.runtime.mutableStateMapOf()
    val stale: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    var days: Set<String> = emptySet()

    /** Whether [days] and the newest days' lines have been read once. */
    @Volatile
    var ready = false

    /** The store's revision the days and today's line were last read at. */
    @Volatile
    var revision = -1L
}

/** Whether the timeline has seen footprints on [day], for a day's page to keep room for its map while they are read. */
fun knownFootprints(day: String): Boolean = day in TrackMemory.days

/** The timeline's figures for [day], how far and how long it moved, if it has read them. */
fun knownDayTrack(day: String): DayTrack? = TrackMemory.cache[day]

@Composable
fun rememberTimelineTracks(container: AppContainer): TimelineTracks {
    // Each day's movement as the modules that are on keep it (记录项与公开); with none, no day has any.
    val settings = LocalSettings.current
    val store = cc.hyoban.moments.plugins.Plugins.dayTracks(container) { settings.isOn(it) } ?: return TimelineTracks.EMPTY
    val revision by store.revision.collectAsStateWithLifecycle()
    val read: (String) -> DayTrack? = remember(store) {
        { day ->
            DayTrack.of(store.points(cc.hyoban.moments.footprints.TrackSelection(day = day)))
        }
    }
    val days by androidx.compose.runtime.produceState(TrackMemory.days.takeIf { TrackMemory.ready }, revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val days = store.days().toSet()
            // The first time, the newest days' lines too, which the first screen shows, so the
            // timeline opens with them in their bubbles rather than the bubbles growing after.
            if (!TrackMemory.ready) {
                days.sortedDescending().take(PRELOADED_DAYS).filter { !TrackMemory.cache.containsKey(it) }.forEach { TrackMemory.cache[it] = read(it) }
            }
            days
        }.also {
            TrackMemory.days = it
            TrackMemory.ready = true
        }
    }
    return remember(days, revision) {
        // Only today's footprints grow, and only when something was recorded since they were read;
        // every other day's are as they were read.
        if (revision != TrackMemory.revision) {
            TrackMemory.revision = revision
            TrackMemory.stale += java.time.LocalDate.now().toString()
        }
        TimelineTracks(days.orEmpty(), TrackMemory.cache, TrackMemory.stale, read, ready = days != null)
    }
}

/** How many of the newest days with footprints are read before the timeline first shows. */
private const val PRELOADED_DAYS = 7

/** A piece of a day's footprints as a row between its records: the line itself where a bill's icon goes, when it set off, how far it went and for how long it moved; tapped for the day. */
@Composable
fun TrackRow(day: String, piece: TrackPiece, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val minutes = (piece.movingMillis / 60_000).toInt()
    PageRow(
        leading = { TrackGlyph(piece.stretches, Modifier.size(24.dp), tokens.tx2) },
        title = org.jetbrains.compose.resources.stringResource(Res.string.timeline_track),
        supporting = org.jetbrains.compose.resources.pluralStringResource(Res.plurals.timeline_track_moving, minutes, minutes),
        onClick = onClick,
        modifier = modifier.testTag("track_${day}_${piece.start}"),
        time = trackTime(piece),
    ) {
        Text(
            org.jetbrains.compose.resources.stringResource(Res.string.distance_kilometres, decimals(piece.metres / 1000, 1)),
            style = AppTheme.typography.body,
            color = tokens.tx2,
        )
    }
}

/** When a piece of the footprints set off, as the records' times read. */
fun trackTime(piece: TrackPiece): String =
    java.time.Instant.ofEpochMilli(piece.start).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)

/** The day's line, where a bill's icon goes, drawn in an icon's stroke. */
@Composable
fun TrackGlyph(stretches: List<List<Pair<Double, Double>>>, modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val all = stretches.flatten()
        // Longitude shrunk by the latitude, so the line keeps the shape it has on a map.
        val scaleX = kotlin.math.cos(Math.toRadians(all.sumOf { it.second } / all.size))
        val minX = all.minOf { it.first } * scaleX
        val maxX = all.maxOf { it.first } * scaleX
        val minY = all.minOf { it.second }
        val maxY = all.maxOf { it.second }
        val stroke = 2.dp.toPx()
        val box = size.minDimension - stroke
        val span = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1.0
        val offsetX = (size.width - (maxX - minX) / span * box) / 2
        val offsetY = (size.height - (maxY - minY) / span * box) / 2
        stretches.forEach { stretch ->
            val path = androidx.compose.ui.graphics.Path()
            stretch.forEachIndexed { index, (lon, lat) ->
                val x = (offsetX + (lon * scaleX - minX) / span * box).toFloat()
                val y = (offsetY + (maxY - lat) / span * box).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}
