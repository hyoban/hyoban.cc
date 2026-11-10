package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.Setting
import cc.hyoban.moments.backup.Backup
import cc.hyoban.moments.backup.BackupArchive
import cc.hyoban.moments.backup.BackupFailure
import cc.hyoban.moments.backup.BackupStatus
import cc.hyoban.moments.backup.DavFile
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.format.formatDate
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// 备份 on 同步与来源: the WebDAV directory, backing up once a day, backing up now with
// how the last one went, and restoring one of the backups there.

@Composable
fun BackupSection(backup: Backup, onEdit: () -> Unit, onRestore: () -> Unit) {
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
                Text(if (configured) backup.url.value.substringAfter("://") else stringResource(Res.string.setting_unset), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            modifier = Modifier.testTag("backup_webdav"),
        ) { Text(stringResource(Res.string.setting_webdav)) }
    }
    val auto: Segment = { shapes ->
        SwitchRow(
            checked = configured && backup.auto,
            icon = AppIcons.Clock,
            title = stringResource(Res.string.backup_auto),
            tag = "backup_auto",
            shapes = shapes,
            supporting = stringResource(Res.string.backup_auto_note),
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
                        running -> stringResource(Res.string.backup_running)
                        !configured -> stringResource(Res.string.backup_needs_webdav)
                        else -> status?.let { statusText(context, it) } ?: stringResource(Res.string.backup_never)
                    },
                )
            },
            trailingContent = if (running) {
                { Spinner(Modifier.size(24.dp)) }
            } else {
                null
            },
            modifier = Modifier.testTag("backup_now"),
        ) { Text(stringResource(Res.string.action_backup_now)) }
    }
    val restore: Segment = { shapes ->
        SegmentedListItem(
            onClick = onRestore,
            enabled = configured && !running,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Download, contentDescription = null) },
            supportingContent = { Text(stringResource(Res.string.restore_note)) },
            modifier = Modifier.testTag("backup_restore"),
        ) { Text(stringResource(Res.string.action_restore_backup)) }
    }
    SegmentedGroup(stringResource(Res.string.section_backup), stringResource(Res.string.settings_backup_note), listOf(directory, auto, now, restore))
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** `今天 03:12`, or the day and time. */
internal fun timeText(context: Context, at: LocalDateTime): String {
    val day = if (at.toLocalDate() == LocalDate.now()) textOf(Res.string.label_today) else formatDate(at.toLocalDate().toString())
    return "$day ${at.format(TIME)}"
}

/** `今天 03:12 已备份`, or when it failed and why. */
private fun statusText(context: Context, status: BackupStatus): String {
    val time = timeText(context, Instant.ofEpochMilli(status.at).atZone(ZoneId.systemDefault()).toLocalDateTime())
    val failure = status.failure ?: return textOf(Res.string.backup_done, time)
    return textOf(Res.string.backup_failed, time, textOf(reasonOf(failure)))
}

private fun reasonOf(failure: BackupFailure) = when (failure) {
    BackupFailure.Auth -> Res.string.backup_failure_auth
    BackupFailure.NotFound -> Res.string.backup_failure_not_found
    BackupFailure.Server -> Res.string.backup_failure_server
    BackupFailure.Unreachable -> Res.string.backup_failure_unreachable
    BackupFailure.Other -> Res.string.backup_failure_other
}

/**
 * The backups in the WebDAV directory, newest first, to pick one and restore it: the phone's
 * data is replaced by it and the app starts again, so it closes only when that failed.
 */
@Composable
fun RestoreDialog(backup: Backup, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Null while the list is read; a failure says why it couldn't be read or restored.
    var backups by remember { mutableStateOf<List<DavFile>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(backup) {
        try {
            backups = backup.backups().also { picked = it.firstOrNull()?.name }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            backups = emptyList()
            failure = textOf(Res.string.restore_list_failed, textOf(reasonOf(Backup.failureOf(error))))
        }
    }
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text(stringResource(Res.string.action_restore_backup)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val list = backups
                when {
                    list == null -> Spinner(Modifier.size(24.dp))
                    list.isEmpty() && failure == null -> Text(stringResource(Res.string.restore_none))
                    else -> list.forEach { file ->
                        val name = file.name
                        val at = BackupArchive.madeAt(name)
                        ListItem(
                            headlineContent = { Text(at?.let { timeText(context, it) } ?: name) },
                            supportingContent = file.size?.let { size -> { Text(Formatter.formatShortFileSize(context, size)) } },
                            trailingContent = if (name == picked) {
                                { Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent) }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .selectable(selected = name == picked, enabled = !restoring, role = Role.RadioButton) { picked = name }
                                .testTag("restore_$name"),
                        )
                    }
                }
                failure?.let { Text(it, color = tokens.danger, modifier = Modifier.padding(top = Space.m)) }
                if (!list.isNullOrEmpty()) {
                    Text(
                        stringResource(if (restoring) Res.string.restore_running else Res.string.restore_dialog_note),
                        style = AppTheme.typography.caption,
                        modifier = Modifier.padding(top = Space.m),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = picked ?: return@TextButton
                    restoring = true
                    failure = null
                    scope.launch {
                        try {
                            backup.restore(name)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            failure = textOf(Res.string.restore_failed, textOf(reasonOf(Backup.failureOf(error))))
                        } finally {
                            restoring = false
                        }
                    }
                },
                enabled = picked != null && !restoring,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                modifier = Modifier.testTag("restore_confirm"),
            ) { Text(stringResource(Res.string.action_restore)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !restoring) { Text(stringResource(Res.string.action_cancel)) } },
    )
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
        title = { Text(stringResource(Res.string.setting_webdav)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://") },
                    label = { Text(stringResource(Res.string.webdav_directory)) },
                    isError = url.isNotBlank() && !ok,
                    supportingText = {
                        Text(stringResource(if (url.isNotBlank() && !ok) Res.string.error_address else Res.string.webdav_directory_note))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("webdav_url"),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(Res.string.webdav_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("webdav_user"),
                )
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(Res.string.webdav_password)) },
                    placeholder = if (backup.password.isSet) {
                        { Text(stringResource(Res.string.webdav_password_set)) }
                    } else {
                        null
                    },
                    textObfuscationMode = if (hidden) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
                    trailingIcon = {
                        val label = stringResource(if (hidden) Res.string.action_show_password else Res.string.action_hide_password)
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
                    ) { Text(stringResource(Res.string.action_stop_backup)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = ok, modifier = Modifier.testTag("webdav_save")) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
