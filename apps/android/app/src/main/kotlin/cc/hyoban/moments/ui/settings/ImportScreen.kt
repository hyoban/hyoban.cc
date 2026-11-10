package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.footprints.TrackImportResult
import cc.hyoban.moments.footprints.readGpx
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.ledger.rememberICostImport
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Every way in for what another app or a file holds, grouped by where it goes: the Moments, the
// ledger, the footprints. A row opens the system's file picker; what came in is said in a dialog.

/**
 * The sources 导入 offers, by name: those whose module is on (记录项与公开). What 我 says under
 * 导入, and the row is left out when there are none.
 */
@Composable
fun importSources(): List<String> {
    val settings = LocalSettings.current
    return listOfNotNull(
        stringResource(Res.string.import_source_swarm).takeIf { settings.isOn(Module.CHECKIN) },
        stringResource(Res.string.import_source_icost).takeIf { settings.isOn(Module.LEDGER) },
        stringResource(Res.string.import_source_gpx).takeIf { settings.isOn(Module.FOOTPRINTS) },
    )
}

/** 导入: Swarm's export, iCost's backup or bill export, and GPX tracks, on one page under 我. */
@Composable
fun ImportScreen(container: AppContainer, onBack: () -> Unit) {
    SubPage(stringResource(Res.string.import_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                // Each source under the module it feeds, so one turned off (记录项与公开) isn't offered.
                val settings = LocalSettings.current
                if (settings.isOn(Module.CHECKIN)) SwarmGroup(container.swarm)
                if (settings.isOn(Module.LEDGER)) ICostGroup(container)
                if (settings.isOn(Module.FOOTPRINTS)) GpxGroup(container)
                // Opened by a link with every one of them off: why there is nothing here.
                if (importSources().isEmpty()) Text(
                    stringResource(Res.string.import_none),
                    style = AppTheme.typography.body,
                    color = tokens.tx2,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l).testTag("import_none"),
                )
            }
        }
    }
}

/** iCost's backup or bill export into the ledger, what it found shown before it is written. */
@Composable
private fun ICostGroup(container: AppContainer) {
    val importer = rememberICostImport(container)
    SegmentedGroup(
        stringResource(Res.string.import_ledger),
        stringResource(Res.string.import_ledger_note),
        listOf { shapes ->
            SegmentedListItem(
                onClick = importer.pick,
                shapes = shapes,
                colors = segmentColors(),
                leadingContent = { Icon(AppIcons.Receipt, contentDescription = null) },
                supportingContent = { Text(stringResource(Res.string.ledger_import_icost_note)) },
                modifier = Modifier.testTag("import_icost"),
            ) { Text(stringResource(Res.string.ledger_import_icost)) }
        },
    )
    importer.Dialog()
}

/** What became of a GPX import: what it added and passed over, or that it failed. */
private sealed interface GpxState {
    data object Reading : GpxState
    data class Done(val result: TrackImportResult) : GpxState
    data object Failed : GpxState
}

/** A GPX file into the footprints, the phone's own samples kept where the two overlap. */
@Composable
private fun GpxGroup(container: AppContainer) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<GpxState?>(null) }
    val reading = state == GpxState.Reading
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = GpxState.Reading
        // On the app's scope, so leaving the page doesn't stop it halfway.
        container.scope.launch {
            state = try {
                GpxState.Done(
                    withContext(Dispatchers.IO) {
                        val points = context.contentResolver.openInputStream(uri)?.use {
                            // Not a GPX at all reads as one with nothing in it.
                            try { readGpx(it) } catch (_: org.xml.sax.SAXException) { emptyList() }
                        } ?: error("Cannot open document")
                        container.footprints.import(points)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                GpxState.Failed
            }
        }
    }
    SegmentedGroup(
        stringResource(Res.string.footprints_title),
        stringResource(Res.string.import_footprints_note),
        listOf { shapes ->
            SegmentedListItem(
                // Files apps call a .gpx by several types, or none.
                onClick = { if (!reading) picker.launch(arrayOf("*/*")) },
                shapes = shapes,
                colors = segmentColors(),
                leadingContent = { Icon(AppIcons.Footprints, contentDescription = null) },
                supportingContent = { Text(stringResource(if (reading) Res.string.ledger_importing else Res.string.footprints_import_note)) },
                trailingContent = if (reading) {
                    { Spinner(Modifier.size(24.dp)) }
                } else {
                    null
                },
                modifier = Modifier.testTag("footprints_import"),
            ) { Text(stringResource(Res.string.footprints_import)) }
        },
    )

    val ended = state
    if (ended is GpxState.Done || ended == GpxState.Failed) {
        AlertDialog(
            onDismissRequest = { state = null },
            title = { Text(stringResource(Res.string.footprints_import)) },
            text = {
                Text(
                    when (ended) {
                        is GpxState.Done -> with(ended.result) {
                            if (added + overlapping + invalid == 0) stringResource(Res.string.footprints_import_empty)
                            else stringResource(Res.string.footprints_import_done, added, overlapping, invalid)
                        }
                        else -> stringResource(Res.string.footprints_error)
                    },
                )
            },
            confirmButton = { TextButton(onClick = { state = null }) { Text(stringResource(Res.string.action_confirm)) } },
        )
    }
}
