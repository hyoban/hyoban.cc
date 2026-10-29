package cc.hyoban.moments.ui.feed

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import cc.hyoban.moments.ui.design.FilterChip
import cc.hyoban.moments.ui.design.FilterChipDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

/**
 * Each chip tagged [tag] and its year or month, or `all`, in a row that scrolls sideways.
 * When they don't fit, a leading chip shows them all, wrapped onto as many rows as they
 * need, as Material 3 has a set of chips reflow rather than only scroll; it shows less again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(tag: String, options: List<Int?>, selected: Int?, label: @Composable (Int?) -> String, onSelect: (Int?) -> Unit) {
    var all by rememberSaveable(tag) { mutableStateOf(false) }
    val row = rememberLazyListState()
    val overflows = all || row.canScrollForward || row.canScrollBackward
    // The leading chip appears ahead of the row once it overflows; the row starts from it.
    LaunchedEffect(overflows) { if (overflows) row.scrollToItem(0) }
    val toggle: @Composable () -> Unit = {
        FilterChip(
            selected = all,
            onClick = { all = !all },
            label = { Text(stringResource(if (all) R.string.action_show_less else R.string.action_show_all)) },
            leadingIcon = {
                Icon(if (all) AppIcons.Collapse else AppIcons.Expand, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
            modifier = Modifier.testTag("${tag}toggle"),
        )
    }
    if (all) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            toggle()
            options.forEach { option -> OptionChip(tag, option, selected, label, onSelect) }
        }
    } else {
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (overflows) item(key = "toggle") { toggle() }
            items(options, key = { it ?: -1 }) { option -> OptionChip(tag, option, selected, label, onSelect) }
        }
    }
}

/** One year or month, checked on the accent's soft tint when chosen. */
@Composable
private fun OptionChip(tag: String, option: Int?, selected: Int?, label: @Composable (Int?) -> String, onSelect: (Int?) -> Unit) {
    FilterChip(
        selected = option == selected,
        onClick = { onSelect(option) },
        label = { Text(label(option), style = if (option == selected) AppTheme.typography.labelLargeEmphasized else AppTheme.typography.labelLarge) },
        // The chosen one checked, as Material 3 has a selected filter chip.
        leadingIcon = if (option == selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.testTag("$tag${option ?: "all"}"),
    )
}
