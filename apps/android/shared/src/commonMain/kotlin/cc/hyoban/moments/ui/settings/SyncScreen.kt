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
 * 同步与来源: the API, its token and reading from it again; the keys the map's place search uses;
 * the model a plan is talked over with, and, where the device reads it, Xiaohongshu, signed in to by [onNoteSignIn].
 */
@Composable
fun SyncScreen(container: AppContainer, onBack: () -> Unit, onNoteSignIn: (() -> Unit)? = null) {
    val settings = container.settings
    val device = cc.hyoban.moments.ui.LocalDevice.current
    // The setting whose dialog is open, by its place in the list, so it stays open across a rotation.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingToken by rememberSaveable { mutableStateOf(false) }
    var editingTencentKey by rememberSaveable { mutableStateOf(false) }
    var editingAmapKey by rememberSaveable { mutableStateOf(false) }
    var editingAiKey by rememberSaveable { mutableStateOf(false) }
    var editingAiModel by rememberSaveable { mutableStateOf(false) }
    var editingAiNotes by rememberSaveable { mutableStateOf(false) }
    val rows = listOf(
        Row(settings.apiUrl, Res.string.setting_api_url, AppIcons.Cloud) { container.changeApi(it) },
        Row(settings.aiUrl, Res.string.setting_ai_url, AppIcons.Cloud) { settings.aiUrl.set(it) },
    )

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
                Section(stringResource(Res.string.section_site), rows.subList(0, 1), 0, stringResource(Res.string.settings_api_note), extra = listOf(token, refresh)) { editing = it }
                device.StorageSettings(container)
                // The keys the place picker asks AMap and Tencent with in China, kept beside the map's styles, and,
                // with both, which it asks first: a menu under the row.
                val chinaPlaces = SettingsRow.Choice(
                    AppIcons.Pin, stringResource(Res.string.setting_china_places), "china_places",
                    listOf(CHINA_PLACES_AMAP to stringResource(Res.string.setting_amap), CHINA_PLACES_TENCENT to stringResource(Res.string.setting_tencent_maps)),
                    settings.chinaPlaces.value,
                ) { settings.chinaPlaces.set(it) }
                val amapKey = SettingsRow.Value(
                    AppIcons.Key, stringResource(Res.string.setting_amap_key), "amap_key",
                    stringResource(if (settings.amapKey.isSet) Res.string.token_set else Res.string.token_unset),
                    maxLines = Int.MAX_VALUE,
                ) { editingAmapKey = true }
                val tencentKey = SettingsRow.Value(
                    AppIcons.Key, stringResource(Res.string.setting_tencent_key), "tencent_key",
                    stringResource(if (settings.tencentMapKey.isSet) Res.string.token_set else Res.string.token_unset),
                    maxLines = Int.MAX_VALUE,
                ) { editingTencentKey = true }
                Section(stringResource(Res.string.section_map), emptyList(), 0, stringResource(Res.string.settings_map_note), extra = listOfNotNull(amapKey, tencentKey, chinaPlaces.takeIf { settings.amapKey.isSet && settings.tencentMapKey.isSet })) { editing = it }
                // The model a plan is talked over with: its address, the owner's key and the model's name.
                val aiKey = SettingsRow.Value(
                    AppIcons.Key, stringResource(Res.string.setting_ai_key), "ai_key",
                    stringResource(if (settings.aiKey.isSet) Res.string.token_set else Res.string.token_unset),
                    maxLines = Int.MAX_VALUE,
                ) { editingAiKey = true }
                val aiModel = SettingsRow.Value(
                    AppIcons.Sliders, stringResource(Res.string.setting_ai_model), "ai_model",
                    settings.aiModel.value.ifEmpty { stringResource(Res.string.setting_unset) },
                ) { editingAiModel = true }
                // What the owner tells the model besides, its first line shown.
                val aiNotes = SettingsRow.Value(
                    AppIcons.Notes, stringResource(Res.string.setting_ai_notes), "ai_notes",
                    settings.aiNotes.value.trim().lineSequence().first().ifEmpty { stringResource(Res.string.ai_notes_unset) },
                ) { editingAiNotes = true }
                // Xiaohongshu, for the model to search its notes: signed in to on its own page.
                val noteSearch = container.noteSearch
                val xiaohongshu = if (noteSearch != null && onNoteSignIn != null) {
                    SettingsRow.Page(
                        AppIcons.Xiaohongshu, stringResource(Res.string.setting_xiaohongshu), "xiaohongshu",
                        supporting = stringResource(if (noteSearch.signedIn.value) Res.string.xiaohongshu_signed_in else Res.string.xiaohongshu_signed_out),
                        onClick = onNoteSignIn,
                    )
                } else {
                    null
                }
                val aiNote = stringResource(Res.string.settings_ai_note).let { note ->
                    if (xiaohongshu != null) note + stringResource(Res.string.settings_ai_note_xiaohongshu) else note
                }
                Section(stringResource(Res.string.section_ai), rows.subList(1, 2), 1, aiNote, extra = listOfNotNull(aiKey, aiModel, aiNotes, xiaohongshu)) { editing = it }
                device.UpdateSettings(container)
            }
        }
    }

    if (editingToken) {
        TokenDialog(settings.apiToken, TokenWords.Api, "api_token", onDismiss = { editingToken = false }) { text ->
            settings.apiToken.set(text)
            editingToken = false
        }
    }

    if (editingTencentKey) {
        TokenDialog(settings.tencentMapKey, TokenWords.Tencent, "tencent_key", onDismiss = { editingTencentKey = false }) { text ->
            settings.tencentMapKey.set(text)
            editingTencentKey = false
        }
    }

    if (editingAmapKey) {
        TokenDialog(settings.amapKey, TokenWords.Amap, "amap_key", onDismiss = { editingAmapKey = false }) { text ->
            settings.amapKey.set(text)
            editingAmapKey = false
        }
    }

    if (editingAiKey) {
        TokenDialog(settings.aiKey, TokenWords.Ai, "ai_key", onDismiss = { editingAiKey = false }) { text ->
            settings.aiKey.set(text)
            editingAiKey = false
        }
    }

    if (editingAiModel) {
        ModelDialog(settings.aiModel.value, settings.aiUrl.value, settings.aiKey.value, onDismiss = { editingAiModel = false }) { text ->
            settings.aiModel.set(text)
            editingAiModel = false
        }
    }

    if (editingAiNotes) {
        NotesDialog(settings.aiNotes.value, onDismiss = { editingAiNotes = false }) { text ->
            settings.aiNotes.set(text.trim())
            editingAiNotes = false
        }
    }

    editing?.let { index ->
        val row = rows[index]
        AddressDialog(row.setting, stringResource(row.title), onDismiss = { editing = null }) { text ->
            row.save(text)
            editing = null
        }
    }
}

