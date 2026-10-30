package cc.hyoban.moments.ui.feed

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.TripRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.summarize
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.components.dayTrackKey
import cc.hyoban.moments.ui.components.tripCardKey
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

// The ledger on the timeline: the bills of the books shown, those written with a Moment in its
// card and the rest in groups between the Moments of their day, and each day's spending beside
// its date. Only what was spent and earned; transfers and the like stay in the ledger's pages.

/** The bills the timeline shows, by where they go, and what each day spent, in the book's currency. */
class TimelineBills(
    val ledger: Ledger,
    /** Bills written with a Moment, by its uid, oldest first as its card lists them. */
    val linked: Map<String, List<Txn>>,
    /** The rest, by day, newest first. */
    val loose: Map<String, List<Txn>>,
    /** What each day spent, `YYYY-MM-DD` to cents, in [currency]. */
    val spent: Map<String, Long>,
    val currency: String,
    /** The car's charges, from the API's second shape, by day. */
    val charges: Map<String, List<Charge>> = emptyMap(),
) {
    companion object {
        val EMPTY = TimelineBills(Ledger(), emptyMap(), emptyMap(), emptyMap(), "CNY")
    }
}

/** A charge on the timeline: when it started, AC or DC, how much went in and for how long. */
data class Charge(val uid: String, val at: String, val dc: Boolean, val kwh: Double?, val minutes: Int?)

/** What the timeline shows, as its filter row chooses: everything, or one plugin's records. */
enum class FeedKind { ALL, MOMENTS, BILLS, CHARGES }

/**
 * The bills to show beside [moments], of every book the ledger keeps open (all but the archived),
 * whichever the ledger's pages have chosen, on the days [filter] keeps.
 */
@Composable
fun rememberTimelineBills(container: AppContainer, moments: List<MomentRecord>, filter: TimeFilter, kind: FeedKind = FeedKind.ALL): TimelineBills {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val second by container.repository.second.collectAsStateWithLifecycle()
    return remember(ledger, second, moments, filter, kind) {
        val charges = if (kind != FeedKind.ALL && kind != FeedKind.CHARGES) emptyMap() else second.entries.values.mapNotNull { entry ->
            val charge = (entry["parts"] as? kotlinx.serialization.json.JsonObject)?.get("charge") as? kotlinx.serialization.json.JsonObject
                ?: return@mapNotNull null
            val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val text = { key: String -> (charge[key] as? kotlinx.serialization.json.JsonPrimitive)?.content }
            Charge(
                (entry["uid"] as kotlinx.serialization.json.JsonPrimitive).content,
                at,
                text("type") == "DC",
                text("energyWh")?.toDoubleOrNull()?.div(1000),
                text("durationMin")?.toDoubleOrNull()?.toInt(),
            )
        }.filter { charge ->
            (filter.year == null || charge.at.take(4).toInt() == filter.year) &&
                (filter.month == null || charge.at.substring(5, 7).toInt() == filter.month)
        }.groupBy { it.at.take(10) }
        val book = ledger.book
        if (book == null || (kind != FeedKind.ALL && kind != FeedKind.BILLS)) {
            return@remember TimelineBills(ledger, emptyMap(), emptyMap(), emptyMap(), book?.currency ?: "CNY", charges)
        }
        val shown = ledger.books.filterNot { it.archived }.mapTo(HashSet()) { it.id }
        val uids = moments.mapTo(HashSet()) { it.uid }
        val bills = ledger.txns.filter { txn ->
            txn.book in shown && txn.kind in SHOWN_KINDS &&
                (filter.year == null || txn.date.year == filter.year) &&
                (filter.month == null || txn.date.monthValue == filter.month)
        }
        val (withMoment, without) = bills.partition { it.moment != null && it.moment in uids }
        val ids = bills.mapTo(HashSet()) { it.id }
        val sums = summarize(ledger.entries.filter { it.txn.id in ids }, ledger.rates, book.currency, null, null)
        TimelineBills(
            ledger,
            linked = withMoment.groupBy { it.moment!! }.mapValues { (_, txns) -> txns.sortedBy { it.time } },
            loose = without.groupBy { it.dateKey },
            spent = sums.expenseByDay.mapKeys { it.key.toString() },
            currency = book.currency,
            charges = charges,
        )
    }
}

