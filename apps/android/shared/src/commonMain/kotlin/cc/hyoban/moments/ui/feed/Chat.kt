package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.things.Checkin
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.costMoney
import cc.hyoban.moments.ui.format.costTitle
import cc.hyoban.moments.ui.format.costVerdictLabel
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.format.formatTime
import cc.hyoban.moments.ui.ledger.TIME
import cc.hyoban.moments.ui.ledger.amountColor
import cc.hyoban.moments.ui.ledger.billAmount
import cc.hyoban.moments.ui.ledger.incoming
import cc.hyoban.moments.ui.ledger.txnIcon
import cc.hyoban.moments.ui.ledger.txnTitle
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.isLargeFont
import cc.hyoban.moments.ui.theme.tokens

// The timeline and a trip as a conversation with oneself: each day opens with its date in a
// capsule at the middle, a Moment or a check-in is a bubble sent from the right, the bills linked
// to it at its foot inside it, and the ledger's other bills, the car's charges and the footprints
// come in from the left, those in a row in one outlined bubble.

/** What one day of the conversation holds, in order: Moments and check-ins, and the records between them gathered. */
sealed interface ChatItem {
    val key: String

    data class Moment(val moment: MomentRecord) : ChatItem {
        override val key get() = moment.uid
    }

    data class CheckinStop(val checkin: Checkin) : ChatItem {
        override val key get() = "checkin-${checkin.uid}"
    }

    data class Records(val lines: List<RecordLine>) : ChatItem {
        override val key get() = "records-${lines.first().key}"
    }
}

/** One line of a records bubble. */
sealed interface RecordLine {
    val key: String

    data class Bill(val txn: Txn) : RecordLine {
        override val key get() = "bill-${txn.id}"
    }

    data class ChargeLine(val charge: Charge) : RecordLine {
        override val key get() = "charge-${charge.uid}"
    }

    data class ThingLine(val thing: ThingRecord) : RecordLine {
        override val key get() = "thing-${thing.uid}"
    }

    data class Track(val day: String, val piece: TrackPiece) : RecordLine {
        override val key get() = "track-$day-${piece.start}"
    }
}

/**
 * [items] of one day, oldest first, as the conversation shows them: each Moment and check-in
 * (its linked bills inside its bubble), and every run of records between them in one bubble,
 * a run parted at [breakAt] (epoch milliseconds), where a trip begins, so none straddles it.
 */
fun chatItems(items: List<DayItem>, breakAt: Long? = null): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var run = mutableListOf<RecordLine>()
    fun flush() {
        if (run.isNotEmpty()) out += ChatItem.Records(run).also { run = mutableListOf() }
    }
    fun add(line: RecordLine) {
        val at = line.at()
        if (breakAt != null && at != null && at >= breakAt && run.any { (it.at() ?: Long.MIN_VALUE) < breakAt }) flush()
        run += line
    }
    for (item in items) {
        when (item) {
            is DayItem.Moment -> {
                flush()
                out += ChatItem.Moment(item.moment)
            }
            is DayItem.Bills -> item.txns.sortedBy { it.time }.forEach { add(RecordLine.Bill(it)) }
            is DayItem.ChargeItem -> add(RecordLine.ChargeLine(item.charge))
            is DayItem.ThingItem -> add(RecordLine.ThingLine(item.thing))
            is DayItem.CheckinItem -> {
                flush()
                out += ChatItem.CheckinStop(item.checkin)
            }
            is DayItem.Track -> add(RecordLine.Track(item.day, item.piece))
        }
    }
    flush()
    return out
}

/** When a line of a records bubble was, in epoch milliseconds; a device or a subscription has no time. */
private fun RecordLine.at(): Long? = when (this) {
    is RecordLine.Bill -> txn.time
    is RecordLine.ChargeLine -> instantOf(charge.at)
    is RecordLine.ThingLine -> null
    is RecordLine.Track -> piece.start
}

/** When a bubble of the conversation was, in epoch milliseconds: a records bubble by its first line with a time. */
fun ChatItem.at(): Long? = when (this) {
    is ChatItem.Moment -> instantOf(moment.occurredAt)
    is ChatItem.CheckinStop -> instantOf(checkin.at)
    is ChatItem.Records -> lines.firstNotNullOfOrNull { it.at() }
}

/** How wide the strip a bubble keeps on its side for the tail; the bubble itself stands this far in. */
val TAIL = 8.dp

/**
 * A bubble as wide as [widest] makes of the [column] it stands in, and its tail's strip: just
 * that wide if [fill], else as wide as it needs up to that. Worked out as it is measured, so the
 * bubble is composed with the rest of its row and a list can make it ready ahead of a scroll.
 */
