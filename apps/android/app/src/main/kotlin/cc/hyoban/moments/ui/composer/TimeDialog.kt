package cc.hyoban.moments.ui.composer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import cc.hyoban.moments.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A day, then a time: the Material pickers one after the other. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(initial).atZone(zone)
    val dateState = rememberDatePickerState(initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val timeState = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
    var pickingTime by remember { mutableStateOf(false) }

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { pickingTime = true }) { Text(stringResource(R.string.label_time)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val day = dateState.selectedDateMillis?.let { LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC) } ?: start.toLocalDate()
                    onPick(day.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone).toInstant().toEpochMilli())
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = timeState) },
        )
    }
}
