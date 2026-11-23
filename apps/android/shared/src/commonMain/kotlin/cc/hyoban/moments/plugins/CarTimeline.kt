package cc.hyoban.moments.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ledger.TxnKind
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.feed.BubbleFoot
import cc.hyoban.moments.ui.feed.BubbleShape
import cc.hyoban.moments.ui.feed.DayAnchor
import cc.hyoban.moments.ui.feed.DayRecord
import cc.hyoban.moments.ui.feed.LocalCheckinNav
import cc.hyoban.moments.ui.feed.PageRow
import cc.hyoban.moments.ui.feed.RecordRow
import cc.hyoban.moments.ui.feed.TAIL
import cc.hyoban.moments.ui.feed.bubbleWidth
import cc.hyoban.moments.ui.feed.instantOf
import cc.hyoban.moments.ui.feed.timeSpan
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.ledger.LocalLedgerNav
import cc.hyoban.moments.ui.ledger.TIME
import cc.hyoban.moments.ui.ledger.amountColor
import cc.hyoban.moments.ui.ledger.billAmount
import cc.hyoban.moments.ui.ledger.txnIcon
import cc.hyoban.moments.ui.ledger.txnSupporting
import cc.hyoban.moments.ui.ledger.txnTitle
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.data.spec.MomentRecord
import kotlin.math.roundToInt

/**
 * A charge on the timeline: when it started, AC or DC, how much went in and for how long, and
 * the [bill] paid for it, the ledger's bill at its very time, shown with it as one line.
 */
data class Charge(val uid: String, val at: String, val dc: Boolean, val kwh: Double?, val minutes: Int?, val bill: Txn? = null)

/** A day the car moved, from its statistics: how far, how long it drove and the energy it used. */
data class DriveDay(val date: String, val km: Double, val minutes: Int?, val kwh: Double?)

