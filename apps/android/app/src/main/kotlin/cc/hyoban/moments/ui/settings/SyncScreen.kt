package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.design.Spinner
import androidx.compose.foundation.background
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
import cc.hyoban.moments.ui.design.ListItemShapes
import cc.hyoban.moments.ui.design.SegmentedListItem
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
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.CHINA_PLACES_AMAP
import cc.hyoban.moments.CHINA_PLACES_TENCENT
import cc.hyoban.moments.ui.design.DropdownMenu
import cc.hyoban.moments.ui.design.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.DpOffset
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

/** 同步与来源: the API, its token and reading from it again; the keys the map's place search uses. */
@Composable
fun SyncScreen(container: PhoneContainer, onBack: () -> Unit) {
    val settings = container.settings
    // The setting whose dialog is open, by its place in the list, so it stays open across a rotation.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingToken by rememberSaveable { mutableStateOf(false) }
    var editingTencentKey by rememberSaveable { mutableStateOf(false) }
    var editingAmapKey by rememberSaveable { mutableStateOf(false) }
    var editingBackup by rememberSaveable { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var editingNas by rememberSaveable { mutableStateOf(false) }
    val rows = listOf(
        Row(settings.apiUrl, Res.string.setting_api_url, AppIcons.Cloud) { container.changeApi(it) },
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
                val token: Segment = { shapes ->
                    SegmentedListItem(
                        onClick = { editingToken = true },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Key, contentDescription = null) },
                        supportingContent = { Text(stringResource(if (settings.apiToken.isSet) Res.string.token_set else Res.string.token_unset)) },
                        modifier = Modifier.testTag("api_token"),
                    ) { Text(stringResource(Res.string.setting_api_token)) }
                }
                // Reading from the API again with a tap, as a refresh pulled for on the tabs is had without the gesture too.
                val syncing by container.repository.syncing.collectAsStateWithLifecycle()
                val connected = settings.connected
                val refresh: Segment = { shapes ->
                    SegmentedListItem(
                        onClick = { if (!syncing) container.refresh() },
                        enabled = connected,
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
                        supportingContent = {
                            Text(stringResource(if (syncing) Res.string.refreshing else if (connected) Res.string.refresh_note else Res.string.refresh_needs_api))
                        },
                        trailingContent = if (syncing) {
                            { Spinner(Modifier.size(24.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("refresh"),
                    ) { Text(stringResource(Res.string.action_refresh)) }
                }
                Section(stringResource(Res.string.section_site), rows.subList(0, 1), 0, stringResource(Res.string.settings_api_note), extra = listOf(token, refresh)) { editing = it }
                BackupSection(container.backup, onEdit = { editingBackup = true }, onRestore = { restoring = true })
                NasSection(container.nas) { editingNas = true }
                // The keys the place picker asks AMap and Tencent with in China, kept beside the map's styles, and,
                // with both, which it asks first: a menu under the row.
                val chinaPlaces: Segment = { shapes ->
                    var open by remember { mutableStateOf(false) }
                    val tencentFirst = settings.chinaPlaces.value == CHINA_PLACES_TENCENT
                    Box {
                        SegmentedListItem(
                            onClick = { open = true },
                            shapes = shapes,
                            colors = segmentColors(),
                            leadingContent = { Icon(AppIcons.Pin, contentDescription = null) },
                            supportingContent = { Text(stringResource(if (tencentFirst) Res.string.setting_tencent_maps else Res.string.setting_amap)) },
                            trailingContent = { Icon(AppIcons.CaretDown, contentDescription = null) },
                            modifier = Modifier.testTag("china_places"),
                        ) { Text(stringResource(Res.string.setting_china_places)) }
                        DropdownMenu(open, onDismissRequest = { open = false }, offset = DpOffset(Space.l, 0.dp)) {
                            listOf(CHINA_PLACES_AMAP to Res.string.setting_amap, CHINA_PLACES_TENCENT to Res.string.setting_tencent_maps).forEach { (value, words) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(words)) },
                                    onClick = { settings.chinaPlaces.set(value); open = false },
                                    trailingIcon = if (settings.chinaPlaces.value == value) {
                                        { Icon(AppIcons.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
                val amapKey: Segment = { shapes ->
                    SegmentedListItem(
                        onClick = { editingAmapKey = true },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Key, contentDescription = null) },
                        supportingContent = { Text(stringResource(if (settings.amapKey.isSet) Res.string.token_set else Res.string.token_unset)) },
                        modifier = Modifier.testTag("amap_key"),
                    ) { Text(stringResource(Res.string.setting_amap_key)) }
                }
                val tencentKey: Segment = { shapes ->
                    SegmentedListItem(
                        onClick = { editingTencentKey = true },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Key, contentDescription = null) },
                        supportingContent = { Text(stringResource(if (settings.tencentMapKey.isSet) Res.string.token_set else Res.string.token_unset)) },
                        modifier = Modifier.testTag("tencent_key"),
                    ) { Text(stringResource(Res.string.setting_tencent_key)) }
                }
                Section(stringResource(Res.string.section_map), emptyList(), 0, stringResource(Res.string.settings_map_note), extra = listOfNotNull(amapKey, tencentKey, chinaPlaces.takeIf { settings.amapKey.isSet && settings.tencentMapKey.isSet })) { editing = it }
                UpdateSection(container.updates, container.scope)
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

    if (editingBackup) {
        val backup = container.backup
        WebDavDialog(
            backup,
            onDismiss = { editingBackup = false },
            onForget = {
                backup.forget()
                editingBackup = false
            },
        ) { url, user, password ->
            backup.change(url, user, password)
            editingBackup = false
        }
    }

    if (editingNas) {
        val nas = container.nas
        NasDialog(
            nas,
            onDismiss = { editingNas = false },
            onForget = {
                nas.forget()
                editingNas = false
            },
        ) { link, user, password ->
            nas.change(link, user, password)
            editingNas = false
        }
    }

    if (restoring) {
        RestoreDialog(container.backup) { restoring = false }
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

/** Rows as one segmented group under a caption, and a note under them; [extra] segments after them. */
@Composable
private fun Section(title: String, rows: List<Row>, first: Int, note: String?, extra: List<Segment> = emptyList(), onEdit: (Int) -> Unit) {
    SegmentedGroup(title, note, rows.mapIndexed<Row, Segment> { index, row -> { shapes -> SettingRow(row, shapes) { onEdit(first + index) } } } + extra)
}

@Composable
private fun SettingRow(row: Row, shapes: ListItemShapes, onClick: () -> Unit) {
    val setting = row.setting
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        supportingContent = {
            // The scheme said by the dialog; the row has room only for where it points.
            Text(if (setting.isSet) setting.value.substringAfter("://") else stringResource(Res.string.setting_unset), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(row.icon, contentDescription = null) },
        trailingContent = if (setting.isDefault && setting.isSet) {
            { Text(stringResource(Res.string.setting_is_default), style = AppTheme.typography.caption) }
        } else {
            null
        },
    ) { Text(stringResource(row.title)) }
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
