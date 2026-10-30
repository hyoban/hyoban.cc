package cc.hyoban.moments.ui.moment

import androidx.compose.foundation.layout.fillMaxWidth
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cc.hyoban.moments.R
import cc.hyoban.moments.share.TelegramShareAdapter

@Composable
internal fun TelegramAssistOptions(
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    destination: String,
    onDestination: (String) -> Unit,
    busy: Boolean,
    hasPhotos: Boolean,
) {
    ShareAssistOptions(
        adapter = TelegramShareAdapter,
        tag = "telegram",
        label = R.string.telegram_assist_label,
        running = R.string.telegram_assist_running,
        enabled = enabled,
        onEnabled = onEnabled,
        busy = busy,
    ) {
        OutlinedTextField(
            value = destination,
            onValueChange = onDestination,
            enabled = !busy,
            singleLine = true,
            label = { Text(stringResource(R.string.telegram_assist_target)) },
            modifier = Modifier.fillMaxWidth().testTag("telegram_assist_target"),
        )
        if (!hasPhotos) Text(stringResource(R.string.share_assist_photos_only), style = AppTheme.typography.caption)
    }
}
