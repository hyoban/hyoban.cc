package cc.hyoban.moments.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.DateTimeDialog
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.theme.Space
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 编辑旅程: its name, the days it spans (its Moments are those days', worked out, not listed),
 * and whether it is public. Kept on the phone like any change until 全部发布到网站; the page's
 * one ink button saves it.
 */
@Composable
fun TripEditScreen(container: AppContainer, id: String, onBack: () -> Unit) {
    val trips by container.repository.trips.collectAsStateWithLifecycle()
    val trip = remember(trips, id) { trips.firstOrNull { it.id == id } } ?: return
    var title by rememberSaveable { mutableStateOf(trip.title) }
    var start by rememberSaveable { mutableStateOf(trip.start) }
    var end by rememberSaveable { mutableStateOf(trip.end) }
    var private by rememberSaveable { mutableStateOf(container.repository.isTripPrivate(id)) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = title.isNotBlank() && start <= end
    SubPage(stringResource(R.string.trip_edit_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                OutlinedTextField(
                    title,
                    { title = it },
                    Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).testTag("trip_title"),
                    label = { Text(stringResource(R.string.trip_edit_name)) },
                    singleLine = true,
                )
                SegmentedGroup(
                    stringResource(R.string.trip_edit_days),
                    stringResource(R.string.trip_edit_days_note),
                    listOf(
                        { shapes -> cc.hyoban.moments.footprints.ActionRow(AppIcons.Calendar, stringResource(R.string.trip_edit_start), shapes, supporting = formatDay(start), tag = "trip_start") { picking = "start" } },
                        { shapes -> cc.hyoban.moments.footprints.ActionRow(AppIcons.Calendar, stringResource(R.string.trip_edit_end), shapes, supporting = formatDay(end), tag = "trip_end") { picking = "end" } },
                    ),
                )
                SegmentedGroup(
                    null,
                    stringResource(if (private) R.string.moment_private_note else R.string.trip_edit_public_note),
                    listOf { shapes ->
                        SwitchRow(private, AppIcons.EyeOff, stringResource(R.string.visibility_private), "trip_private", shapes) { private = !private }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l), horizontalArrangement = Arrangement.End) {
                    Button(
                        enabled = valid,
                        onClick = {
                            scope.launch {
                                container.repository.editTrip(id, title.trim(), start, end, if (private) Visibility.PRIVATE else Visibility.PUBLIC)
                                onBack()
                            }
                        },
                        modifier = Modifier.testTag("trip_save"),
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
    picking?.let { which ->
        val day = LocalDate.parse(if (which == "start") start else end)
        DateTimeDialog(day, 0, 0, onDismiss = { picking = null }) { picked, _, _ ->
            if (which == "start") start = picked.toString() else end = picked.toString()
            picking = null
        }
    }
}