/** What the timeline lists of the ledger: money out and in, and money back. */
private val SHOWN_KINDS = setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.REFUND, TxnKind.REIMBURSEMENT)

/** One day's spending, for its pill; null when it spent nothing. */
@Composable
fun daySpent(bills: TimelineBills, date: String): String? = bills.spent[date]?.takeIf { it > 0 }?.let { money(it, bills.currency) }

/** What a day holds, in the order the timeline shows it: newest first, the bills between them gathered. */
sealed interface DayItem {
    val key: String

    data class Moment(val moment: MomentRecord) : DayItem {
        override val key get() = moment.uid
    }

    data class Bills(val txns: List<Txn>) : DayItem {
        override val key get() = "bills-${txns.first().id}"
    }

    data class ChargeItem(val charge: Charge) : DayItem {
        override val key get() = "charge-${charge.uid}"
    }

    /** The day's footprints, after everything else of the day. */
    data class Track(val day: String) : DayItem {
        override val key get() = "track-$day"
    }
}

/** A charge as a row on the page, as a bill is: AC or DC with how much and how long under it, and its time at the end. */
@Composable
fun ChargeRow(charge: Charge, modifier: Modifier = Modifier) {
    PageRow(
        leading = { Icon(AppIcons.Ev, contentDescription = null) },
        title = androidx.compose.ui.res.stringResource(cc.hyoban.moments.R.string.timeline_charge),
        supporting = listOfNotNull(
            androidx.compose.ui.res.stringResource(if (charge.dc) cc.hyoban.moments.R.string.charge_dc else cc.hyoban.moments.R.string.charge_ac),
            charge.kwh?.let { "%.1f kWh".format(it) },
            charge.minutes?.let { androidx.compose.ui.res.pluralStringResource(cc.hyoban.moments.R.plurals.charge_minutes, it, it) },
        ).joinToString("\u2003"),
        onClick = null,
        modifier = modifier.testTag("charge_${charge.uid}"),
    ) {
        charge.at.drop(11).take(5).ifEmpty { null }?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3) }
    }
}

/**
 * A row on the timeline's page, for what has no words of its own: an icon at the Moments' edge,
 * a title and a line under it, and figures at the end; pressed, its wash reaches a little past
 * its edges. It stands as tall as a touch target, so rows one after another sit evenly.
 */
@Composable
fun PageRow(
    leading: @Composable () -> Unit,
    title: String,
    supporting: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
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
            if (large) trailing()
        }
        if (!large) Column(horizontalAlignment = Alignment.End, content = trailing)
    }
}

/** A bill as a row on the timeline's page: its category's icon, its name and what sets it apart, and its amount over its time. */
@Composable
fun BillRow(txn: Txn, ledger: Ledger, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PageRow(
        leading = { Icon(txnIcon(txn, ledger), contentDescription = null) },
        title = txnTitle(txn, ledger),
        supporting = txnSupporting(txn, ledger, withTime = false, place = cc.hyoban.moments.footprints.footprintPlace(txn)),
        onClick = onClick,
        modifier = modifier.testTag("bill_${txn.id}"),
    ) {
        Text(signedMoney(txn.amount, txn.currency, txn.incoming), style = AppTheme.typography.numBody, color = amountColor(txn))
        Text(txn.local.format(TIME), style = AppTheme.typography.caption, color = tokens.tx3)
    }
}

/** [moments], [bills] and [charges] of one day merged by the time of day, newest first, bills next to each other in one group. */
fun dayItems(moments: List<MomentRecord>, bills: List<Txn>, charges: List<Charge> = emptyList()): List<DayItem> {
    if (bills.isEmpty() && charges.isEmpty()) return moments.map(DayItem::Moment)
    val timed = moments.map { it.occurredAt.drop(11).take(8) to DayItem.Moment(it) as Any } +
        bills.map { it.local.toLocalTime().toString().take(8) to it as Any } +
        charges.map { it.at.drop(11).take(8) to DayItem.ChargeItem(it) as Any }
    val out = mutableListOf<DayItem>()
    var group = mutableListOf<Txn>()
    for ((_, item) in timed.sortedByDescending { it.first }) {
        if (item is Txn) {
            group += item
        } else {
            if (group.isNotEmpty()) out += DayItem.Bills(group).also { group = mutableListOf() }
            out += item as DayItem
        }
    }
    if (group.isNotEmpty()) out += DayItem.Bills(group)
    return out
}

