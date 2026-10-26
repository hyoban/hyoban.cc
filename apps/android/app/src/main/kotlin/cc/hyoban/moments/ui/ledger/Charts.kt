package cc.hyoban.moments.ui.ledger

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ledger.formatMoney
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.format.weekdayLetters
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// The ledger's charts, drawn on a canvas: thin marks from one baseline, a 2dp gap of the card
// between touching marks, a single series in the primary and categories in a fixed order of
// colour-blind-safe hues, each chart readable by tapping a mark and by TalkBack.

// The validated order of the data-viz palette's categorical hues, stepped for each mode. No
// Material 3 role says "the fourth series", so these stay as they are, like the map's years.
private val SERIES_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300)
private val SERIES_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300)

/** How many categories get a hue of their own; the rest are summed as 其他, in grey. */
const val SERIES_COUNT = 6

@Composable
fun seriesColors(): List<Color> =
    (if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SERIES_DARK else SERIES_LIGHT).map { Color(it) }

/** The colour of 其他 and of a period compared with: recessive, beside the series. */
@Composable
fun otherColor(): Color = MaterialTheme.colorScheme.outline

/** A chart's marks growing in from nothing whenever [key] changes, read only while drawing. */
@Composable
private fun rememberReveal(key: Any?): Animatable<Float, *> {
    val progress = remember(key) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    LaunchedEffect(progress) { progress.animateTo(1f, spec) }
    return progress
}

/**
 * A chart on a card: its title, a line reading out what is picked or else what stands out,
 * and when the pick leads to bills, a button to them; a legend for two series or more.
 */
@Composable
fun ChartCard(
    title: String,
    readout: String,
    modifier: Modifier = Modifier,
    legend: List<Pair<String, Color>> = emptyList(),
    onOpen: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = LocalSurfaceLevel.current.raised.fill(),
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.xs),
    ) {
        Column(Modifier.padding(start = Space.l, end = Space.s, top = Space.m, bottom = Space.l)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        readout,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (onOpen != null) TextButton(onClick = onOpen) { Text(stringResource(R.string.ledger_open_bills)) }
            }
            Column(Modifier.padding(end = Space.s)) {
                if (legend.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        legend.forEach { (label, color) -> LegendKey(label, color) }
                    }
                }
                Spacer(Modifier.height(Space.m))
                content()
            }
        }
    }
}

@Composable
private fun LegendKey(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Space.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Labels under a chart: one per mark when there are as many, else spread from end to end. */
@Composable
private fun Axis(labels: List<String>, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = if (labels.size == count) Arrangement.Start else Arrangement.SpaceBetween) {
        labels.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = if (labels.size == count) Modifier.weight(1f) else Modifier,
            )
        }
    }
}

/** A bar between [top] and [bottom], its data end rounded, at the bottom when it [falls], and square on the baseline. */
private fun DrawScope.bar(color: Color, left: Float, top: Float, width: Float, bottom: Float, round: Boolean, falls: Boolean = false) {
    if (bottom - top <= 0f) return
    val corner = CornerRadius(if (round) min(4.dp.toPx(), min(width / 2, bottom - top)) else 0f)
    val path = Path().apply {
        addRoundRect(
            if (falls) RoundRect(left, top, left + width, bottom, bottomLeftCornerRadius = corner, bottomRightCornerRadius = corner)
            else RoundRect(left, top, left + width, bottom, topLeftCornerRadius = corner, topRightCornerRadius = corner),
        )
    }
    drawPath(path, color)
}

/**
 * Columns from one baseline, a column a slot: [series] side by side in each slot, or one on
 * another when [stacked]. A tap picks a slot, and again lets it go; the others fade back.
 */
@Composable
fun BarChart(
    series: List<List<Long>>,
    colors: List<Color>,
    axis: List<String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    height: Dp = 120.dp,
) {
    val count = series.firstOrNull()?.size ?: 0
    if (count == 0) return
    val reveal = rememberReveal(series)
    val base = MaterialTheme.colorScheme.outlineVariant
    val select by rememberUpdatedState(onSelect)
    val picked by rememberUpdatedState(selected)
    val max = (if (stacked) (0 until count).maxOf { i -> series.sumOf { it[i].coerceAtLeast(0) } } else series.maxOf { s -> s.maxOf { it } }).coerceAtLeast(1)
    // More back than spent, a refund's, falls below the baseline rather than vanishing.
    val min = if (stacked) 0 else series.minOf { s -> s.minOf { it } }.coerceAtMost(0)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = description }
                .pointerInput(count) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width.toFloat() / count)).toInt().coerceIn(0, count - 1)
                        select(if (index == picked) null else index)
                    }
                },
        ) {
            val slot = size.width / count
            val gap = 2.dp.toPx()
            val group = min(slot * 0.7f, if (stacked) 24.dp.toPx() else 24.dp.toPx() * series.size + gap * (series.size - 1))
            val width = if (stacked) group else (group - gap * (series.size - 1)) / series.size
            val scale = (size.height - gap) / (max - min)
            val bottom = size.height + min * scale
            for (i in 0 until count) {
                val alpha = if (picked == null || picked == i) 1f else 0.4f
                val left = i * slot + (slot - group) / 2
                if (stacked) {
                    var top = bottom
                    val last = series.indices.lastOrNull { series[it][i] > 0 }
                    series.forEachIndexed { s, values ->
                        val value = values[i].coerceAtLeast(0)
                        if (value == 0L) return@forEachIndexed
                        val h = value * scale * reveal.value
                        bar(colors[s].copy(alpha = alpha), left, top - h + if (s == last) 0f else gap, width, top, s == last)
                        top -= h
                    }
                } else {
                    series.forEachIndexed { s, values ->
                        val h = values[i] * scale * reveal.value
                        val x = left + s * (width + gap)
                        if (h >= 0) bar(colors[s].copy(alpha = alpha), x, bottom - h, width, bottom, true)
                        else bar(colors[s].copy(alpha = alpha), x, bottom, width, bottom - h, true, falls = true)
                    }
                }
            }
            drawLine(base, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        }
        Axis(axis, count)
    }
}

