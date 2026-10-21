package cc.hyoban.moments.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.hyoban.moments.R

/**
 * Material 3's full-screen dialog, for a task with fields to fill on a phone: the whole
 * screen, its bar holding only the X that leaves it and [actions], the confirming one at the
 * end; the headline, which may be long, at the top of [content] rather than in the bar.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Edge to edge, its own colour under the status and navigation bars, as the pages are.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // A window of its own, so its tags are resource ids again for `uiautomator`.
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = AppColors.page) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val close = stringResource(R.string.action_close)
                    Tooltip(close, below = true) {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("dialog_close")) {
                            Icon(Icons.Rounded.Close, contentDescription = close)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                content()
            }
        }
    }
}
