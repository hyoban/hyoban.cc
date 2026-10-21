package cc.hyoban.moments.ui.moment

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.ui.LocalMomentActions
import cc.hyoban.moments.ui.rememberShowMessage

/**
 * What a Moment's card offers beyond opening it, from a long press or a right click, as
 * Material 3 has a menu open where it was pressed, at [at] from the card's bottom start:
 * sharing it, copying its words, and editing it when it can be, as its page's toolbar does.
 */
@Composable
fun MomentMenu(moment: MomentRecord, urls: MediaUrls, at: DpOffset?, onDismiss: () -> Unit) {
    val actions = LocalMomentActions.current
    val context = LocalContext.current
    val show = rememberShowMessage()
    val copied = stringResource(R.string.message_text_copied)
    var sharing by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ShareTarget?>(null) }
    // Kept while it closes, so it doesn't jump to the corner as it goes.
    var shownAt by remember { mutableStateOf(DpOffset.Zero) }
    if (at != null) shownAt = at

    DropdownMenu(expanded = at != null, onDismissRequest = onDismiss, offset = shownAt) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            onClick = {
                onDismiss()
                sharing = true
            },
            modifier = Modifier.testTag("menu_share"),
        )
        if (moment.text.isNotBlank()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_text)) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                onClick = {
                    onDismiss()
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", moment.text))
                    show(copied)
                },
                modifier = Modifier.testTag("menu_copy_text"),
            )
        }
        if (actions != null && actions.canEdit(moment.uid)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit_moment)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    actions.onEdit(moment.uid)
                },
                modifier = Modifier.testTag("menu_edit"),
            )
        }
    }
    if (sharing) {
        ShareMenu(
            onSelect = { sharing = false; target = it },
            onDismiss = { sharing = false },
        )
    }
    target?.let { ShareMoment(moment, urls, it, onDismiss = { target = null }) }
}
