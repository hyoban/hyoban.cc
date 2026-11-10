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
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.ledger.summarize
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
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.format.daysBetween
import cc.hyoban.moments.ui.format.formatDate
import java.time.LocalDate

// The ledger on the timeline: the bills of the books shown, those written with a Moment in its
// card and the rest in groups between the Moments of their day, and each day's spending beside
// its date. Only what was spent and earned; transfers and the like stay in the ledger's pages.

/** The bills the timeline shows, by where they go, and what each day spent, in the book's currency. */
class TimelineBills(
    val ledger: Ledger = Ledger(),
    /** Bills written with a Moment, by its uid, oldest first as its card lists them. */
    val linked: Map<String, List<Txn>> = emptyMap(),
    /** The rest, by day, newest first. */
    val loose: Map<String, List<Txn>> = emptyMap(),
    /** What each day spent, `YYYY-MM-DD` to cents, in [currency]. */
    val spent: Map<String, Long> = emptyMap(),
    val currency: String = "CNY",
    /** The car's charges, from the API's entries, by day. */
    val charges: Map<String, List<Charge>> = emptyMap(),
    /** The devices bought and the subscriptions paid, by day. */
    val things: Map<String, List<ThingRecord>> = emptyMap(),
    /** The check-ins with neither words nor photos, by day. */
    val checkins: Map<String, List<Checkin>> = emptyMap(),
) {
    /** What of it falls on [date] alone, as a day's page shows it; the bills linked to Moments stay whole. */
    fun onDay(date: String) = TimelineBills(
        ledger, linked, loose.filterKeys { it == date }, spent.filterKeys { it == date }, currency,
        charges.filterKeys { it == date }, things.filterKeys { it == date }, checkins.filterKeys { it == date },
    )

    companion object {
        val EMPTY = TimelineBills()
    }
}

/** A charge on the timeline: when it started, AC or DC, how much went in and for how long. */
data class Charge(val uid: String, val at: String, val dc: Boolean, val kwh: Double?, val minutes: Int?)

/**
 * A device bought or a subscription paid on the timeline: known by its day alone, so it has no
 * time; its name and price, and whether it was [bought] (a device, software bought once) or
 * renewed.
 */
data class ThingRecord(val uid: String, val day: String, val device: Boolean, val bought: Boolean, val name: String, val price: Double, val currency: String)

/** Opens a device's or a subscription's sheet on its page, by its entry's uid. */
val LocalThingNav = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** What the timeline shows, as its filter row chooses: everything, or one plugin's records. */
enum class FeedKind { ALL, MOMENTS, BILLS, CHARGES, CHECKINS }

/**
 * The bills to show beside [moments], of every book the ledger keeps open (all but the archived),
 * whichever the ledger's pages have chosen, on the days [filter] keeps.
 */