/** A setting as a row: its name, its icon, and what saving it does. */
private class Row(val setting: Setting, val title: StringResource, val icon: ImageVector, val save: (String) -> Unit)

/** Rows as one segmented group under a caption, and a note under them; [extra] rows after them. */
@Composable
private fun Section(title: String, rows: List<Row>, first: Int, note: String?, extra: List<SettingsRow> = emptyList(), onEdit: (Int) -> Unit) {
    SettingsGroupView(SettingsGroupSpec(title, note, rows.mapIndexed { index, row -> settingRow(row) { onEdit(first + index) } } + extra))
}

/** An address as a row: where it points, and 默认 at its end while it is the build's own. */
@Composable
private fun settingRow(row: Row, onClick: () -> Unit): SettingsRow {
    val setting = row.setting
    return SettingsRow.Action(
        row.icon, stringResource(row.title), null,
        // The scheme said by the dialog; the row has room only for where it points.
        supporting = if (setting.isSet) setting.value.substringAfter("://") else stringResource(Res.string.setting_unset),
        trailing = if (setting.isDefault && setting.isSet) stringResource(Res.string.setting_is_default) else null,
        maxLines = 1,
        onClick = onClick,
    )
}

/** Blank is the build's own, or none; anything else an http or https address. */
private fun valid(text: String) = text.isBlank() || Setting.normalize(text).toHttpUrlOrNull() != null

/**
 * A setting's address to change, the whole of it selected to type over; cancel and confirm
 * at its foot, as Material 3 always gives a dialog a way out, and under the field, while
 * it differs from the build's own, putting that back, which saves it at once.
 */
