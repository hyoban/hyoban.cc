package cc.hyoban.moments.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import cc.hyoban.moments.Setting
import cc.hyoban.moments.backup.Backup
import cc.hyoban.moments.backup.BackupFailure
import cc.hyoban.moments.backup.BackupStatus
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// 备份 on 同步与来源: the WebDAV directory, backing up once a day, and backing up now with
// how the last one went.

@Composable
fun BackupSection(backup: Backup, onEdit: () -> Unit) {
    val running by backup.running.collectAsStateWithLifecycle()
    val status by backup.status.collectAsStateWithLifecycle()
    val configured = backup.configured
    val directory: Segment = { shapes ->
        SegmentedListItem(
            onClick = onEdit,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Cloud, contentDescription = null) },
            supportingContent = {
                Text(if (configured) backup.url.value.substringAfter("://") else stringResource(R.string.setting_unset), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            modifier = Modifier.testTag("backup_webdav"),
        ) { Text(stringResource(R.string.setting_webdav)) }
    }
    val auto: Segment = { shapes ->
        SwitchRow(
            checked = configured && backup.auto,
            icon = AppIcons.Clock,
            title = stringResource(R.string.backup_auto),
            tag = "backup_auto",
            shapes = shapes,
            supporting = stringResource(R.string.backup_auto_note),
            enabled = configured,
        ) { backup.changeAuto(!backup.auto) }
    }
    val context = LocalContext.current
    val now: Segment = { shapes ->
        SegmentedListItem(
            onClick = { if (!running) backup.backUpNow() },
            enabled = configured,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.CloudUpload, contentDescription = null) },
            supportingContent = {
                Text(
                    when {
                        running -> stringResource(R.string.backup_running)
                        !configured -> stringResource(R.string.backup_needs_webdav)
                        else -> status?.let { statusText(context, it) } ?: stringResource(R.string.backup_never)
                    },
                )
            },
            trailingContent = if (running) {
                { Spinner(Modifier.size(24.dp)) }
            } else {
                null
            },
            modifier = Modifier.testTag("backup_now"),
        ) { Text(stringResource(R.string.action_backup_now)) }
    }
    SegmentedGroup(stringResource(R.string.section_backup), stringResource(R.string.settings_backup_note), listOf(directory, auto, now))
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** `今天 03:12 已备份`, or when it failed and why. */
private fun statusText(context: Context, status: BackupStatus): String {
    val at = Instant.ofEpochMilli(status.at).atZone(ZoneId.systemDefault())
    val day = if (at.toLocalDate() == LocalDate.now()) context.getString(R.string.label_today) else formatDate(at.toLocalDate().toString())
    val time = "$day ${at.format(TIME)}"
    val failure = status.failure ?: return context.getString(R.string.backup_done, time)
    val reason = when (failure) {
        BackupFailure.Auth -> R.string.backup_failure_auth
        BackupFailure.NotFound -> R.string.backup_failure_not_found
        BackupFailure.Server -> R.string.backup_failure_server
        BackupFailure.Unreachable -> R.string.backup_failure_unreachable
        BackupFailure.Other -> R.string.backup_failure_other
    }
    return context.getString(R.string.backup_failed, time, context.getString(reason))
}

/**
 * The WebDAV directory, its user name and password in one dialog; a password already kept
 * isn't shown again, only replaced. 停用备份, while there is one, forgets them all.
 */
@Composable
fun WebDavDialog(backup: Backup, onDismiss: () -> Unit, onForget: () -> Unit, onSave: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf(backup.url.value) }
    var user by remember { mutableStateOf(backup.user.value) }
    val password = rememberTextFieldState()
    var hidden by remember { mutableStateOf(true) }
    val ok = url.isNotBlank() && Setting.normalize(url).toHttpUrlOrNull() != null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { if (ok) onSave(url, user, password.text.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_webdav)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://") },
                    label = { Text(stringResource(R.string.webdav_directory)) },
                    isError = url.isNotBlank() && !ok,
                    supportingText = {
                        Text(stringResource(if (url.isNotBlank() && !ok) R.string.error_address else R.string.webdav_directory_note))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("webdav_url"),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.webdav_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("webdav_user"),
                )
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(R.string.webdav_password)) },
                    placeholder = if (backup.password.isSet) {
                        { Text(stringResource(R.string.webdav_password_set)) }
                    } else {
                        null
                    },
                    textObfuscationMode = if (hidden) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
                    trailingIcon = {
                        val label = stringResource(if (hidden) R.string.action_show_password else R.string.action_hide_password)
                        Tooltip(label) {
                            IconButton(onClick = { hidden = !hidden }) {
                                Icon(if (hidden) AppIcons.Eye else AppIcons.EyeOff, contentDescription = label)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { save() },
                    modifier = Modifier.fillMaxWidth().testTag("webdav_password"),
                )
                if (backup.configured) {
                    TextButton(
                        onClick = onForget,
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                        modifier = Modifier.testTag("webdav_forget"),
                    ) { Text(stringResource(R.string.action_stop_backup)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = ok, modifier = Modifier.testTag("webdav_save")) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
