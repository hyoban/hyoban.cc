package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.Setting
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jetbrains.compose.resources.StringResource

/**
 * A WebDAV directory and who signs in to it: the phone's to back up to, a computer's to read the
 * phone's backup from. A blank password keeps the one there is; [onForget] takes it all away.
 */
@Composable
fun WebDavDialog(
    url: String,
    user: String,
    passwordSet: Boolean,
    configured: Boolean,
    onDismiss: () -> Unit,
    onForget: () -> Unit,
    forget: StringResource = Res.string.action_stop_backup,
    note: StringResource = Res.string.webdav_directory_note,
    onSave: (String, String, String) -> Unit,
) {
    var url by remember { mutableStateOf(url) }
    var user by remember { mutableStateOf(user) }
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
                        Text(stringResource(if (url.isNotBlank() && !ok) Res.string.error_address else note))
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
                    placeholder = if (passwordSet) {
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
                if (configured) {
                    TextButton(
                        onClick = onForget,
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                        modifier = Modifier.testTag("webdav_forget"),
                    ) { Text(stringResource(forget)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = ok, modifier = Modifier.testTag("webdav_save")) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
