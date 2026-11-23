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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import cc.hyoban.moments.data.spec.FoodVerdict
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.data.spec.MomentRecord
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

    /** A module's own stop, a check-in, sent from the right as a Moment is. */
    data class Anchor(val anchor: DayAnchor) : ChatItem {
        override val key get() = anchor.key
    }

    data class Records(val lines: List<RecordLine>) : ChatItem {
        override val key get() = "records-${lines.first().key}"
    }
}

/** One line of a records bubble. */
sealed interface RecordLine {
    val key: String

    /** A module's own record, as its plugin draws it. */
    data class Record(val record: DayRecord) : RecordLine {
        override val key get() = record.key
    }

    data class Track(val day: String, val piece: TrackPiece) : RecordLine {
        override val key get() = "track-$day-${piece.start}"
    }
}

/**
 * [items] of one day, oldest first, as the conversation shows them: each Moment and stop
 * (what goes with it inside its bubble), and every run of records between them in one bubble,
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
            is DayItem.Record -> add(RecordLine.Record(item.record))
            is DayItem.Anchor -> {
                flush()
                out += ChatItem.Anchor(item.anchor)
            }
            is DayItem.Track -> add(RecordLine.Track(item.day, item.piece))
        }
    }
    flush()
    return out
}

/** When a line of a records bubble was, in epoch milliseconds; a device or a subscription has no time. */
private fun RecordLine.at(): Long? = when (this) {
    is RecordLine.Record -> record.instant
    is RecordLine.Track -> piece.start
}

/** When a bubble of the conversation was, in epoch milliseconds: a records bubble by its first line with a time. */
fun ChatItem.at(): Long? = when (this) {
    is ChatItem.Moment -> instantOf(moment.occurredAt)
    is ChatItem.Anchor -> anchor.instant
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

/**
 * A planned bubble's outline: a sent bubble's corners with no tail, inside the tail's strip all
 * the same so it lines up with the bubbles beside it, its last corner large where the tail would be.
 */
data class PlannedBubbleShape(private val joinedAbove: Boolean, private val joinedBelow: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val large = androidx.compose.ui.geometry.CornerRadius(Radius.large.toPx())
        val small = androidx.compose.ui.geometry.CornerRadius(Radius.medium.toPx())
        Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                0f, 0f, size.width - TAIL.toPx(), size.height,
                topLeftCornerRadius = large,
                topRightCornerRadius = if (joinedAbove) small else large,
                bottomRightCornerRadius = if (joinedBelow) small else large,
                bottomLeftCornerRadius = large,
            ),
        )
    }
}