@Composable
private fun AddressDialog(setting: Setting, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
private enum class TokenWords(val title: StringResource, val hint: StringResource, val replace: StringResource, val note: StringResource, val forget: StringResource, val show: StringResource, val hide: StringResource) {
    Api(Res.string.setting_api_token, Res.string.token_hint, Res.string.token_replace, Res.string.settings_token_note, Res.string.action_forget_token, Res.string.action_show_token, Res.string.action_hide_token),
    Tencent(Res.string.setting_tencent_key, Res.string.tencent_key_hint, Res.string.tencent_key_replace, Res.string.settings_tencent_key_note, Res.string.action_forget_tencent_key, Res.string.action_show_key, Res.string.action_hide_key),
    Amap(Res.string.setting_amap_key, Res.string.amap_key_hint, Res.string.tencent_key_replace, Res.string.settings_amap_key_note, Res.string.action_forget_tencent_key, Res.string.action_show_key, Res.string.action_hide_key),
    Ai(Res.string.setting_ai_key, Res.string.ai_key_hint, Res.string.tencent_key_replace, Res.string.settings_ai_key_note, Res.string.action_forget_tencent_key, Res.string.action_show_key, Res.string.action_hide_key),
}

/**
 * The model to talk plans over with: typed, the whole of it selected to type over, or picked from
 * what the service offers, asked of it as the dialog opens when its address and key are set and
 * narrowed by what is typed; blank forgets it.
 */
@Composable
private fun ModelDialog(value: String, url: String, key: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The service's models: null while asked, then the list, or why there is none.
    var models by remember { mutableStateOf<List<String>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val canAsk = url.isNotEmpty() && key.isNotEmpty()
    LaunchedEffect(url, key) {
        if (!canAsk) return@LaunchedEffect
        try {
            models = cc.hyoban.moments.data.ai.PlanAssistant.models(url, key)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failure = textOf(Res.string.ai_models_failed, cc.hyoban.moments.ui.format.failureReason(error))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.setting_ai_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    placeholder = { Text(stringResource(Res.string.ai_model_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(field.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("ai_model_field"),
                )
                when {
                    !canAsk -> Text(stringResource(Res.string.ai_models_need_key), style = AppTheme.typography.caption, color = tokens.tx2)
                    failure != null -> Text(failure!!, style = AppTheme.typography.caption, color = tokens.tx2)
                    models == null -> Spinner(Modifier.size(24.dp))
                    else -> {
                        // Narrowed by what is typed, unless what is typed is what was set.
                        val typed = field.text.trim()
                        val shown = models!!.filter { typed.isEmpty() || typed == value || it.contains(typed, ignoreCase = true) }
                        if (shown.isEmpty()) {
                            Text(stringResource(Res.string.ai_models_none), style = AppTheme.typography.caption, color = tokens.tx2)
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("ai_models")) {
                                items(shown.size) { index ->
                                    val model = shown[index]
                                    androidx.compose.foundation.layout.Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable { onSave(model) }
                                            .padding(horizontal = Space.xs),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                                    ) {
                                        Text(model, style = AppTheme.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (model == value) Icon(AppIcons.Check, contentDescription = null, tint = tokens.accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }, modifier = Modifier.testTag("ai_model_save")) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/**
 * 补充说明: what the owner tells the model besides, in a field of four lines growing to ten, a line
 * under it saying where it goes; blank forgets it.
 */
@Composable
private fun NotesDialog(value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.setting_ai_notes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    placeholder = { Text(stringResource(Res.string.ai_notes_hint)) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("ai_notes_field"),
                )
                Text(stringResource(Res.string.ai_notes_note), style = AppTheme.typography.caption, color = tokens.tx2)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }, modifier = Modifier.testTag("ai_notes_save")) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/**
 * A secret, the API's token or a map service's key, to type or paste in the secure text field,
 * its dots shown as what they are on the eye's tap; one already kept isn't shown again, only
 * replaced, or forgotten from under the field, in the error colour, as it can't be had back.
 */
@Composable
private fun TokenDialog(token: Token, words: TokenWords, tag: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