/** The car's charges among the API's [entries], on the days [keep] takes. */
fun chargesOf(entries: Collection<kotlinx.serialization.json.JsonObject>, keep: (String) -> Boolean): List<Charge> = entries.mapNotNull { entry ->
    val charge = (entry["parts"] as? kotlinx.serialization.json.JsonObject)?.get("charge") as? kotlinx.serialization.json.JsonObject
        ?: return@mapNotNull null
    val at = (entry["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
    if (!keep(at.take(10))) return@mapNotNull null
    val text = { key: String -> (charge[key] as? kotlinx.serialization.json.JsonPrimitive)?.content }
    Charge(
        (entry["uid"] as kotlinx.serialization.json.JsonPrimitive).content,
        at,
        text("type") == "DC",
        text("energyWh")?.toDoubleOrNull()?.div(1000),
        cc.hyoban.moments.data.spec.Entries.chargeMinutes(charge)?.toInt(),
    )
}

/**
 * The days the car moved among its monthly statistics ([months], the copy's `vehicle_months`), on
 * the days [keep] takes: each day's distance, driving time and energy, every car's summed. A day
 * only parked, using energy standing, isn't one.
 */
fun drivesOf(months: Collection<kotlinx.serialization.json.JsonObject>, keep: (String) -> Boolean): Map<String, DriveDay> {
    val days = mutableMapOf<String, DriveDay>()
    for (month in months) {
        for (day in (month["days"] as? kotlinx.serialization.json.JsonArray).orEmpty()) {
            val fields = day as? kotlinx.serialization.json.JsonObject ?: continue
            val number = { key: String -> (fields[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
            val date = (fields["date"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val metres = number("distanceMeters") ?: 0.0
            if (metres <= 0 || !keep(date)) continue
            val seconds = number("durationSeconds")
            val wh = number("energyWh")
            val before = days[date]
            days[date] = DriveDay(
                date,
                (before?.km ?: 0.0) + metres / 1000,
                listOfNotNull(before?.minutes, seconds?.let { (it / 60).roundToInt() }).takeIf { it.isNotEmpty() }?.sum(),
                listOfNotNull(before?.kwh, wh?.div(1000)).takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }
    return days
}

/**
 * Where [charges] go on the timeline, as bills do: each paired with the bill paid at its very time
 * among [bills] (that bill then shown with it, not on its own), and with the Moment or check-in
 * that bill goes with ([linkOf]), else the one at the charge's own time ([at], uids by epoch
 * milliseconds), else loose on its day. What comes back: the charges loose by day, those linked by
 * uid, and the ids of the bills taken.
 */
fun placeCharges(
    charges: List<Charge>,
    bills: List<Txn>,
    linkOf: (Txn) -> String?,
    at: Map<Long, String>,
): Triple<Map<String, List<Charge>>, Map<String, List<Charge>>, Set<String>> {
    val byTime = bills.filter { it.kind == TxnKind.EXPENSE }.groupBy { it.time }.mapValues { it.value.toMutableList() }
    val taken = mutableSetOf<String>()
    val loose = mutableListOf<Charge>()
    val linked = mutableMapOf<String, MutableList<Charge>>()
    for (charge in charges.sortedBy { it.at }) {
        val time = instantOf(charge.at)
        val bill = time?.let { byTime[it]?.removeFirstOrNull() }
        bill?.let { taken += it.id }
        val paired = charge.copy(bill = bill)
        val uid = bill?.let(linkOf) ?: time?.let(at::get)
        if (uid != null) linked.getOrPut(uid) { mutableListOf() } += paired else loose += paired
    }
    return Triple(loose.groupBy { it.at.take(10) }, linked, taken)
}


/**
 * A charge on the timeline: on the page a row, in a records bubble a line, at the foot of the
 * Moment or check-in it goes with a line, and under it on the page; with the bill paid for it,
 * that bill's category and amount, opening the bill.
 */
class ChargeRecord(val charge: Charge, val ledger: Ledger?) : DayRecord {
    override val key get() = "charge-${charge.uid}"
    override val time get() = charge.at.drop(11).take(8)
    override val instant get() = instantOf(charge.at)
    override val inCard get() = false

    @Composable override fun open(onDay: (() -> Unit)?): (() -> Unit)? = charge.bill?.let { openBill(it.id) } ?: onDay

    @Composable override fun Row(modifier: Modifier) = ChargeRow(charge, modifier, ledger)

    @Composable override fun Line(large: Boolean, timed: Boolean) =
        ChargeRecordRow(charge, ledger, large, timed, timeAtEnd = false, noteColor = tokens.tx3)

    @Composable override fun Attached(large: Boolean, bubbleTime: String?) {
        val open = charge.bill?.let { bill -> Modifier.clickable(onClick = openBill(bill.id)) } ?: Modifier
        Box(open) { ChargeRecordRow(charge, ledger, large, timed = charge.at.drop(11).take(5) != bubbleTime, timeAtEnd = true, noteColor = tokens.tx2) }
    }

    @Composable override fun AttachedRow() = ChargeRow(charge, ledger = ledger)
}

/** What the car drove on a day, the day's last line: known by its day alone, opening the day. */
class DriveRecord(val drive: DriveDay) : DayRecord {
    override val key get() = "drive-${drive.date}"
    override val time get() = ""
    override val instant: Long? get() = null
    override val endsDay get() = true

    @Composable override fun open(onDay: (() -> Unit)?): (() -> Unit)? = onDay

    @Composable override fun Row(modifier: Modifier) = DriveRow(drive, modifier)

    @Composable override fun Line(large: Boolean, timed: Boolean) = RecordRow(
        icon = { Icon(AppIcons.Car, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
        title = stringResource(Res.string.timeline_drive),
        note = driveNote(drive),
        noteColor = tokens.tx3,
        noteBelow = true,
        // Known by its day alone: the time's column stays, empty.
        time = null,
        end = androidx.compose.ui.text.AnnotatedString(stringResource(Res.string.distance_kilometres, decimals(drive.km, if (drive.km < 10) 1 else 0))),
        endColor = tokens.tx,
        large = large,
        timed = timed,
        tag = "drive_${drive.date}",
    )
}

/**
 * A charge as a row on the page, as a bill is: its time first, then AC or DC with how much and how
 * long under it; with the bill paid for it, that bill's category and amount.
 */
@Composable
fun ChargeRow(charge: Charge, modifier: Modifier = Modifier, ledger: Ledger? = null) {
    val bill = charge.bill?.takeIf { ledger != null }
    PageRow(
        leading = { Icon(AppIcons.Ev, contentDescription = null) },
        title = if (bill != null) cc.hyoban.moments.ui.ledger.txnTitle(bill, ledger!!) else org.jetbrains.compose.resources.stringResource(Res.string.timeline_charge),
        supporting = listOfNotNull(
            org.jetbrains.compose.resources.stringResource(if (charge.dc) Res.string.charge_dc else Res.string.charge_ac),
            charge.kwh?.let { "%.1f kWh".format(it) },
            charge.minutes?.let { org.jetbrains.compose.resources.pluralStringResource(Res.plurals.charge_minutes, it, it) },
        ).joinToString(" "),
        onClick = null,
        modifier = modifier.testTag("charge_${charge.uid}"),
        time = charge.at.drop(11).take(5),
        trailing = { if (bill != null) Text(cc.hyoban.moments.ui.ledger.billAmount(bill, ledger!!), style = AppTheme.typography.body, color = cc.hyoban.moments.ui.ledger.amountColor(bill)) },
    )
}

/** What the car drove on a day, as a row on the page: how far, and how long and the energy under it. */
@Composable
fun DriveRow(drive: DriveDay, modifier: Modifier = Modifier) {
    PageRow(
        leading = { Icon(AppIcons.Car, contentDescription = null) },
        title = org.jetbrains.compose.resources.stringResource(Res.string.timeline_drive) + " " +
            org.jetbrains.compose.resources.stringResource(Res.string.distance_kilometres, decimals(drive.km, if (drive.km < 10) 1 else 0)),
        supporting = driveNote(drive).ifEmpty { null },
        onClick = null,
        modifier = modifier.testTag("drive_${drive.date}"),
    )
}

/**
 * A charge as one line of a bubble: with the bill paid for it, that bill's category, the energy
 * faint after it and what it cost at the end; alone, 充电 and its energy at the end.
 */
@Composable
internal fun ChargeRecordRow(charge: Charge, ledger: Ledger?, large: Boolean, timed: Boolean, timeAtEnd: Boolean, noteColor: androidx.compose.ui.graphics.Color) {
    val energy = charge.kwh?.let { "%.1f kWh".format(it) } ?: charge.minutes?.let { pluralStringResource(Res.plurals.charge_minutes, it, it) }.orEmpty()
    val bill = charge.bill?.takeIf { ledger != null }
    RecordRow(
        icon = { Icon(AppIcons.Ev, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
        title = if (bill != null) txnTitle(bill, ledger!!) else stringResource(Res.string.timeline_charge),
        note = energy.takeIf { bill != null },
        noteColor = noteColor,
        time = charge.at.drop(11).take(5).ifEmpty { null },
        end = if (bill != null) billAmount(bill, ledger!!) else androidx.compose.ui.text.AnnotatedString(energy),
        endColor = if (bill != null) amountColor(bill) else tokens.tx,
        large = large,
        timed = timed,
        tag = "charge_${charge.uid}",
        timeAtEnd = timeAtEnd,
    )
}

/** A day's driving time and energy, as its line says them after 用车. */
@Composable
fun driveNote(drive: DriveDay): String = listOfNotNull(
    drive.minutes?.let { minutes ->
        if (minutes >= 60) stringResource(Res.string.footprints_hours_minutes, minutes / 60, minutes % 60) else stringResource(Res.string.footprints_minutes, minutes)
    },
    drive.kwh?.let { "%.1f kWh".format(it) },
).joinToString(" · ")
/** The Moments' and check-ins' uids by when they were, in epoch milliseconds, as a bill finds the one at its very time. */
fun momentTimes(moments: Collection<MomentRecord>, checkins: Collection<Checkin>): Map<Long, String> = buildMap {
    checkins.forEach { checkin -> instantOf(checkin.at)?.let { put(it, checkin.uid) } }
    moments.forEach { moment -> instantOf(moment.occurredAt)?.let { put(it, moment.uid) } }
}