/** A hairline of [color], dashed, along [shape]'s outline: what a planned bubble has for its fill. */
fun Modifier.dashedOutline(shape: Shape, color: androidx.compose.ui.graphics.Color): Modifier = drawBehind {
    val width = 1.dp.toPx()
    val dash = 4.dp.toPx()
    drawOutline(
        shape.createOutline(size, layoutDirection, this),
        color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dash, dash / 1.5f))),
    )
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
    onDay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** A footprints' line drawn as the way it was gone, with its own tap; else as 轨迹. */
    trackRow: (@Composable (RecordLine.Track) -> Unit)? = null,
) {
    val shown = lines
    val single = shown.size == 1
    val openRecord = (shown.singleOrNull() as? RecordLine.Record)?.record?.open(onDay)
    val open: (() -> Unit)? = when (val only = shown.singleOrNull()) {
        is RecordLine.Record -> openRecord
        is RecordLine.Track -> onDay.takeIf { trackRow == null }
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
            // Only a record known by its day alone (a device, a subscription) or a day's driving alone has no time, and no column for one.
            val timed = shown.any { !(it is RecordLine.Record && it.record.time.isEmpty()) }
            shown.forEach { line ->
                when (line) {
                    is RecordLine.Record -> line.record.Line(large, timed)
                    is RecordLine.Track -> if (trackRow != null) trackRow(line) else RecordRow(
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
 * What goes with a Moment or a stop, at the foot of its bubble under a hairline, each drawn by
 * its module: a Moment's own cost, the charges at its time, the bills linked to it; one at the
 * bubble's own [time] leaves the time out.
 */
@Composable
fun BubbleFoot(records: List<DayRecord>, modifier: Modifier = Modifier, time: String? = null) {
    val large = isLargeFont()
    Column(modifier.fillMaxWidth()) {
        cc.hyoban.moments.ui.design.HorizontalDivider(Modifier.padding(bottom = Space.xs))
        records.forEach { it.Attached(large, time) }
    }
}

/** One line of a records bubble; with the font set large, the figure goes under the words. */
@Composable
internal fun RecordRow(
    icon: @Composable () -> Unit,
    title: String,
    time: String?,
    /** What follows the title, faint: a charge's energy beside its bill, a day's driving time and energy. */
    note: String? = null,
    noteColor: androidx.compose.ui.graphics.Color = tokens.tx3,
    end: androidx.compose.ui.text.AnnotatedString,
    endColor: androidx.compose.ui.graphics.Color,
    large: Boolean,
    timed: Boolean,
    tag: String,
    /** In a bubble from the right, the time ends the line, as a Moment's does, rather than leading it. */
    timeAtEnd: Boolean = false,
    /** The note on a line of its own under the title, as a day's driving has it, too long to share one. */
    noteBelow: Boolean = false,
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
        val caption = AppTheme.typography.caption
        val words: @Composable (Modifier) -> Unit = { m ->
            if (noteBelow && !note.isNullOrBlank()) {
                Column(m.padding(vertical = Space.xs)) {
                    Text(title, style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(note, style = caption, color = noteColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val text = if (note.isNullOrBlank()) androidx.compose.ui.text.AnnotatedString(title) else androidx.compose.ui.text.buildAnnotatedString {
                    append(title)
                    append("  ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = noteColor, fontSize = caption.fontSize))
                    append(note)
                    pop()
                }
                Text(text, style = AppTheme.typography.body, maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = m)
            }
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
 * A way between two stops as a records bubble's line: its way's [icon] and [title], [note] faint
 * after it (推断), [time] in the time column and [end] at the edge; tapped, [menu] opens over it.
 */
@Composable
fun WayRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    note: String?,
    time: String?,
    end: String,
    timed: Boolean,
    tag: String,
    busy: Boolean = false,
    menu: (@Composable (dismiss: () -> Unit) -> Unit)?,
) {
    val open = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box((if (menu != null) Modifier.clickable { open.value = true } else Modifier)) {
        RecordRow(
            icon = {
                if (busy) cc.hyoban.moments.ui.design.Spinner(color = tokens.tx3, size = 14.dp, stroke = 2.dp, inset = 2.dp)
                else Icon(icon, contentDescription = null, tint = tokens.tx3, modifier = Modifier.size(18.dp))
            },
            title = title,
            time = time,
            note = note,
            end = androidx.compose.ui.text.AnnotatedString(end),
            endColor = tokens.tx,
            large = isLargeFont(),
            timed = timed,
            tag = tag,
        )
        menu?.let { content -> cc.hyoban.moments.ui.design.DropdownMenu(open.value, onDismissRequest = { open.value = false }) { content { open.value = false } } }
    }
}

/** A records bubble of one line: a way between two stops with no footprints' lines to tell it in. */
@Composable
fun WayBubble(modifier: Modifier = Modifier, row: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        val shape = BubbleShape(sent = false, joinedAbove = false, joinedBelow = false)
        Column(
            Modifier
                .bubbleWidth(fill = false) { column -> minOf(column * 0.86f, 420.dp) }
                .clip(shape)
                .border(cardBorder(), shape)
                .padding(start = TAIL)
                .padding(horizontal = Space.m, vertical = Space.xs),
        ) { row() }
    }
}

