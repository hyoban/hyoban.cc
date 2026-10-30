package cc.hyoban.moments.ui.feed

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ledger.Ledger
import cc.hyoban.moments.ledger.Txn
import cc.hyoban.moments.ui.components.dayCardKey
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.ledger.TIME
import cc.hyoban.moments.ui.ledger.amountColor
import cc.hyoban.moments.ui.ledger.incoming
import cc.hyoban.moments.ui.ledger.signedMoney
import cc.hyoban.moments.ui.ledger.txnIcon
import cc.hyoban.moments.ui.ledger.txnTitle
import cc.hyoban.moments.ui.theme.Radius
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.cardBorder
import cc.hyoban.moments.ui.theme.isLargeFont
import cc.hyoban.moments.ui.theme.tokens

// The timeline and a trip as a conversation with oneself: each day opens with its date in a
// capsule at the middle, a Moment is a bubble sent from the right, and the ledger's bills, the
// car's charges and the footprints come in from the left, those in a row in one outlined bubble.

/** What one day of the conversation holds, in order: Moments, and the records between them gathered. */
sealed interface ChatItem {
    val key: String

    data class Moment(val moment: MomentRecord) : ChatItem {
        override val key get() = moment.uid
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

    data class Track(val day: String) : RecordLine {
        override val key get() = "track-$day"
    }
}

/**
 * [items] of one day, oldest first, as the conversation shows them: each Moment, the bills
 * written with it ([linked]) right after it, and every run of records between Moments in one
 * bubble.
 */
fun chatItems(items: List<DayItem>, linked: Map<String, List<Txn>>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var run = mutableListOf<RecordLine>()
    fun flush() {
        if (run.isNotEmpty()) out += ChatItem.Records(run).also { run = mutableListOf() }
    }
    for (item in items) {
        when (item) {
            is DayItem.Moment -> {
                flush()
                out += ChatItem.Moment(item.moment)
                linked[item.moment.uid].orEmpty().forEach { run += RecordLine.Bill(it) }
            }
            is DayItem.Bills -> item.txns.sortedBy { it.time }.forEach { run += RecordLine.Bill(it) }
            is DayItem.ChargeItem -> run += RecordLine.ChargeLine(item.charge)
            is DayItem.Track -> run += RecordLine.Track(item.day)
        }
    }
    flush()
    return out
}

/** How wide the strip a bubble keeps on its side for the tail; the bubble itself stands this far in. */
val TAIL = 8.dp

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
 * only the last has the tail.
 */
class BubbleShape(private val sent: Boolean, private val joinedAbove: Boolean, private val joinedBelow: Boolean) : Shape {
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
    val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, LocalConfiguration.current.locales[0])
    val label = if (date == java.time.LocalDate.now()) stringResource(R.string.label_today) + " " + weekday else formatDay(day)
    val text = listOfNotNull(note, label).joinToString(" ")
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // At least a touch target high for its tap, the capsule itself only as tall as its words.
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.sharedCard(dayCardKey(day)).clip(AppTheme.shapes.medium).clickable(onClickLabel = stringResource(R.string.label_whole_day), onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = listOfNotNull(note, formatDay(day), description.takeIf { it.isNotBlank() }).joinToString(" ")
                }
                .testTag("day_header_$day"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = AppTheme.typography.caption,
                color = tokens.tx2,
                modifier = Modifier.clip(cc.hyoban.moments.ui.theme.Corners.full).background(tokens.fill).padding(horizontal = Space.m, vertical = Space.xs),
            )
        }
    }
}

/**
 * Records in a row as one bubble from the left: a line each, when, its icon and what it is,
 * and its amount or figure at the end, no total under them. More
 * than five show four and how many more. One record opens itself; several, their day.
 */
