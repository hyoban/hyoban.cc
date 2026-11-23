package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.Spinner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.IconButtonDefaults
import cc.hyoban.moments.ui.design.ButtonDefaults
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.IconButton
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedSecureTextField
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.CHINA_PLACES_AMAP
import cc.hyoban.moments.CHINA_PLACES_TENCENT
import cc.hyoban.moments.Setting
import cc.hyoban.moments.Token
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.components.Tooltip
import cc.hyoban.moments.ui.theme.Space
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Where the app reads from and what lets it write, a page under 我: rows grouped on one surface
// under a small caption, a note under the group, and a tap on a row opening a dialog to change it.

/**
 * 同步与来源: the API, its token and reading from it again, then the groups the plugins bring
 * (where the device keeps things beside the API, the map's keys, the model a plan is talked over
 * with, the app's updates); [navigate] opens a page a group leads to, where the device has one.
 */
@Composable
fun SyncScreen(container: AppContainer, onBack: () -> Unit, navigate: ((Any) -> Unit)? = null) {
    val settings = container.settings
    var editingApi by rememberSaveable { mutableStateOf(false) }
    var editingToken by rememberSaveable { mutableStateOf(false) }

    SubPage(stringResource(Res.string.sync_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                val address = addressRow(settings.apiUrl, Res.string.setting_api_url, AppIcons.Cloud) { editingApi = true }
                // The token publishing takes; the changes themselves wait at the top of 我.
                val token = SettingsRow.Value(
                    AppIcons.Key, stringResource(Res.string.setting_api_token), "api_token",
                    stringResource(if (settings.apiToken.isSet) Res.string.token_set else Res.string.token_unset),
                    maxLines = Int.MAX_VALUE,
                ) { editingToken = true }
                // Reading from the API again with a tap, as a refresh pulled for on the tabs is had without the gesture too.
                val syncing by container.repository.syncing.collectAsStateWithLifecycle()
                val connected = settings.connected
                val refresh = SettingsRow.Action(
                    AppIcons.Refresh, stringResource(Res.string.action_refresh), "refresh",
                    supporting = if (connected && !syncing) null else stringResource(if (syncing) Res.string.refreshing else Res.string.refresh_needs_api),
                    enabled = connected,
                    busy = syncing,
                ) { if (!syncing) container.refresh() }
                SettingsGroupView(SettingsGroupSpec(stringResource(Res.string.section_site), stringResource(Res.string.settings_api_note), listOf(address, token, refresh)))
                PluginGroups(container, cc.hyoban.moments.plugins.SettingsPage.SYNC, cc.hyoban.moments.plugins.SettingsPageNav(navigate))
            }
        }
    }

    if (editingToken) {
        TokenDialog(settings.apiToken, API_TOKEN, "api_token", onDismiss = { editingToken = false }) { text ->
            settings.apiToken.set(text)
            editingToken = false
        }
    }

    if (editingApi) {
        AddressDialog(settings.apiUrl, stringResource(Res.string.setting_api_url), onDismiss = { editingApi = false }) { text ->
            container.changeApi(text)
            editingApi = false
        }
    }
}

/** What the API token's dialog calls it, asks for and says it is for. */
private val API_TOKEN = TokenWords(Res.string.setting_api_token, Res.string.token_hint, Res.string.token_replace, Res.string.settings_token_note, Res.string.action_forget_token, Res.string.action_show_token, Res.string.action_hide_token)

/** An address as a row: where it points, and 默认 at its end while it is the build's own. */
@Composable
internal fun addressRow(setting: Setting, title: StringResource, icon: ImageVector, onClick: () -> Unit): SettingsRow =
    SettingsRow.Action(
        icon, stringResource(title), null,
        // The scheme said by the dialog; the row has room only for where it points.
        supporting = if (setting.isSet) setting.value.substringAfter("://") else stringResource(Res.string.setting_unset),
        trailing = if (setting.isDefault && setting.isSet) stringResource(Res.string.setting_is_default) else null,
        maxLines = 1,
        onClick = onClick,
    )

/** Blank is the build's own, or none; anything else an http or https address. */
private fun valid(text: String) = text.isBlank() || Setting.normalize(text).toHttpUrlOrNull() != null

/**
 * A setting's address to change, the whole of it selected to type over; cancel and confirm
 * at its foot, as Material 3 always gives a dialog a way out, and under the field, while
 * it differs from the build's own, putting that back, which saves it at once.
 */
@Composable
internal fun AddressDialog(setting: Setting, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(setting.value, TextRange(0, setting.value.length))) }
    val ok = valid(field.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    placeholder = { Text(setting.default.ifEmpty { "https://" }) },
                    isError = !ok,
                    // The error marked by an icon as well as the colour, as Material 3 asks of a field in error.
                    trailingIcon = if (ok) null else {
                        { Icon(AppIcons.Alert, contentDescription = null) }
                    },
                    supportingText = {
                        Text(
                            when {
                                !ok -> stringResource(Res.string.error_address)
                                setting.default.isEmpty() -> stringResource(Res.string.setting_blank_none)
                                else -> stringResource(Res.string.setting_default, setting.default)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    // Done on the keyboard, or Enter on one plugged in, saves as confirm does.
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(field.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (setting.default.isNotEmpty() && Setting.normalize(field.text) != setting.default) {
                    TextButton(onClick = { onSave(setting.default) }, modifier = Modifier.testTag("restore_default")) {
                        Text(stringResource(Res.string.action_restore_default))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }, enabled = ok) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** What a secret's dialog calls it, asks for and says it is for. */
internal class TokenWords(val title: StringResource, val hint: StringResource, val replace: StringResource, val note: StringResource, val forget: StringResource, val show: StringResource, val hide: StringResource)

/**
 * A secret, the API's token or a map service's key, to type or paste in the secure text field,
 * its dots shown as what they are on the eye's tap; one already kept isn't shown again, only
 * replaced, or forgotten from under the field, in the error colour, as it can't be had back.
 */
@Composable
internal fun TokenDialog(token: Token, words: TokenWords, tag: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val field = rememberTextFieldState()
    var hidden by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(words.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedSecureTextField(
                    state = field,
                    placeholder = { Text(stringResource(if (token.isSet) words.replace else words.hint)) },
                    textObfuscationMode = if (hidden) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
                    trailingIcon = {
                        val label = stringResource(if (hidden) words.show else words.hide)
                        Tooltip(label) {
                            IconButton(onClick = { hidden = !hidden }, modifier = Modifier.testTag("${tag}_reveal")) {
                                Icon(if (hidden) AppIcons.Eye else AppIcons.EyeOff, contentDescription = label)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { field.text.toString().takeIf { it.isNotBlank() }?.let(onSave) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("${tag}_field"),
                )
                // What the secret is for, said where it is set.
                Text(stringResource(words.note), style = AppTheme.typography.caption, color = tokens.tx2)
                if (token.isSet) {
                    TextButton(
                        onClick = { onSave("") },
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                        modifier = Modifier.testTag("${tag}_forget"),
                    ) { Text(stringResource(words.forget)) }
                }
            }
        },
        confirmButton = {
            val text = field.text.toString()
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("${tag}_save")) {
                Text(stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
