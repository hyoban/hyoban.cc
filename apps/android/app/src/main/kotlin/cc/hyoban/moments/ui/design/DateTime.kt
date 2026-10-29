package cc.hyoban.moments.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.tokens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Picking a day and the time on it in one dialog: a month of days, stepped through by its
 * arrows, the day chosen in the ink and today's number in the accent; under it the hour and
 * minute, typed. The design's own, in place of Material's date picker and clock dial.
 */
@Composable
fun DateTimeDialog(day: LocalDate, hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (LocalDate, Int, Int) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(day.toEpochDay()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(day).toString()) }
    var hours by rememberSaveable { mutableStateOf("%02d".format(hour)) }
    var minutes by rememberSaveable { mutableStateOf("%02d".format(minute)) }
    val h = hours.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minutes.toIntOrNull()?.takeIf { it in 0..59 }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (h != null && m != null) onPick(LocalDate.ofEpochDay(chosen), h, m) }, enabled = h != null && m != null, modifier = Modifier.testTag("time_confirm")) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MonthGrid(YearMonth.parse(month), LocalDate.ofEpochDay(chosen), onMonth = { month = it.toString() }, onDay = { chosen = it.toEpochDay() })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.label_time), style = AppTheme.typography.bodyMedium, color = tokens.tx2, modifier = Modifier.weight(1f))
                    TimePart(hours, { hours = it }, "time_hour")
                    Text(":", style = AppTheme.typography.titleLarge)
                    TimePart(minutes, { minutes = it }, "time_minute")
                }
            }
        },
    )
}

/** Two digits of the time, typed into a capsule on the pressed grey. */
@Composable
private fun TimePart(value: String, onChange: (String) -> Unit, tag: String) {
    BasicTextField(
        value,
        { typed -> onChange(typed.filter(Char::isDigit).take(2)) },
        singleLine = true,
        textStyle = AppTheme.typography.titleLarge.copy(color = tokens.tx, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(tokens.tx),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(64.dp).clip(Corners.full).background(tokens.pressed).padding(vertical = 8.dp).testTag(tag),
    )
}

/** A month's days under its name and the weekdays, Monday first, [chosen] in the ink. */
@Composable
private fun MonthGrid(month: YearMonth, chosen: LocalDate, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val locale = Locale.getDefault()
    val today = remember { LocalDate.now() }
    val title = month.format(DateTimeFormatter.ofPattern(if (locale.language == "zh") "yyyy年M月" else "MMMM yyyy", locale))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) {
                Icon(AppIcons.ChevronLeft, contentDescription = stringResource(R.string.action_previous_month), tint = tokens.tx2)
            }
            IconButton(onClick = { onMonth(month.plusMonths(1)) }) {
                Icon(AppIcons.ChevronRight, contentDescription = stringResource(R.string.action_next_month), tint = tokens.tx2)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { weekday ->
                Text(
                    weekday.getDisplayName(TextStyle.NARROW, locale),
                    style = AppTheme.typography.labelMedium,
                    color = tokens.tx3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
            }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = (List(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)).chunked(7)
        cells.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val on = date == chosen
                            val label = date.toString()
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                    .then(if (on) Modifier.background(tokens.ink) else Modifier)
                                    .clickable(role = Role.RadioButton) { onDay(date) }
                                    .semantics { selected = on; contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    style = AppTheme.typography.labelLarge,
                                    color = when {
                                        on -> tokens.onInk
                                        date == today -> tokens.accent
                                        else -> tokens.tx
                                    },
                                )
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
