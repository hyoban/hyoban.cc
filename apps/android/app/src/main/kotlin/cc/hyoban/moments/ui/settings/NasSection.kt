package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.R
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens

// 绿联 NAS on 同步与来源: the NAS whose photo app 选照片 reads, by its UGREENlink ID or an
// address, and who signs in.

@Composable
fun NasSection(nas: Nas, onEdit: () -> Unit) {
    val row: Segment = { shapes ->
        SegmentedListItem(
            onClick = onEdit,
            shapes = shapes,
            colors = segmentColors(),
            leadingContent = { Icon(AppIcons.Nas, contentDescription = null) },
            supportingContent = {
                Text(
                    if (nas.configured) stringResource(R.string.nas_as, nas.link.value, nas.user.value) else stringResource(R.string.setting_unset),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.testTag("nas_setting"),
        ) { Text(stringResource(R.string.nas_photos)) }
    }
    SegmentedGroup(stringResource(R.string.section_nas), stringResource(R.string.settings_nas_note), listOf(row))
}

/**
 * The NAS's UGREENlink ID or address, the user and the password in one dialog; a password
 * already kept isn't shown again, only replaced. 停用, while there is one, forgets them all.
 */
@Composable
fun NasDialog(nas: Nas, onDismiss: () -> Unit, onForget: () -> Unit, onSave: (String, String, String) -> Unit) {
    var link by remember { mutableStateOf(nas.link.value) }
    var user by remember { mutableStateOf(nas.user.value) }
    val password = rememberTextFieldState()
    var hidden by remember { mutableStateOf(true) }
    val ok = link.isNotBlank() && user.isNotBlank() && (nas.password.isSet || password.text.isNotEmpty())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { if (ok) onSave(link, user, password.text.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_nas)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.nas_link)) },
                    supportingText = { Text(stringResource(R.string.nas_link_note)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("nas_link"),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.webdav_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("nas_user"),
                )
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(R.string.webdav_password)) },
                    placeholder = if (nas.password.isSet) {
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
                    modifier = Modifier.fillMaxWidth().testTag("nas_password"),
                )
                if (nas.configured) {
                    TextButton(
                        onClick = onForget,
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                        modifier = Modifier.testTag("nas_forget"),
                    ) { Text(stringResource(R.string.action_stop_nas)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = ok, modifier = Modifier.testTag("nas_save")) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
