package cc.hyoban.moments.footprints

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (granted) change(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        available = ActivityTransitions.available(context)
        permitted = ActivityTransitions.permitted(context)
        enabled = ActivityTransitions.enabled(context)
        RecordingService.refreshActivityRecognition(context)
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.footprints_activity_title), Modifier.weight(1f))
                Switch(enabled, onCheckedChange = { next ->
                    if (next && Build.VERSION.SDK_INT >= 29 && !permitted) permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    else change(next)
                }, enabled = available || enabled, modifier = Modifier.testTag("footprints_activity_enabled"))
            }
            Text(stringResource(when {
                !available -> R.string.footprints_activity_unavailable
                !enabled -> R.string.footprints_activity_off
                !permitted -> R.string.footprints_activity_permission
                else -> status
            }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.footprints_activity_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