/** Bills next to each other on the timeline: rows on the page, one under another, lined up with the Moments' words. */
@Composable
fun BillGroup(txns: List<Txn>, ledger: Ledger, onBill: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        txns.forEach { txn -> BillRow(txn, ledger, onClick = { onBill(txn.id) }) }
    }
}

/** The bills written with a Moment, at its foot. */
@Composable
fun LinkedBills(txns: List<Txn>, ledger: Ledger, onBill: (String) -> Unit) {
    Column(Modifier.padding(top = Space.s)) {
        txns.forEach { txn -> BillRow(txn, ledger, onClick = { onBill(txn.id) }) }
    }
}

/** Out past its edges by [by] each side, so a list row's own inset lines its content up with what is around it. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

/**
 * Where a trip begins on the timeline (its last day, as the newest come first): a row on the
 * page, its route icon, its name, its dates and length, and an arrow; it grows into the trip's
 * page when tapped.
 */
@Composable
fun TripBanner(trip: TripRecord, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .sharedCard(tripCardKey(trip.id))
            .clip(AppTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = Space.m)
            .testTag("trip_${trip.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(AppIcons.Route, contentDescription = null, tint = tokens.tx2)
        Column(Modifier.weight(1f)) {
            Text(trip.title, style = AppTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tripSummary(trip, count), style = AppTheme.typography.bodySmall, color = tokens.tx2, maxLines = 1)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(20.dp))
    }
}

/** A day's footprints as the timeline lists them: the line, its stretches in lon/lat, and how far and long it moved. */
class DayTrack(val stretches: List<List<Pair<Double, Double>>>, val metres: Double, val movingMillis: Long)

/**
 * The footprints on the timeline: the days that have any, and each day's line and figures, read
 * from the phone when its row comes into view and kept; today's again as it is recorded.
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
    val cache = java.util.concurrent.ConcurrentHashMap<String, DayTrack?>()
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
    val store = container.footprints
    val revision by store.revision.collectAsStateWithLifecycle()
    val read: (String) -> DayTrack? = remember(store) {
        { day ->
            val points = store.points(cc.hyoban.moments.footprints.TrackSelection(day = day))
            val stretches = cc.hyoban.moments.footprints.trackSegments(points, budget = 400).filter { it.size > 1 }
            val stats = cc.hyoban.moments.footprints.trackStats(points)
            // A day with no stretch that moved, the phone only lying still, lists nothing.
            if (stretches.isEmpty() || stats.metres < 100) null
            else DayTrack(stretches.map { stretch -> stretch.map { it.longitude to it.latitude } }, stats.metres, stats.recordedMillis)
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

/** A day's footprints as a row after its bills: the line itself where a bill's icon goes, how far it went and for how long it moved; tapped for the day. */
@Composable
fun TrackRow(day: String, tracks: TimelineTracks, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val track by androidx.compose.runtime.produceState(tracks.cached(day), day, tracks) { value = tracks.load(day) }
    val shown = track ?: return
    val minutes = (shown.movingMillis / 60_000).toInt()
    PageRow(
        leading = { TrackGlyph(shown.stretches, Modifier.size(24.dp), tokens.tx2) },
        title = androidx.compose.ui.res.stringResource(cc.hyoban.moments.R.string.timeline_track),
        supporting = androidx.compose.ui.res.pluralStringResource(cc.hyoban.moments.R.plurals.timeline_track_moving, minutes, minutes),
        onClick = onClick,
        // The day's page grows out of the row and shrinks back into it.
        modifier = modifier.sharedCard(dayTrackKey(day)).testTag("track_$day"),
    ) {
        Text(
            androidx.compose.ui.res.stringResource(cc.hyoban.moments.R.string.distance_kilometres, shown.metres / 1000),
            style = AppTheme.typography.numBody,
            color = tokens.tx2,
        )
    }
}

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
