package cc.hyoban.moments.footprints

import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun ActivityRecognitionSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ActivityTransitions.enabled(context)) }
    var available by remember { mutableStateOf(ActivityTransitions.available(context)) }
    var permitted by remember { mutableStateOf(ActivityTransitions.permitted(context)) }
    val status by ActivityTransitions.status.collectAsState()
    fun change(value: Boolean) { enabled = value; ActivityTransitions.setEnabled(context, value) }
    val permission = cc.hyoban.moments.ui.components.rememberPermissionAsk(
        arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
        cc.hyoban.moments.ui.components.PermissionWords(
            AppIcons.Walk,
            stringResource(Res.string.permission_activity_title),
            stringResource(Res.string.permission_activity_body),
            stringResource(Res.string.permission_not_now),
            listOf(cc.hyoban.moments.ui.design.PermissionFact(AppIcons.Phone, stringResource(Res.string.permission_activity_fact))),
        ),
    ) { granted ->
        permitted = granted
        if (granted) change(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        available = ActivityTransitions.available(context)
        permitted = ActivityTransitions.permitted(context)
        enabled = ActivityTransitions.enabled(context)
        RecordingService.refreshActivityRecognition(context)
    }
    SegmentedGroup(
        stringResource(Res.string.footprints_activity_section),
        stringResource(Res.string.footprints_activity_note),
        listOf { shapes ->
            SwitchRow(
                enabled,
                AppIcons.Walk,
                stringResource(Res.string.footprints_activity_title),
                "footprints_activity_enabled",
                shapes,
                supporting = stringResource(when {
                    !available -> Res.string.footprints_activity_unavailable
                    !enabled -> Res.string.footprints_activity_off
                    !permitted -> Res.string.footprints_activity_permission
                    else -> status
                }),
                enabled = available || enabled,
            ) {
                if (!enabled && !permitted) permission()
                else change(!enabled)
            }
        },
    )
}
