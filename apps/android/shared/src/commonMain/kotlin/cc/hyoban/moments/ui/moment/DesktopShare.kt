package cc.hyoban.moments.ui.moment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.DesktopDialog
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.Toolbar
import cc.hyoban.moments.ui.design.VerticalDivider
import cc.hyoban.moments.ui.rememberShowMessage
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.stringResource

/**
 * Sharing a Moment on a desktop (D04): a centred dialog, what goes out on the left, where to on
 * the right, each named by what happens at the other end. A computer posts nowhere by itself, so
 * it copies the words and keeps the photos in a folder for whatever app they go to next;
 * [savePhotos] does that the device's way, and [more] hands the Moment to the system's own
 * sharing where the device has one.
 */
@Composable
fun DesktopShareDialog(
    moment: MomentRecord,
    urls: MediaUrls,
    place: String?,
    onDismiss: () -> Unit,
    savePhotos: ((List<String>) -> Unit)?,
    more: (() -> Unit)? = null,
) {
    val device = LocalDevice.current
    val show = rememberShowMessage()
    val copied = stringResource(Res.string.message_text_copied)
    val photos = moment.media.mapNotNull { media ->
        media.published?.let { urls.image(it, ORIGINAL_WIDTH) } ?: media.local?.uri
    }
    DesktopDialog(560.dp, onDismiss) {
        Column {
            Toolbar(
                title = { Text(stringResource(Res.string.action_share)) },
                actions = {
                    val close = stringResource(Res.string.action_close)
                    Tooltip(close) { IconButton(onClick = onDismiss, modifier = Modifier.testTag("share_close")) { Icon(AppIcons.Close, contentDescription = close) } }
                },
            )
            Row(Modifier.fillMaxWidth()) {
                // What goes out: its first photo and its words.
                Column(
                    Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    moment.media.firstOrNull()?.let { first ->
                        MediaStill(first, urls, Modifier.fillMaxWidth().aspectRatio(1f).clip(AppTheme.shapes.medium))
                    }
                    if (moment.text.isNotBlank()) Text(moment.text, style = AppTheme.typography.body, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    place?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx2) }
                }
                VerticalDivider()
                Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                    if (moment.text.isNotBlank()) {
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.action_copy_text)) },
                            leadingContent = { Icon(AppIcons.Copy, contentDescription = null) },
                            modifier = Modifier.testTag("share_copy").clickableRow {
                                device.copyText(moment.text)
                                show(copied)
                            },
                        )
                    }
                    if (savePhotos != null && photos.isNotEmpty()) {
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.desktop_save_photos)) },
                            supportingContent = { Text(stringResource(Res.string.desktop_save_photos_note, photos.size)) },
                            leadingContent = { Icon(AppIcons.Photos, contentDescription = null) },
                            modifier = Modifier.testTag("share_save").clickableRow { savePhotos(photos) },
                        )
                    }
                    if (more != null) {
                        ListItem(
                            headlineContent = { Text(stringResource(Res.string.desktop_share_more)) },
                            leadingContent = { Icon(AppIcons.Share, contentDescription = null) },
                            modifier = Modifier.testTag("share_more").clickableRow(more),
                        )
                    }
                }
            }
        }
    }
}

/** A row that does [action] when clicked, washed as it is pressed or hovered. */
private fun Modifier.clickableRow(action: () -> Unit): Modifier = clickable(onClick = action)

/** The width asked of a published photo kept in full. */
private const val ORIGINAL_WIDTH = 4096
