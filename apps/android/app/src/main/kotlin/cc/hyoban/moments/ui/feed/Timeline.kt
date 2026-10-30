package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.ui.design.AppIcons
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import cc.hyoban.moments.ui.design.HorizontalDivider
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
import cc.hyoban.moments.ui.components.tripCardKey
import cc.hyoban.moments.ui.ledger.TxnRow
import cc.hyoban.moments.ui.ledger.money
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.dividerColor
import cc.hyoban.moments.ui.theme.fill
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

/** The bills to show beside [moments], of the books the ledger shows, on the days [filter] keeps. */
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
        val shown = ledger.shownBooks
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

/** A charge as a row on the page: its icon, AC or DC and how much, and when and how long in figures. */
@Composable
fun ChargeRow(charge: Charge, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("charge_${charge.uid}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(AppIcons.Ev, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(
                    androidx.compose.ui.res.stringResource(if (charge.dc) cc.hyoban.moments.R.string.charge_dc else cc.hyoban.moments.R.string.charge_ac),
                    charge.kwh?.let { "%.1f kWh".format(it) },
                ).joinToString("\u2003"),
                style = AppTheme.typography.body,
            )
            Text(
                listOfNotNull(charge.at.drop(11).take(5).ifEmpty { null }, charge.minutes?.let { "$it min" }).joinToString("\u2003"),
                style = AppTheme.typography.numCaption,
                color = AppTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/** Bills next to each other on the timeline: rows on the page, hairlines between them, lined up with the Moments' words. */
@Composable
fun BillGroup(txns: List<Txn>, ledger: Ledger, onBill: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        txns.forEachIndexed { index, txn ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = dividerColor())
            TxnRow(txn, ledger, onClick = { onBill(txn.id) }, modifier = Modifier.bleed(Space.l))
        }
    }
}

/** The bills written with a Moment, at its foot under a hairline. */
@Composable
fun LinkedBills(txns: List<Txn>, ledger: Ledger, onBill: (String) -> Unit) {
    HorizontalDivider(Modifier.padding(top = Space.s), color = dividerColor())
    txns.forEach { txn -> TxnRow(txn, ledger, onClick = { onBill(txn.id) }, modifier = Modifier.bleed(Space.l)) }
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
        Icon(AppIcons.Route, contentDescription = null, tint = AppTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(trip.title, style = AppTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tripSummary(trip, count), style = AppTheme.typography.bodySmall, color = AppTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = AppTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

/** A day's footprints as the timeline lists them: the line, its stretches in lon/lat, and how far and long it moved. */
class DayTrack(val stretches: List<List<Pair<Double, Double>>>, val metres: Double, val movingMillis: Long)

/**
 * The footprints on the timeline: the days that have any, and each day's line and figures, read
 * from the phone when its row comes into view and kept; today's again as it is recorded.
 */
class TimelineTracks(val days: Set<String>, private val cache: MutableMap<String, DayTrack?>, private val read: (String) -> DayTrack?) {
    fun cached(day: String): DayTrack? = cache[day]

    suspend fun load(day: String): DayTrack? = cache[day] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { read(day) }.also { cache[day] = it }

    companion object {
        val EMPTY = TimelineTracks(emptySet(), mutableMapOf()) { null }
    }
}

@Composable
fun rememberTimelineTracks(container: AppContainer): TimelineTracks {
    val store = container.footprints
    val revision by store.revision.collectAsStateWithLifecycle()
    val cache = remember { java.util.concurrent.ConcurrentHashMap<String, DayTrack?>() }
    val days by androidx.compose.runtime.produceState(emptySet<String>(), revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.days().toSet() }
    }
    return remember(days, revision) {
        // Only today's footprints grow; every other day's are as they were read.
        cache.remove(java.time.LocalDate.now().toString())
        TimelineTracks(days, cache) { day ->
            val points = store.points(cc.hyoban.moments.footprints.TrackSelection(day = day))
            val stretches = cc.hyoban.moments.footprints.trackSegments(points, budget = 400).filter { it.size > 1 }
            val stats = cc.hyoban.moments.footprints.trackStats(points)
            // A day with no stretch that moved, the phone only lying still, lists nothing.
            if (stretches.isEmpty() || stats.metres < 100) null
            else DayTrack(stretches.map { stretch -> stretch.map { it.longitude to it.latitude } }, stats.metres, stats.recordedMillis)
        }
    }
}

/** A day's footprints as a row after its bills: the line itself where a bill's icon goes, how far it went and for how long it moved; tapped for the day. */
@Composable
fun TrackRow(day: String, tracks: TimelineTracks, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val track by androidx.compose.runtime.produceState(tracks.cached(day), day, tracks) { value = tracks.load(day) }
    val shown = track ?: return
    val minutes = (shown.movingMillis / 60_000).toInt()
    cc.hyoban.moments.ui.design.ListItem(
        headlineContent = { Text(androidx.compose.ui.res.stringResource(cc.hyoban.moments.R.string.timeline_track), maxLines = 1) },
        supportingContent = { Text(androidx.compose.ui.res.pluralStringResource(cc.hyoban.moments.R.plurals.timeline_track_moving, minutes, minutes), maxLines = 1) },
        leadingContent = { TrackGlyph(shown.stretches) },
        trailingContent = {
            Text(
                androidx.compose.ui.res.stringResource(cc.hyoban.moments.R.string.distance_kilometres, shown.metres / 1000),
                style = AppTheme.typography.titleSmall,
            )
        },
        colors = cc.hyoban.moments.ui.design.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = modifier.bleed(Space.l).clickable(onClick = onClick).testTag("track_$day"),
    )
}

/** The day's line, fitted into a bill icon's round, drawn in an icon's stroke. */
@Composable
private fun TrackGlyph(stretches: List<List<Pair<Double, Double>>>) {
    val color = cc.hyoban.moments.ui.theme.tokens.tx2
    Box(
        Modifier.size(40.dp).background(cc.hyoban.moments.ui.theme.tokens.pressed, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
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
}
