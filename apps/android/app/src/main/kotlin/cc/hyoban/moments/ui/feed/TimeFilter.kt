package cc.hyoban.moments.ui.feed


import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.format.formatMonthOnly
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.fill

// Narrowing the Moments to a year, and within it to a month: a row of the years there
// are Moments in, and under it, once a year is chosen, the months of that year that have
// them. Each row starts with the choice of all.

/** The year and month the Moments are narrowed to; null for all. */
@Immutable
data class TimeFilter(val year: Int? = null, val month: Int? = null) {
    fun matches(moment: MomentRecord): Boolean {
        val date = moment.occurredAt
        return (year == null || date.take(4).toInt() == year) &&
            (month == null || date.substring(5, 7).toInt() == month)
    }
}

/** Keeps a [TimeFilter] across a rotation, as its two numbers. */
val TimeFilterSaver = listSaver<TimeFilter, Int?>(
    save = { listOf(it.year, it.month) },
    restore = { TimeFilter(it[0], it[1]) },
)

/** The Moments [filter] lets through. */
fun List<MomentRecord>.filtered(filter: TimeFilter): List<MomentRecord> =
    if (filter == TimeFilter()) this else filter(filter::matches)

/** The years with Moments, newest first. */
fun yearsOf(moments: List<MomentRecord>): List<Int> =
    moments.map { it.occurredAt.take(4).toInt() }.distinct().sortedDescending()

/** The months of [year] with Moments, latest first. */
fun monthsOf(moments: List<MomentRecord>, year: Int): List<Int> =
    moments.filter { it.occurredAt.take(4).toInt() == year }.map { it.occurredAt.substring(5, 7).toInt() }.distinct().sortedDescending()

/** The rows of chips choosing [filter] from [years] and, for the year chosen, [months]. */
@Composable
fun TimeFilterRows(filter: TimeFilter, years: List<Int>, months: List<Int>, onChange: (TimeFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        ChipRow(
            "year_",
            listOf(null) + years,
            selected = filter.year,
            label = { it?.let { year -> stringResource(R.string.filter_year, year) } ?: stringResource(R.string.filter_all) },
            onSelect = { onChange(TimeFilter(year = it)) },
        )
        AnimatedVisibility(
            filter.year != null && months.size > 1,
            enter = fadeIn(AppTheme.motionScheme.defaultEffectsSpec()) + expandVertically(AppTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(AppTheme.motionScheme.defaultEffectsSpec()) + shrinkVertically(AppTheme.motionScheme.defaultSpatialSpec()),
        ) {
            ChipRow(
                "month_",
                listOf(null) + months,
                selected = filter.month,
                label = { it?.let { month -> formatMonthOnly(month) } ?: stringResource(R.string.filter_whole_year) },
                onSelect = { onChange(filter.copy(month = it)) },
            )
        }
    }
}

/** Each chip tagged [tag] and its year or month, or `all`, in a row that scrolls sideways. */
@Composable
private fun ChipRow(tag: String, options: List<Int?>, selected: Int?, label: @Composable (Int?) -> String, onSelect: (Int?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.gutter),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        items(options, key = { it ?: -1 }) { option -> OptionChip(tag, option, selected, label, onSelect) }
    }
}

/** One year or month, on the accent's soft tint when chosen, as the kinds above are: no check, so a chip keeps its width. */
@Composable
private fun OptionChip(tag: String, option: Int?, selected: Int?, label: @Composable (Int?) -> String, onSelect: (Int?) -> Unit) {
    FilterChip(
        selected = option == selected,
        onClick = { onSelect(option) },
        label = { Text(label(option), style = AppTheme.typography.label) },
        modifier = Modifier.testTag("$tag${option ?: "all"}"),
    )
}