fun Modifier.bubbleWidth(fill: Boolean, widest: (column: Dp) -> Dp): Modifier = layout { measurable, constraints ->
    val max = constraints.constrainWidth((widest(constraints.maxWidth.toDp()) + TAIL).roundToPx())
    val placeable = measurable.measure(
        if (fill) constraints.copy(minWidth = max, maxWidth = max) else constraints.copy(minWidth = minOf(constraints.minWidth, max), maxWidth = max),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The corners of a sent bubble's body, inside its tail's strip: what its photos and its press are clipped to. */
fun sentBodyShape(joinedAbove: Boolean, joinedBelow: Boolean) = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = Radius.large,
    topEnd = if (joinedAbove) Radius.medium else Radius.large,
    bottomEnd = if (joinedBelow) Radius.medium else 0.dp,
    bottomStart = Radius.large,
)

/**
 * A bubble as Telegram draws one: large corners, and on its side ([sent], the right; else the
 * left) a strip [TAIL] wide where its last bubble of a run curls a tail out of its foot. Within a
 * run from the same side, the corners on that side where it meets its neighbours are small, and
 * only the last has the tail. Equal for the same bubble, so one made again as it is composed
 * again keeps its clip's and border's outline.
 */
data class BubbleShape(private val sent: Boolean, private val joinedAbove: Boolean, private val joinedBelow: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val w = size.width
        val h = size.height
        val t = TAIL.toPx()
        val large = Radius.large.toPx()
        val small = Radius.medium.toPx()
        val right = w - t
        // Drawn as the right side's; the left side's is its mirror.
        fun x(v: Float) = if (sent) v else w - v
        val k = 0.4477f // 1 − 0.5523, how far in a quarter circle's cubic handles reach
        val path = Path()
        fun corner(fromX: Float, fromY: Float, cx: Float, cy: Float, toX: Float, toY: Float) =
            path.cubicTo(x(fromX + (cx - fromX) * (1 - k)), fromY + (cy - fromY) * (1 - k), x(toX + (cx - toX) * (1 - k)), toY + (cy - toY) * (1 - k), x(toX), toY)
        val tr = if (joinedAbove) small else large
        val tail = !joinedBelow
        path.moveTo(x(large), 0f)
        path.lineTo(x(right - tr), 0f)
        corner(right - tr, 0f, right, 0f, right, tr)
        if (tail) {
            // The tail: straight down, then sweeping out to its tip at the foot of the strip.
            val top = h - 16.dp.toPx()
            val u = 16.dp.toPx() / 16f
            path.lineTo(x(right), top)
            path.cubicTo(x(right), top + 9 * u, x(right + 2.5f * u), top + 14.5f * u, x(right + t), h)
            path.lineTo(x(large), h)
        } else {
            path.lineTo(x(right), h - small)
            corner(right, h - small, right, h, right - small, h)
            path.lineTo(x(large), h)
        }
        corner(large, h, 0f, h, 0f, h - large)
        path.lineTo(x(0f), large)
        corner(0f, large, 0f, 0f, large, 0f)
        path.close()
        Outline.Generic(path)
    }
}

/**
 * A day's date in a capsule at the middle of the column: 今天 or the date, with its weekday, and
 * [note] before it, a trip's 第 n 天. Tapped for the whole day, which grows out of it.
 */
@Composable
fun DateCapsule(day: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, note: String? = null, description: String = "") {
    val date = java.time.LocalDate.parse(day)
    val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, cc.hyoban.moments.ui.components.currentLocale())
    val label = if (date == java.time.LocalDate.now()) stringResource(Res.string.label_today) + " " + weekday else formatDay(day)
    val text = listOfNotNull(note, label).joinToString(" ")
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // At least a touch target high for its tap, the capsule itself only as tall as its words.
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clip(AppTheme.shapes.medium).clickable(onClickLabel = stringResource(Res.string.label_whole_day), onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = listOfNotNull(note, formatDay(day), description.takeIf { it.isNotBlank() }).joinToString(" ")
                }
                .testTag("day_header_$day"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = AppTheme.typography.caption,
                color = tokens.tx2,
                modifier = Modifier.background(tokens.fill, cc.hyoban.moments.ui.theme.Corners.full).padding(horizontal = Space.m, vertical = Space.xs),
            )
        }
    }
}

/**
 * Records in a row as one bubble from the left: a line each, when, its icon and what it is,
 * and its amount or figure at the end, no total under them, every one of them shown.
 * One record opens itself; several, their day.
 */