@Composable
fun RecordsBubble(
    lines: List<RecordLine>,
    ledger: Ledger,
    tracks: TimelineTracks,
    onBill: (String) -> Unit,
    onDay: (() -> Unit)?,
    onTrackDay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // A day's footprints read from the phone as the bubble comes into view; a day that moved
    // too little to list has no line, and a bubble of that alone isn't drawn.
    val trackDay = (lines.lastOrNull() as? RecordLine.Track)?.day
    val track by produceState(trackDay?.let { tracks.cached(it) }, trackDay, tracks) { value = trackDay?.let { tracks.load(it) } }
    val shown = lines.filter { it !is RecordLine.Track || track != null }
    if (shown.isEmpty()) return
    val single = shown.size == 1
    val open: (() -> Unit)? = when (val only = shown.singleOrNull()) {
        is RecordLine.Bill -> ({ onBill(only.txn.id) })
        is RecordLine.Track -> onTrackDay ?: onDay
        is RecordLine.ChargeLine -> onDay
        null -> onDay
    }
    val large = isLargeFont()
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        // As wide as three quarters of the column, or the whole of it with the font set large;
        // a single record only as wide as it needs. Never wider than 420 on a wide screen. The
        // tail's strip comes on top, at the left.
        val max = minOf(if (large) maxWidth - TAIL else maxWidth * 0.86f, 420.dp) + TAIL
        val shape = BubbleShape(sent = false, joinedAbove = false, joinedBelow = false)
        Column(
            Modifier
                .then(if (single) Modifier.widthIn(max = max) else Modifier.width(max))
                .clip(shape)
                .border(cardBorder(), shape)
                .then(if (open != null) Modifier.clickable(onClick = open) else Modifier)
                .padding(start = TAIL)
                .padding(horizontal = Space.m, vertical = Space.xs)
                .testTag("records_${lines.first().key}"),
        ) {
            val visible = if (shown.size > MAX_LINES) shown.take(MAX_LINES - 1) else shown
            // The footprints alone have no time, and no column for one.
            val timed = shown.any { it !is RecordLine.Track }
            visible.forEach { line ->
                when (line) {
                    is RecordLine.Bill -> RecordRow(
                        icon = { Icon(txnIcon(line.txn, ledger), contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                        title = txnTitle(line.txn, ledger),
                        time = line.txn.local.format(TIME),
                        end = signedMoney(line.txn.amount, line.txn.currency, line.txn.incoming),
                        endColor = amountColor(line.txn),
                        large = large,
                        timed = timed,
                        tag = "bill_${line.txn.id}",
                    )
                    is RecordLine.ChargeLine -> RecordRow(
                        icon = { Icon(AppIcons.Ev, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                        title = stringResource(R.string.timeline_charge),
                        time = line.charge.at.drop(11).take(5).ifEmpty { null },
                        end = line.charge.kwh?.let { "%.1f kWh".format(it) } ?: line.charge.minutes?.let { pluralStringResource(R.plurals.charge_minutes, it, it) }.orEmpty(),
                        endColor = tokens.tx,
                        large = large,
                        timed = timed,
                        tag = "charge_${line.charge.uid}",
                    )
                    is RecordLine.Track -> track?.let { day ->
                        RecordRow(
                            icon = { Icon(AppIcons.Footprints, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp)) },
                            title = stringResource(R.string.timeline_track),
                            time = null,
                            end = stringResource(R.string.distance_kilometres, day.metres / 1000),
                            endColor = tokens.tx,
                            large = large,
                            timed = timed,
                            tag = "track_${line.day}",
                        )
                    }
                }
            }
            if (shown.size > MAX_LINES) {
                Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.timeline_more_records, shown.size - visible.size), style = AppTheme.typography.caption, color = tokens.tx3)
                }
            }
        }
    }
}

private const val MAX_LINES = 5

/** One line of a records bubble; with the font set large, the figure goes under the words. */
@Composable
private fun RecordRow(
    icon: @Composable () -> Unit,
    title: String,
    time: String?,
    end: String,
    endColor: androidx.compose.ui.graphics.Color,
    large: Boolean,
    timed: Boolean,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = if (large) Space.xs else 0.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // The time leads, a column of its own read down the bubble as the day goes, as wide as
        // 00:00 since the system's monospace doesn't always keep its digits one width; a line with
        // no time (the footprints) keeps the column empty, so the icons stay in line.
        if (timed) Box {
            Text("00:00", style = AppTheme.typography.caption, color = androidx.compose.ui.graphics.Color.Transparent, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            time?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx3, maxLines = 1) }
        }
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
    }
}