@Composable
fun rememberTimelineBills(container: AppContainer, moments: List<MomentRecord>, filter: TimeFilter, kind: FeedKind = FeedKind.ALL): TimelineBills {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val records by container.repository.records.collectAsStateWithLifecycle()
    val entries by container.repository.entries.collectAsStateWithLifecycle()
    val allCheckins by container.repository.checkins.collectAsStateWithLifecycle()
    // Only the records of modules that are on (记录项与公开).
    val off = LocalSettings.current.modulesOff
    return remember(ledger, records, entries, allCheckins, moments, filter, kind, off) {
        val inFilter = { day: String ->
            (filter.year == null || day.take(4).toInt() == filter.year) && (filter.month == null || day.substring(5, 7).toInt() == filter.month)
        }
        val things = if (kind != FeedKind.ALL || Module.THINGS.key in off) emptyMap() else (
            cc.hyoban.moments.data.things.Things.devices(entries.values).map { ThingRecord(it.uid, it.bought.toString(), true, true, it.name, it.price, "CNY") } +
                cc.hyoban.moments.data.things.Things.subscriptions(entries.values).map {
                    ThingRecord(it.uid, it.paid.toString(), false, it.period == cc.hyoban.moments.data.things.Period.ONCE, it.name, it.price, it.currency)
                }
            ).filter { inFilter(it.day) }.groupBy { it.day }
        val charges = if ((kind != FeedKind.ALL && kind != FeedKind.CHARGES) || Module.CAR.key in off) emptyMap() else records.entries.values.mapNotNull { entry ->
            val charge = (entry["parts"] as? kotlinx.serialization.json.JsonObject)?.get("charge") as? kotlinx.serialization.json.JsonObject
                ?: return@mapNotNull null
            val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val text = { key: String -> (charge[key] as? kotlinx.serialization.json.JsonPrimitive)?.content }
            Charge(
                (entry["uid"] as kotlinx.serialization.json.JsonPrimitive).content,
                at,
                text("type") == "DC",
                text("energyWh")?.toDoubleOrNull()?.div(1000),
                cc.hyoban.moments.data.spec.Entries.chargeMinutes(charge)?.toInt(),
            )
        }.filter { charge ->
            (filter.year == null || charge.at.take(4).toInt() == filter.year) &&
                (filter.month == null || charge.at.substring(5, 7).toInt() == filter.month)
        }.groupBy { it.at.take(10) }
        // The phone's own too, an import's waiting to be published among them.
        val checkins = if ((kind != FeedKind.ALL && kind != FeedKind.CHECKINS) || Module.CHECKIN.key in off) emptyMap() else allCheckins.byDay.filterKeys(inFilter)
        val book = ledger.book
        if (book == null || (kind != FeedKind.ALL && kind != FeedKind.BILLS) || Module.LEDGER.key in off) {
            return@remember TimelineBills(ledger, emptyMap(), emptyMap(), emptyMap(), book?.currency ?: "CNY", charges, things, checkins)
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
            things = things,
            checkins = checkins,
        )
    }
}

/** What the timeline lists of the ledger: money out and in, and money back. */
val SHOWN_KINDS = setOf(TxnKind.EXPENSE, TxnKind.INCOME, TxnKind.REFUND, TxnKind.REIMBURSEMENT)

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

    data class ThingItem(val thing: ThingRecord) : DayItem {
        override val key get() = "thing-${thing.uid}"
    }

    data class CheckinItem(val checkin: Checkin) : DayItem {
        override val key get() = "checkin-${checkin.uid}"
    }

    /** The day's footprints, after everything else of the day. */
    data class Track(val day: String) : DayItem {
        override val key get() = "track-$day"
    }
}

/** A charge as a row on the page, as a bill is: its time first, then AC or DC with how much and how long under it. */
@Composable
fun ChargeRow(charge: Charge, modifier: Modifier = Modifier) {
    PageRow(
        leading = { Icon(AppIcons.Ev, contentDescription = null) },
        title = org.jetbrains.compose.resources.stringResource(Res.string.timeline_charge),
        supporting = listOfNotNull(
            org.jetbrains.compose.resources.stringResource(if (charge.dc) Res.string.charge_dc else Res.string.charge_ac),
            charge.kwh?.let { "%.1f kWh".format(it) },
            charge.minutes?.let { org.jetbrains.compose.resources.pluralStringResource(Res.plurals.charge_minutes, it, it) },
        ).joinToString(" "),
        onClick = null,
        modifier = modifier.testTag("charge_${charge.uid}"),
        time = charge.at.drop(11).take(5),
    )
}

/** A check-in as a row on the page: its time, and the venue it was at, or on a page of that place its day; a tap opens its sheet. */
@Composable
fun CheckinRow(checkin: Checkin, modifier: Modifier = Modifier, withDate: Boolean = false) {
    val open = LocalCheckinNav.current
    PageRow(
        leading = { Icon(AppIcons.Pin, contentDescription = null) },
        title = checkinTitle(checkin, withDate),
        supporting = if (checkin.private) stringResource(Res.string.visibility_private) else null,
        onClick = open?.let { { it(checkin.uid) } },
        modifier = modifier.testTag("checkin_${checkin.uid}"),
        time = formatTime(checkin.at).orEmpty(),
    )
}

/** `签到 面馆`, or on its place's own list `10月12日 签到`. */
@Composable
fun checkinTitle(checkin: Checkin, withDate: Boolean = false): String =
    if (withDate) stringResource(Res.string.timeline_checkin_on, formatDate(checkin.at.take(10))) else stringResource(Res.string.timeline_checkin, checkin.venue)

