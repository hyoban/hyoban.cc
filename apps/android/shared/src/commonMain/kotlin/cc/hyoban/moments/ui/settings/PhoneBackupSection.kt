package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.backup.BackupRead
import cc.hyoban.moments.backup.PhoneBackup
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDate
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 同步与来源's WebDAV backup on a desktop or a tablet (D25): where the phone backs up to, and
 * reading its latest backup again, the ledger and the footprints shown here coming from it alone.
 */
@Composable
fun PhoneBackupSection(backup: PhoneBackup) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    val busy by backup.busy.collectAsStateWithLifecycle()
    val read by backup.read.collectAsStateWithLifecycle()
    val address: Segment = { shapes ->
        SegmentedListItem(
            onClick = { editing = true },
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.CloudSync, contentDescription = null) },
            supportingContent = {
                Text(
                    if (backup.configured) backup.url.value.substringAfter("://") else stringResource(Res.string.setting_unset),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.testTag("phone_backup_address"),
        ) { Text(stringResource(Res.string.webdav_address)) }
    }
    val refresh: Segment = { shapes ->
        SegmentedListItem(
            onClick = { if (!busy) scope.launch { backup.refresh() } },
            enabled = backup.configured,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
            supportingContent = { Text(readText(read, busy)) },
            trailingContent = if (busy) {
                { Spinner(Modifier.size(24.dp)) }
            } else {
                null
            },
            modifier = Modifier.testTag("phone_backup_read"),
        ) { Text(stringResource(Res.string.webdav_read)) }
    }
    SegmentedGroup(stringResource(Res.string.webdav_title), stringResource(Res.string.webdav_note), listOf(address, refresh))

    if (editing) {
        WebDavDialog(
            backup.url.value,
            backup.user.value,
            passwordSet = backup.password.isSet,
            configured = backup.configured,
            onDismiss = { editing = false },
            onForget = {
                backup.change("", "", "")
                editing = false
            },
            forget = Res.string.action_forget,
            note = Res.string.webdav_note,
        ) { url, user, password ->
            backup.change(url, user, password)
            editing = false
            scope.launch { backup.refresh() }
        }
    }
}

/** What the last read of the backup came to, said under its row. */
@Composable
private fun readText(read: BackupRead?, busy: Boolean): String = when {
    busy -> stringResource(Res.string.webdav_reading)
    read?.failure != null -> stringResource(Res.string.webdav_failed, read.failure)
    read?.madeAt != null -> stringResource(Res.string.desktop_backup_at, formatDate(read.madeAt.toLocalDate().toString()) + " " + read.madeAt.toLocalTime().toString().take(5))
    else -> stringResource(Res.string.desktop_no_backup)
}

/** 初次打开's way to start from the phone's backup: its WebDAV directory asked for, then read, and [next] on. */
@Composable
fun phoneBackupStart(backup: PhoneBackup, next: () -> Unit): Segment {
    var editing by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (editing) {
        WebDavDialog(
            backup.url.value,
            backup.user.value,
            passwordSet = backup.password.isSet,
            configured = backup.configured,
            onDismiss = { editing = false },
            onForget = {
                backup.change("", "", "")
                editing = false
            },
            forget = Res.string.action_forget,
            note = Res.string.webdav_note,
        ) { url, user, password ->
            backup.change(url, user, password)
            editing = false
            scope.launch { backup.refresh() }
            next()
        }
    }
    return { shapes ->
        SegmentedListItem(
            onClick = { editing = true },
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.CloudSync, contentDescription = null) },
            supportingContent = { Text(stringResource(Res.string.onboarding_backup_note)) },
            trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.testTag("onboarding_backup"),
        ) { Text(stringResource(Res.string.onboarding_backup)) }
    }
}