/**
 * Lines over [length] steps, each from the first and as far as it has values; with [area], a
 * wash under the first. From zero unless [zeroBased] is off, when it spans the values' range.
 * A tap picks a step, marked down the chart, and again lets it go.
 */
@Composable
fun LineChart(
    lines: List<List<Long>>,
    colors: List<Color>,
    length: Int,
    axis: List<String>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    area: Boolean = false,
    zeroBased: Boolean = true,
) {
    if (length < 2 || lines.all { it.isEmpty() }) return
    val reveal = rememberReveal(lines)
    val base = MaterialTheme.colorScheme.outlineVariant
    val surface = LocalSurfaceLevel.current.raised.fill()
    val select by rememberUpdatedState(onSelect)
    val picked by rememberUpdatedState(selected)
    val values = lines.flatten()
    val top = values.max().coerceAtLeast(if (zeroBased) 1 else values.max())
    val low = if (zeroBased) min(0, values.min()) else values.min()
    val span = (top - low).coerceAtLeast(1).toFloat()
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(120.dp)
                .semantics { contentDescription = description }
                .pointerInput(length) {
                    detectTapGestures { offset ->
                        val index = (offset.x / size.width * (length - 1)).roundToInt().coerceIn(0, length - 1)
                        select(if (index == picked) null else index)
                    }
                },
        ) {
            val inset = 6.dp.toPx()
            val h = size.height - inset * 2
            fun x(i: Int) = i.toFloat() / (length - 1) * size.width
            fun y(v: Long) = inset + h - (v - low) / span * h
            if (low < 0) drawLine(base, Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1.dp.toPx())
            drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            clipRect(right = size.width * reveal.value) {
                // Drawn last to first, so the first, the period itself, is over what it is compared with.
                lines.indices.reversed().forEach { index ->
                    val line = lines[index]
                    if (line.isEmpty()) return@forEach
                    val path = Path().apply {
                        moveTo(x(0), y(line[0]))
                        for (i in 1 until line.size) lineTo(x(i), y(line[i]))
                    }
                    if (area && index == 0) {
                        val wash = Path().apply {
                            addPath(path)
                            lineTo(x(line.size - 1), size.height)
                            lineTo(x(0), size.height)
                            close()
                        }
                        drawPath(wash, colors[0].copy(alpha = 0.1f))
                    }
                    drawPath(path, colors[index], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            val mark = picked ?: lines[0].lastIndex.takeIf { it >= 0 && reveal.value == 1f }
            if (picked != null) drawLine(base, Offset(x(picked!!), 0f), Offset(x(picked!!), size.height), strokeWidth = 1.dp.toPx())
            if (mark != null) {
                lines.forEachIndexed { index, line ->
                    if (mark > line.lastIndex || (picked == null && index > 0)) return@forEachIndexed
                    val center = Offset(x(mark), y(line[mark]))
                    drawCircle(surface, 6.dp.toPx(), center)
                    drawCircle(colors[index], 4.dp.toPx(), center)
                }
            }
        }
        Axis(axis, -1)
    }
}

/**
 * Shares of a whole as a ring, clockwise from the top, a 2dp gap of the card between them;
 * a tap on one picks it, drawn wider while the rest fade, and [center] says what it is.
 */
@Composable
fun DonutChart(
    slices: List<Pair<Long, Color>>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val total = slices.sumOf { it.first }.coerceAtLeast(1)
    val reveal = rememberReveal(slices)
    val select by rememberUpdatedState(onSelect)
    val picked by rememberUpdatedState(selected)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = description }
                    .pointerInput(slices) {
                        detectTapGestures { offset ->
                            val dx = offset.x - size.width / 2f
                            val dy = offset.y - size.height / 2f
                            val radius = size.width / 2f
                            if (hypot(dx, dy) !in radius * 0.55f..radius) return@detectTapGestures select(null)
                            val angle = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360) % 360
                            var start = 0.0
                            val index = slices.indexOfFirst { (cents, _) -> start += cents * 360.0 / total; angle < start }
                            select(if (index == picked || index < 0) null else index)
                        }
                    },
            ) {
                val wide = 36.dp.toPx()
                val thin = 26.dp.toPx()
                val radius = size.width / 2 - wide / 2
                val gap = if (slices.size > 1) Math.toDegrees(2.dp.toPx() / radius.toDouble()).toFloat() else 0f
                var start = -90f
                slices.forEachIndexed { index, (cents, color) ->
                    val sweep = cents * 360f / total * reveal.value
                    val stroke = if (picked == index) wide else thin
                    val alpha = if (picked == null || picked == index) 1f else 0.4f
                    if (sweep > gap) {
                        drawArc(
                            color.copy(alpha = alpha),
                            startAngle = start + gap / 2,
                            sweepAngle = sweep - gap,
                            useCenter = false,
                            topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(stroke),
                        )
                    }
                    start += sweep
                }
            }
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) { center() }
        }
    }
}

