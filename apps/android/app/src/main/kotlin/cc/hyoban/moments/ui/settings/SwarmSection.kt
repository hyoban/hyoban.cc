package cc.hyoban.moments.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.data.swarm.SwarmImport
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.Space

// 时刻 on 导入: Swarm's export, its check-ins brought in private and unpublished, how far
// copying their photos has got while it runs, and what came in once it is done.

@Composable
fun SwarmGroup(swarm: SwarmImport) {
    val state by swarm.state.collectAsStateWithLifecycle()
    val running = state.running
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) swarm.start(uri) }
    val row: Segment = { shapes ->
        SegmentedListItem(
            onClick = { if (!running) picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Timeline, contentDescription = null) },
            supportingContent = {
                Text(
                    when (val now = state) {
                        SwarmImport.State.Reading -> stringResource(R.string.swarm_reading)
                        is SwarmImport.State.Copying -> stringResource(R.string.swarm_copying, now.done, now.total)
                        else -> stringResource(R.string.swarm_import_note)
                    },
                )
            },
            trailingContent = if (running) {
                { Spinner(Modifier.size(24.dp)) }
            } else {
                null
            },
            modifier = Modifier.testTag("import_swarm"),
        ) { Text(stringResource(R.string.swarm_import)) }
    }
    SegmentedGroup(stringResource(R.string.import_moments), stringResource(R.string.swarm_import_explain), listOf(row))

    when (val now = state) {
        is SwarmImport.State.Done -> AlertDialog(
            onDismissRequest = swarm::dismiss,
            title = { Text(stringResource(R.string.swarm_import)) },
            text = {
                val report = now.report
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(stringResource(R.string.swarm_import_summary, report.checkins, report.moments, report.entries, report.photos, report.places))
                    if (report.known > 0) Text(stringResource(R.string.swarm_import_known, report.known))
                    Text(stringResource(if (LocalSettings.current.connected) R.string.swarm_import_private else R.string.swarm_import_private_alone))
                }
            },
            confirmButton = { TextButton(onClick = swarm::dismiss) { Text(stringResource(R.string.action_confirm)) } },
        )
        SwarmImport.State.Failed -> AlertDialog(
            onDismissRequest = swarm::dismiss,
            title = { Text(stringResource(R.string.swarm_import)) },
            text = { Text(stringResource(R.string.swarm_import_failed)) },
            confirmButton = { TextButton(onClick = swarm::dismiss) { Text(stringResource(R.string.action_confirm)) } },
        )
        else -> Unit
    }
}
