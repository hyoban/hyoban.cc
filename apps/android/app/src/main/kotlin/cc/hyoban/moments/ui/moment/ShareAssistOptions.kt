package cc.hyoban.moments.ui.moment

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.AssistAdapter
import cc.hyoban.moments.accessibility.AssistStatus
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.ui.theme.Space

@Composable
internal fun ShareAssistOptions(
    adapter: AssistAdapter,
    tag: String,
    @StringRes label: Int,
    @StringRes running: Int,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    busy: Boolean,
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val connected by ShareAccessibilityService.connected.collectAsStateWithLifecycle()
    val state by ShareAccessibilityService.state.collectAsStateWithLifecycle()
    val status = state.forAdapter(adapter.id)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(stringResource(label), Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                enabled = !busy,
                modifier = Modifier.testTag("${tag}_assist_toggle"),
            )
        }
        if (enabled) {
            content()
            if (!connected) {
                TextButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.testTag("${tag}_assist_settings"),
                ) {
                    Text(stringResource(R.string.share_assist_enable_service))
                }
            }
            when (status) {
                AssistStatus.Idle -> Unit
                AssistStatus.Running -> {
                    Text(stringResource(running))
                    TextButton(
                        onClick = { ShareAccessibilityService.cancel(adapter.id) },
                        modifier = Modifier.testTag("${tag}_assist_cancel"),
                    ) {
                        Text(stringResource(R.string.share_assist_cancel))
                    }
                }
                AssistStatus.Ready -> Text(stringResource(adapter.readyMessage))
                is AssistStatus.Stopped -> Text(stringResource(adapter.stoppedMessage))
            }
        }
    }
}
