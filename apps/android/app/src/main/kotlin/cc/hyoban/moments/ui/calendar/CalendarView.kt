package cc.hyoban.moments.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.MonthHeader
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.dayCardKey
import cc.hyoban.moments.ui.components.sharedCard
import cc.hyoban.moments.ui.format.formatMonth
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill
import java.time.LocalDate
import java.time.YearMonth

// The Moments as a calendar: every month that has one, newest first, laid out Monday
// first as the site's calendar is. A day with Moments shows its first photo and how many
// there are; tapping it opens them.

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

/** One day of a month's grid: its Moments, oldest first, and the media to show for it. */
private data class CalendarDay(val date: LocalDate, val moments: List<MomentRecord>, val cover: MomentMedia?)

/** A month and its weeks, each seven days or none for the days outside it. */
private data class CalendarMonth(val month: YearMonth, val weeks: List<List<CalendarDay?>>)

private fun monthsOf(moments: List<MomentRecord>): List<CalendarMonth> {
    val byDay = moments.groupBy { LocalDate.parse(it.occurredAt.take(10)) }
    return byDay.keys.map { YearMonth.from(it) }.distinct().sortedDescending().map { month ->
        // Monday first: the days before the 1st are blank.
        val lead = month.atDay(1).dayOfWeek.value - 1
        val days = List(lead) { null } + (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            val here = byDay[date].orEmpty().sortedBy { it.occurredAt }
            CalendarDay(date, here, here.firstNotNullOfOrNull { it.media.firstOrNull() })
        }
        CalendarMonth(month, days.chunked(7).map { week -> week + List(7 - week.size) { null } })
    }
}

/**
 * The calendar, as a list under [leading] (the page's header): [onDay] gets the date
 * tapped, as `YYYY-MM-DD`, and the Moments that day.
 */
@Composable
fun CalendarView(
    moments: List<MomentRecord>,
    urls: MediaUrls,
    onDay: (String, List<MomentRecord>) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    leading: LazyListScope.() -> Unit = {},
) {
    val months = remember(moments) { monthsOf(moments) }
    val today = remember { LocalDate.now() }

    PageWidth(modifier) { columns ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = columns.margin, end = columns.margin, bottom = LocalBottomClearance.current + Space.xl),
        ) {
            leading()
            if (columns.count > 1) {
                // Where the width allows, months side by side, each whole, so a month's
                // days fit the height of a phone on its side.
                months.chunked(columns.count).forEach { pair ->
                    item(key = "months-${pair.first().month}") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Top) {
                            pair.forEach { month ->
                                Column(Modifier.weight(1f)) {
                                    MonthTitle(month, today)
                                    WeekdayRow()
                                    month.weeks.forEach { week -> WeekRow(week, urls, today, onDay) }
                                }
                            }
                            repeat(columns.count - pair.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                months.forEach { month ->
                    item(key = "month-${month.month}") { MonthTitle(month, today) }
                    item(key = "weekdays-${month.month}") { WeekdayRow() }
                    month.weeks.forEachIndexed { index, week ->
                        item(key = "week-${month.month}-$index") { WeekRow(week, urls, today, onDay) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthTitle(month: CalendarMonth, today: LocalDate) {
    val (name, year) = formatMonth(month.month.atDay(1).toString())
    MonthHeader(name, year.takeIf { month.month.year != today.year })
}

@Composable
private fun WeekRow(week: List<CalendarDay?>, urls: MediaUrls, today: LocalDate, onDay: (String, List<MomentRecord>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        week.forEach { day ->
            Box(Modifier.weight(1f).aspectRatio(1f)) {
                if (day != null) DayCell(day, urls, today) { onDay(day.date.toString(), day.moments) }
            }
        }
    }
}

@Composable
private fun WeekdayRow() {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.xs, bottom = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WEEKDAYS.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A day: its photo with the date over it when it has Moments, else just the date, faint. */
@Composable
private fun DayCell(day: CalendarDay, urls: MediaUrls, today: LocalDate, onClick: () -> Unit) {
    val CELL = MaterialTheme.shapes.small
    val isToday = day.date == today
    val ring = if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CELL) else Modifier

    if (day.moments.isEmpty()) {
        Box(Modifier.fillMaxSize().clip(CELL).then(ring), contentAlignment = Alignment.Center) {
            Text(
                "${day.date.dayOfMonth}",
                style = MaterialTheme.typography.labelLarge,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        return
    }

    // A day grows into what it opens: its one Moment's page, or the page of its Moments.
    val opens = day.moments.singleOrNull()?.uid ?: dayCardKey(day.date.toString())
    Box(
        Modifier.fillMaxSize().sharedCard(opens, cardCorner = 8.dp).clip(CELL)
            .background(LocalSurfaceLevel.current.raised.fill())
            .clickable(onClick = onClick)
            .then(ring),
    ) {
        if (day.cover != null) {
            MediaStill(day.cover, urls, Modifier.fillMaxSize())
            // The date stays legible over any photo.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), Color.Transparent, MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)))))
        }
        val ink = if (day.cover != null) Color.White else MaterialTheme.colorScheme.onSurface
        Text(
            "${day.date.dayOfMonth}",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 5.dp, vertical = 3.dp),
        )
        if (day.moments.size > 1) {
            Text(
                "${day.moments.size}",
                style = MaterialTheme.typography.labelSmall,
                color = ink,
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }
    }
}