@Composable
fun RecordsBubble(
    lines: List<RecordLine>,
    ledger: Ledger,
    onBill: (String) -> Unit,
    onDay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shown = lines
    val single = shown.size == 1
    val openThing = LocalThingNav.current
    val open: (() -> Unit)? = when (val only = shown.singleOrNull()) {
        is RecordLine.Bill -> ({ onBill(only.txn.id) })
        is RecordLine.ThingLine -> openThing?.let { { it(only.thing.uid) } }
        is RecordLine.Track, is RecordLine.ChargeLine -> onDay
        null -> onDay
    }
    val large = isLargeFont()
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        // As wide as three quarters of the column, or the whole of it with the font set large;
        // a single record only as wide as it needs. Never wider than 420 on a wide screen. The
        // tail's strip comes on top, at the left.
        val shape = BubbleShape(sent = false, joinedAbove = false, joinedBelow = false)
        Column(
            Modifier
                .bubbleWidth(fill = !single) { column -> minOf(if (large) column - TAIL else column * 0.86f, 420.dp) }
                .clip(shape)
                .border(cardBorder(), shape)
                .then(if (open != null) Modifier.clickable(onClick = open) else Modifier)
                .padding(start = TAIL)
                .padding(horizontal = Space.m, vertical = Space.xs)
                .testTag("records_${lines.first().key}"),
        ) {
            // Only a device or a subscription alone has no time, and no column for one.
            val timed = shown.any { it !is RecordLine.ThingLine }
            shown.forEach { line ->
                when (line) {
                    is RecordLine.Bill -> RecordRow(
                        icon = { Icon(txnIcon(line.txn, ledger), contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                        title = txnTitle(line.txn, ledger),
                        time = line.txn.local.format(TIME),
                        end = billAmount(line.txn, ledger),
                        endColor = amountColor(line.txn),
                        large = large,
                        timed = timed,
                        tag = "bill_${line.txn.id}",
                    )
                    is RecordLine.ChargeLine -> RecordRow(
                        icon = { Icon(AppIcons.Ev, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                        title = stringResource(Res.string.timeline_charge),
                        time = line.charge.at.drop(11).take(5).ifEmpty { null },
                        end = androidx.compose.ui.text.AnnotatedString(line.charge.kwh?.let { "%.1f kWh".format(it) } ?: line.charge.minutes?.let { pluralStringResource(Res.plurals.charge_minutes, it, it) }.orEmpty()),
                        endColor = tokens.tx,
                        large = large,
                        timed = timed,
                        tag = "charge_${line.charge.uid}",
                    )
                    is RecordLine.ThingLine -> RecordRow(
                        icon = { Icon(if (line.thing.device) AppIcons.Laptop else AppIcons.Subscription, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                        title = thingTitle(line.thing),
                        // Known by its day alone: the time's column stays, empty.
                        time = null,
                        end = androidx.compose.ui.text.AnnotatedString(cc.hyoban.moments.ui.things.priceOf(line.thing.price, line.thing.currency)),
                        endColor = tokens.tx,
                        large = large,
                        timed = timed,
                        tag = "thing_${line.thing.uid}",
                    )
                    is RecordLine.Track -> RecordRow(
                        icon = { Icon(AppIcons.Footprints, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                        title = stringResource(Res.string.timeline_track),
                        time = trackTime(line.piece),
                        end = androidx.compose.ui.text.AnnotatedString(stringResource(Res.string.distance_kilometres, decimals(line.piece.metres / 1000, 1))),
                        endColor = tokens.tx,
                        large = large,
                        timed = timed,
                        tag = "track_${line.day}_${line.piece.start}",
                    )
                }
            }
        }
    }
}

/**
 * The bills linked to a Moment or a check-in, at the foot of its bubble under a hairline: a line
 * each as a records bubble draws them, each opening its bill; a bill at the bubble's own [time]
 * leaves the time out, its amount at the edge. A Moment's own [cost] leads them, drawn the same,
 * its amount at the edge: a meal's dishes or what it went on, how it was and how many it was
 * for; it opens the Moment as the rest of its bubble does.
 */
@Composable
fun BubbleBills(
    txns: List<Txn>,
    ledger: Ledger?,
    onBill: (String) -> Unit,
    modifier: Modifier = Modifier,
    time: String? = null,
    cost: MomentCost? = null,
) {
    val large = isLargeFont()
    Column(modifier.fillMaxWidth()) {
        cc.hyoban.moments.ui.design.HorizontalDivider(Modifier.padding(bottom = Space.xs))
        cost?.amount?.let { amount ->
            RecordRow(
                icon = { Icon(if (cost.meal) AppIcons.Food else AppIcons.Cash, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                title = listOfNotNull(
                    cost.items.takeIf { it.isNotEmpty() }?.joinToString(stringResource(Res.string.separator_list))
                        ?: stringResource(costTitle(cost)),
                    FoodVerdict.of(cost.verdict)?.let { stringResource(costVerdictLabel(cost, it)) },
                    cost.people?.takeIf { it > 1 }?.let { pluralStringResource(Res.plurals.count_food_people, it, it) },
                ).joinToString(" "),
                time = null,
                end = androidx.compose.ui.text.AnnotatedString(costMoney(amount, cost.currency)),
                endColor = tokens.tx,
                large = large,
                timed = false,
                tag = "moment_cost",
                timeAtEnd = true,
            )
        }
        if (ledger != null) txns.forEach { txn ->
            val at = txn.local.format(TIME)
            Box(Modifier.clickable { onBill(txn.id) }) {
                RecordRow(
                    icon = { Icon(txnIcon(txn, ledger), contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                    title = txnTitle(txn, ledger),
                    time = at,
                    end = billAmount(txn, ledger),
                    endColor = amountColor(txn),
                    large = large,
                    timed = at != time,
                    tag = "bill_${txn.id}",
                    timeAtEnd = true,
                )
            }
        }
    }
}

/** One line of a records bubble; with the font set large, the figure goes under the words. */
@Composable
private fun RecordRow(
    icon: @Composable () -> Unit,
    title: String,
    time: String?,
    end: androidx.compose.ui.text.AnnotatedString,
    endColor: androidx.compose.ui.graphics.Color,
    large: Boolean,
    timed: Boolean,
    tag: String,
    /** In a bubble from the right, the time ends the line, as a Moment's does, rather than leading it. */
    timeAtEnd: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = if (large) Space.xs else 0.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // The time leads, a column of its own read down the bubble as the day goes, as wide as
        // 00:00 since the system's monospace doesn't always keep its digits one width; a line with
        // no time (a device or a subscription) keeps the column empty, so the icons stay in line.
        val timeColumn: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.CenterEnd) {
                Text("00:00", style = AppTheme.typography.caption, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
                time?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1) }
            }
        }
        if (timed && !timeAtEnd) timeColumn()
        icon()
        val words: @Composable (Modifier) -> Unit = { m ->
            Text(title, style = AppTheme.typography.body, maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = m)
        }
        if (large) {
            Column(Modifier.weight(1f)) {
                words(Modifier)
                Text(end, style = AppTheme.typography.body, color = endColor)
            }
        } else {
            words(Modifier.weight(1f))
            Text(end, style = AppTheme.typography.body, color = endColor, maxLines = 1)
        }
        if (timed && timeAtEnd) timeColumn()
    }
}

/**
 * A check-in as the conversation has it: sent from the right as a Moment is, on the pressed fill
 * and drawn with the Moments beside it, only as wide as it needs; the place's pin, its venue on
 * one line and, faint at the end, whether it is private and when. It sets the records around it
 * apart, and a tap opens its sheet.
 */
@Composable
fun CheckinBubble(
    checkin: Checkin,
    joinedAbove: Boolean,
    joinedBelow: Boolean,
    modifier: Modifier = Modifier,
    bills: List<Txn> = emptyList(),
    ledger: Ledger? = null,
    onBill: (String) -> Unit = {},
) {
    val open = LocalCheckinNav.current
    val shape = androidx.compose.runtime.remember(joinedAbove, joinedBelow) { BubbleShape(sent = true, joinedAbove, joinedBelow) }
    val title = checkinTitle(checkin)
    val withBills = bills.isNotEmpty() && ledger != null
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        // With its bills it is as wide as a records bubble, they at its foot.
        Column(
            Modifier
                .bubbleWidth(fill = withBills) { column -> minOf(column * 0.88f, 420.dp) }
                .clip(shape)
                .background(tokens.fill)
                .then(if (open != null) Modifier.clickable { open(checkin.uid) } else Modifier)
                .padding(end = TAIL)
                .padding(horizontal = Space.m, vertical = Space.s)
                .testTag("checkin_${checkin.uid}"),
        ) {
            // With bills under it, its time ends the line as theirs do, at the bubble's right edge.
            Row(
                (if (withBills) Modifier.fillMaxWidth() else Modifier).semantics(mergeDescendants = true) { contentDescription = title },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Icon(AppIcons.Pin, contentDescription = null, tint = tokens.tx2, modifier = Modifier.size(18.dp))
                Text(checkin.venue, style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = withBills))
                val meta = listOfNotNull(stringResource(Res.string.visibility_private).takeIf { checkin.private }, formatTime(checkin.at)).joinToString(" ")
                if (meta.isNotEmpty()) Text(meta, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1)
            }
            if (withBills) BubbleBills(bills, ledger, onBill, Modifier.padding(top = Space.s), time = formatTime(checkin.at))
        }
    }
}
