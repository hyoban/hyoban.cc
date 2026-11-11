package cc.hyoban.moments.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.footprints.TrackSelection
import cc.hyoban.moments.footprints.distanceMetres
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.compose.formatDistance
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * 足迹 where they are only shown (D14): the days there are footprints of, newest first, each with
 * how far it went, a day opening its page with its track on the map. Recording, and what the
 * footprints are set to, stay on the phone; [subtitle] says where these came from.
 */
@Composable
fun FootprintDaysScreen(container: AppContainer, subtitle: String?, onDay: (String) -> Unit, onBack: () -> Unit) {
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    val days by produceState(emptyList<String>(), revision) {
        value = withContext(Dispatchers.IO) { container.footprints.days() }
    }
    SubPage(stringResource(Res.string.footprints_title), onBack, Modifier.background(AppColors.page), subtitle = subtitle) {
        if (days.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(top = LocalTopClearance.current).padding(Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Text(stringResource(Res.string.desktop_no_backup), style = AppTheme.typography.title, textAlign = TextAlign.Center)
                Text(stringResource(Res.string.desktop_backup_hint), style = AppTheme.typography.body, color = tokens.tx2, textAlign = TextAlign.Center)
            }
            return@SubPage
        }
        // How far each day went, worked out once for the summary at the top.
        val distances by produceState(emptyMap<String, Double>(), days, revision) {
            value = withContext(Dispatchers.IO) { days.associateWith { dayDistance(container, it) } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = LocalTopClearance.current, bottom = Space.xl)) {
            item(key = "summary") { Summary(distances) }
            items(days, key = { it }) { day ->
                // How far the day went, read as its row comes into view.
                val metres = distances[day]
                ListItem(
                    headlineContent = { Text(formatDate(day)) },
                    leadingContent = { Icon(AppIcons.Footprints, contentDescription = null) },
                    trailingContent = { metres?.let { Text(formatDistance(it)) } },
                    modifier = Modifier.fillMaxWidth().testTag("footprint_day_$day").clickableDay { onDay(day) },
                )
            }
        }
    }
}

private fun Modifier.clickableDay(onClick: () -> Unit) = clickable(onClick = onClick)

/** How far [day]'s footprints went, a break in them counting nothing. */
private fun dayDistance(container: AppContainer, day: String): Double =
    container.footprints.points(TrackSelection(day = day)).zipWithNext { a, b -> if (a.segment == b.segment) distanceMetres(a, b) else 0.0 }.sum()

/** Today's, this week's, this month's and this year's distance, side by side (D14). */
@Composable
private fun Summary(distances: Map<String, Double>) {
    val today = java.time.LocalDate.now()
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    fun since(from: java.time.LocalDate) = distances.filterKeys { runCatching { !java.time.LocalDate.parse(it).isBefore(from) }.getOrDefault(false) }.values.sum()
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        listOf(
            Res.string.label_today to since(today),
            Res.string.period_week to since(weekStart),
            Res.string.period_month to since(today.withDayOfMonth(1)),
            Res.string.period_year to since(today.withDayOfYear(1)),
        ).forEach { (label, metres) ->
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = AppTheme.typography.caption, color = tokens.tx2)
                Text(formatDistance(metres), style = AppTheme.typography.title)
            }
        }
    }
}