/** Which of four steps of the primary a day's spending is, by its quartile among the days with any; 0 for none. */
private fun levelsOf(values: Collection<Long>): (Long) -> Int {
    val sorted = values.filter { it > 0 }.sorted()
    if (sorted.isEmpty()) return { 0 }
    val cuts = listOf(0.25, 0.5, 0.75).map { sorted[((sorted.size - 1) * it).roundToInt()] }
    return { value -> if (value <= 0) 0 else 1 + cuts.count { value > it } }
}

private val LEVEL_ALPHA = listOf(0f, 0.2f, 0.4f, 0.65f, 0.9f)

/**
 * A month's days as a calendar from Monday, each filled with the primary as deep as what was
 * spent that day, leading to its bills.
 */
@Composable
fun MonthHeatmap(month: LocalDate, byDay: Map<LocalDate, Long>, currency: String, onDay: (LocalDate) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val level = remember(byDay) { levelsOf(byDay.values) }
    val lead = month.dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val weeks = (lead + days + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weekdayLetters().forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        for (week in 0 until weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in 0 until 7) {
                    val day = week * 7 + column - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (day in 1..days) {
                            val date = month.withDayOfMonth(day)
                            val cents = byDay[date] ?: 0
                            val step = level(cents)
                            val description = listOfNotNull(formatDate(date.toString(), today = date), formatMoneyOrNull(cents, currency)).joinToString(" ")
                            val cell = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small)
                            Box(
                                if (step == 0) {
                                    cell.clearAndSetSemantics { contentDescription = description }
                                } else {
                                    cell.background(primary.copy(alpha = LEVEL_ALPHA[step]))
                                        .clickable { onDay(date) }
                                        .clearAndSetSemantics {
                                            contentDescription = description
                                            role = Role.Button
                                            onClick { onDay(date); true }
                                        }
                                        .testTag("heat_$date")
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$day",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        step >= 3 -> MaterialTheme.colorScheme.onPrimary
                                        step > 0 -> MaterialTheme.colorScheme.onSurface
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMoneyOrNull(cents: Long, currency: String) = if (cents == 0L) null else formatMoney(cents, currency)

/**
 * A year's days, its months down and their days across, each as deep a step of the primary as
 * what was spent; a tap picks a day, marked with a ring, and again lets it go.
 */
@Composable
fun YearHeatmap(year: Int, byDay: Map<LocalDate, Long>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit, description: String) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val ink = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val level = remember(byDay) { levelsOf(byDay.filterKeys { it.year == year }.values) }
    val select by rememberUpdatedState(onSelect)
    val picked by rememberUpdatedState(selected)
    val gutter = 20.dp
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(31f / 12f * 0.9f)
            .semantics { contentDescription = description }
            .pointerInput(year) {
                detectTapGestures { offset ->
                    val left = gutter.toPx()
                    val cell = (size.width - left) / 31f
                    val row = (offset.y / (size.height / 12f)).toInt().coerceIn(0, 11)
                    val column = ((offset.x - left) / cell).toInt()
                    val month = java.time.YearMonth.of(year, row + 1)
                    val date = if (column in 0 until month.lengthOfMonth()) month.atDay(column + 1) else null
                    select(if (date == picked) null else date)
                }
            },
    ) {
        val left = gutter.toPx()
        val cellWidth = (size.width - left) / 31f
        val cellHeight = size.height / 12f
        val gap = 2.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        for (month in 1..12) {
            val ym = java.time.YearMonth.of(year, month)
            if (month % 3 == 1) {
                val text = measurer.measure("$month", labelStyle.copy(color = labelColor))
                drawText(text, topLeft = Offset(0f, (month - 1) * cellHeight + (cellHeight - text.size.height) / 2))
            }
            for (day in 1..ym.lengthOfMonth()) {
                val date = ym.atDay(day)
                val step = level(byDay[date] ?: 0)
                val topLeft = Offset(left + (day - 1) * cellWidth + gap / 2, (month - 1) * cellHeight + gap / 2)
                val cell = Size(cellWidth - gap, cellHeight - gap)
                drawRoundRect(if (step == 0) empty else primary.copy(alpha = LEVEL_ALPHA[step]), topLeft, cell, radius)
                if (date == picked) drawRoundRect(ink, topLeft, cell, radius, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
