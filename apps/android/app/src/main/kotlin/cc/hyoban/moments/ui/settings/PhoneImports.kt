package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.plugins.footprints
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.plugins.Points
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.footprints.TrackImportResult
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.footprints.readFootprintCsv
import cc.hyoban.moments.footprints.readGpx
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.ledger.rememberICostImport
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

// Every way in for what another app or a file holds, grouped by where it goes: the Moments, the
// ledger, the footprints. A row opens the system's file picker; what came in is said in a dialog.

/**
 * The sources 导入 offers, by name: those whose module is on (记录项与公开). What 我 says under
 * 导入, and the row is left out when there are none.
 */
@Composable
fun importSources(): List<String> {
    val settings = LocalSettings.current
    val on = Plugins.registry.on(Points.imports, settings::isOn).flatMap { it.sources }
    return on.map { stringResource(it) }
}

/** iCost's backup or bill export into the ledger, what it found shown before it is written. */
@Composable
internal fun ICostGroup(container: PhoneContainer) {
    val importer = rememberICostImport(container)
    SettingsGroupView(
        SettingsGroupSpec(
            stringResource(Res.string.import_ledger),
            stringResource(Res.string.import_ledger_note),
            listOf(
                SettingsRow.Action(
                    AppIcons.Receipt, stringResource(Res.string.ledger_import_icost), "import_icost",
                    supporting = stringResource(Res.string.ledger_import_icost_note),
                    onClick = importer.pick,
                ),
            ),
        ),
    )
    importer.Dialog()
}

/** What became of a track import: what it added and passed over, or that it failed. */
private sealed interface TrackState {
    data object Reading : TrackState
    data class Done(val result: TrackImportResult) : TrackState
    data object Failed : TrackState
}

/**
 * Track files into the footprints, the phone's own samples kept where the two overlap: a GPX
 * this app exported, or Footprint's (一生足迹) CSVs, several years' files at once.
 */
@Composable
internal fun TrackGroup(container: PhoneContainer) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<TrackState?>(null) }
    val reading = state == TrackState.Reading
    fun import(uris: List<Uri>, read: (InputStream) -> List<TrackPoint>) {
        if (uris.isEmpty()) return
        state = TrackState.Reading
        // On the app's scope, so leaving the page doesn't stop it halfway.
        container.scope.launch {
            state = try {
                TrackState.Done(
                    withContext(Dispatchers.IO) {
                        val points = uris.flatMap { uri ->
                            context.contentResolver.openInputStream(uri)?.use(read) ?: error("Cannot open document")
                        }
                        container.footprints.import(points)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TrackState.Failed
            }
        }
    }
    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Not a GPX at all reads as one with nothing in it.
        import(listOfNotNull(uri)) { try { readGpx(it) } catch (_: org.xml.sax.SAXException) { emptyList() } }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        import(uris, ::readFootprintCsv)
    }
    val importing = stringResource(Res.string.ledger_importing)
    // Files apps call a .gpx or a .csv by several types, or none.
    fun row(tag: String, title: String, note: String, onClick: () -> Unit) = SettingsRow.Action(
        AppIcons.Footprints, title, tag,
        supporting = if (reading) importing else note,
        busy = reading,
    ) { if (!reading) onClick() }
    SettingsGroupView(
        SettingsGroupSpec(
            stringResource(Res.string.footprints_title),
            stringResource(Res.string.import_footprints_note),
            listOf(
                row("footprints_import", stringResource(Res.string.footprints_import), stringResource(Res.string.footprints_import_note)) {
                    gpx.launch(arrayOf("*/*"))
                },
                row("footprints_import_csv", stringResource(Res.string.footprints_import_csv), stringResource(Res.string.footprints_import_csv_note)) {
                    csv.launch(arrayOf("*/*"))
                },
            ),
        ),
    )

    val ended = state
    if (ended is TrackState.Done || ended == TrackState.Failed) {
        AlertDialog(
            onDismissRequest = { state = null },
            title = { Text(stringResource(Res.string.import_footprints_title)) },
            text = {
                Text(
                    when (ended) {
                        is TrackState.Done -> with(ended.result) {
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