/** How many Moments and check-ins a place has, the Moments said even when none unless it has check-ins alone. */
@Composable
fun placeCounts(moments: Int, checkins: Int): List<String> = listOfNotNull(
    pluralStringResource(Res.plurals.count_moments, moments, moments).takeIf { moments > 0 || checkins == 0 },
    pluralStringResource(Res.plurals.count_checkins, checkins, checkins).takeIf { checkins > 0 },
)

/** A device bought or a subscription paid as a row on the page: no time, its name, its price at the end. */
@Composable
fun ThingRow(thing: ThingRecord, modifier: Modifier = Modifier) {
    val open = LocalThingNav.current
    PageRow(
        leading = { Icon(if (thing.device) AppIcons.Laptop else AppIcons.Subscription, contentDescription = null) },
        title = thingTitle(thing),
        supporting = cc.hyoban.moments.ui.things.priceOf(thing.price, thing.currency),
        onClick = open?.let { { it(thing.uid) } },
        modifier = modifier.testTag("thing_${thing.uid}"),
        time = "",
    )
}

/** `买了 OPPO Find X10`, `续费 ChatGPT Plus`. */
@Composable
fun thingTitle(thing: ThingRecord): String =
    org.jetbrains.compose.resources.stringResource(if (thing.bought) Res.string.timeline_bought else Res.string.timeline_renewed, thing.name)

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
            if (large) trailing()
        }
        if (!large) Column(horizontalAlignment = Alignment.End, content = trailing)
    }
}

/** A bill as a row on the timeline's page: its time, its category's icon, its name and what sets it apart, and its amount. */
@Composable
fun BillRow(txn: Txn, ledger: Ledger, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PageRow(
        leading = { Icon(txnIcon(txn, ledger), contentDescription = null) },
        title = txnTitle(txn, ledger),
        supporting = txnSupporting(txn, ledger, withTime = false, place = cc.hyoban.moments.footprints.footprintPlace(txn)),
        onClick = onClick,
        modifier = modifier.testTag("bill_${txn.id}"),
        time = txn.local.format(TIME),
    ) {
        Text(signedMoney(txn.amount, txn.currency, txn.incoming), style = AppTheme.typography.body, color = amountColor(txn))
    }
}

/**
 * [moments], [bills] and [charges] of one day merged by the time of day, newest first or, with
 * [oldestFirst], from the morning, bills next to each other in one group read the same way.
 */
fun dayItems(
    moments: List<MomentRecord>,
    bills: List<Txn>,
    charges: List<Charge> = emptyList(),
    things: List<ThingRecord> = emptyList(),
    checkins: List<Checkin> = emptyList(),
    oldestFirst: Boolean = false,
): List<DayItem> {
    // A device or a subscription has its day alone, so it comes first in the day.
    val timed = moments.map { it.occurredAt.drop(11).take(8) to DayItem.Moment(it) as Any } +
        bills.map { it.local.toLocalTime().toString().take(8) to it as Any } +
        charges.map { it.at.drop(11).take(8) to DayItem.ChargeItem(it) as Any } +
        checkins.map { it.at.drop(11).take(8) to DayItem.CheckinItem(it) as Any } +
        things.map { "" to DayItem.ThingItem(it) as Any }
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
    return if (!oldestFirst) out else out.asReversed().map { if (it is DayItem.Bills) DayItem.Bills(it.txns.asReversed()) else it }
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
    /** Each day read, null for one that didn't move, which a ConcurrentHashMap couldn't hold. */
    val cache: MutableMap<String, DayTrack?> = java.util.Collections.synchronizedMap(HashMap())
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
    // With the footprints turned off (记录项与公开), no day has any.
    if (!LocalSettings.current.isOn(Module.FOOTPRINTS)) return TimelineTracks.EMPTY
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
        title = org.jetbrains.compose.resources.stringResource(Res.string.timeline_track),
        supporting = org.jetbrains.compose.resources.pluralStringResource(Res.plurals.timeline_track_moving, minutes, minutes),
        onClick = onClick,
        // The day's page grows out of the row and shrinks back into it.
        modifier = modifier.testTag("track_$day"),
        // No time of its own, the column kept so its glyph stays in line with the bills' icons.
        time = "",
    ) {
        Text(
            org.jetbrains.compose.resources.stringResource(Res.string.distance_kilometres, decimals(shown.metres / 1000, 1)),
            style = AppTheme.typography.body,
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
