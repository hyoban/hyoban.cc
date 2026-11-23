package cc.hyoban.moments.footprints

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cc.hyoban.moments.plugins.FootprintNeeds
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.PermissionWords
import cc.hyoban.moments.ui.components.openAppSettings
import cc.hyoban.moments.ui.components.rememberPermissionAsk
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.PermissionSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** The phone's footprints recorded by [RecordingService] into [store], and exported or deleted from it. */
class PhoneFootprintRecording(private val context: Context, private val store: FootprintStore) : FootprintRecording {
    override val status = RecordingService.status

    override suspend fun control(action: String) {
        RecordingService.control(context, action)
    }

    @Composable
    override fun rememberEnable(onGranted: (String) -> Unit, onDeclined: () -> Unit, onDenied: () -> Unit): (String) -> Unit {
        val context = LocalContext.current
        var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
        // Recording in the background needs location 始终允许, which only the system's settings give:
        // said once, after recording first starts without it (35).
        var askBackground by rememberSaveable { mutableStateOf(false) }
        val prefs = remember { context.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
        val requested = remember { FootprintNeeds.location.permissions + FootprintNeeds.notifications.permissions }
        val permissions = rememberPermissionAsk(
            requested,
            PermissionWords(
                AppIcons.Footprints,
                stringResource(Res.string.onboarding_footprints),
                stringResource(Res.string.onboarding_footprints_line),
                stringResource(Res.string.permission_not_now),
                listOf(
                    PermissionFact(AppIcons.Shield, stringResource(Res.string.onboarding_fp_local)),
                    PermissionFact(AppIcons.Bell, stringResource(Res.string.onboarding_fp_notification)),
                ),
            ),
            isGranted = { FootprintNeeds.location.given(it) },
            onDecline = { pendingAction = null; onDeclined() },
        ) { granted ->
            val action = pendingAction
            pendingAction = null
            if (granted && action != null) {
                onGranted(action)
                if (!FootprintNeeds.background.given(context) && !prefs.getBoolean("told_background", false)) {
                    prefs.edit().putBoolean("told_background", true).apply()
                    askBackground = true
                }
            } else if (!granted) onDenied()
        }
        if (askBackground) PermissionSheet(
            icon = AppIcons.Footprints,
            title = stringResource(Res.string.permission_background_title),
            body = stringResource(Res.string.permission_background_body),
            facts = listOf(PermissionFact(AppIcons.Bell, stringResource(Res.string.onboarding_fp_notification))),
            path = stringResource(Res.string.permission_background_path),
            primary = stringResource(Res.string.permission_open_settings),
            external = true,
            secondary = stringResource(Res.string.permission_background_secondary),
            onPrimary = { askBackground = false; context.openAppSettings() },
            onDismiss = { askBackground = false },
            tag = "permission_background",
        )
        return { action ->
            pendingAction = action
            permissions()
        }
    }

    @Composable
    override fun rememberExport(work: (suspend () -> Unit) -> Unit): (TrackSelection, Boolean, String) -> Unit {
        val context = LocalContext.current
        var exportDay by rememberSaveable { mutableStateOf<String?>(null) }
        var exportSession by rememberSaveable { mutableStateOf<String?>(null) }
        var exportRaw by rememberSaveable { mutableStateOf(false) }
        // ColorOS's file manager names the file from the type: application/gpx+xml became ".gpx+xml"
        // and octet-stream ".bin". Only a wildcard keeps the ".gpx" name as given.
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) work {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open document")
                    output.bufferedWriter(Charsets.UTF_8).use { store.export(TrackSelection(exportDay, exportSession), it, raw = exportRaw) }
                }
            }
        }
        return { selection, raw, name ->
            exportDay = selection.day
            exportSession = selection.session
            exportRaw = raw
            export.launch(name)
        }
    }

    override fun deletable(selection: TrackSelection) = store.deletable(selection)

    override fun delete(selection: TrackSelection) = store.delete(selection)
}
