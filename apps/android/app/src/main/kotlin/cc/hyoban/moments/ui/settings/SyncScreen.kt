package cc.hyoban.moments.ui.settings

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
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

/** 同步与来源: the API, its token and reading from it again; where the photos come from; the map's styles. */
@Composable
fun SyncScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    // The setting whose dialog is open, by its place in the list, so it stays open across a rotation.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingToken by rememberSaveable { mutableStateOf(false) }
    val rows = listOf(
        Row(settings.apiUrl, R.string.setting_api_url, AppIcons.Cloud) { container.changeApi(it) },
        Row(settings.assetOrigin, R.string.setting_asset_origin, AppIcons.Image) { settings.assetOrigin.set(it) },
        Row(settings.mapStyleLight, R.string.setting_map_style_light, AppIcons.Sun) { settings.mapStyleLight.set(it) },
        Row(settings.mapStyleDark, R.string.setting_map_style_dark, AppIcons.Moon) { settings.mapStyleDark.set(it) },
    )

    SubPage(stringResource(R.string.sync_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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
                        supportingContent = { Text(stringResource(if (settings.apiToken.isSet) R.string.token_set else R.string.token_unset)) },
                        modifier = Modifier.testTag("api_token"),
                    ) { Text(stringResource(R.string.setting_api_token)) }
                }
                // Reading from the API again with a tap, as a refresh pulled for on the tabs is had without the gesture too.
                val syncing by container.repository.syncing.collectAsStateWithLifecycle()
                val refresh: Segment = { shapes ->
                    SegmentedListItem(
                        onClick = { if (!syncing) container.refresh() },
                        shapes = shapes,
                        colors = segmentColors(),
                        leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
                        supportingContent = { Text(stringResource(if (syncing) R.string.refreshing else R.string.refresh_note)) },
                        trailingContent = if (syncing) {
                            { Spinner(Modifier.size(24.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("refresh"),
                    ) { Text(stringResource(R.string.action_refresh)) }
                }
                Section(stringResource(R.string.section_site), rows.subList(0, 1), 0, stringResource(R.string.settings_api_note), extra = listOf(token, refresh)) { editing = it }
                Section(stringResource(R.string.section_media), rows.subList(1, 2), 1, null) { editing = it }
                Section(stringResource(R.string.section_map), rows.subList(2, 4), 2, stringResource(R.string.settings_map_note)) { editing = it }
            }
        }
    }

    if (editingToken) {
        TokenDialog(settings.apiToken, onDismiss = { editingToken = false }) { text ->
            settings.apiToken.set(text)
            editingToken = false
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
private class Row(val setting: Setting, val title: Int, val icon: ImageVector, val save: (String) -> Unit)

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
            Text(setting.value.substringAfter("://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(row.icon, contentDescription = null) },
        trailingContent = if (setting.isDefault) {
            { Text(stringResource(R.string.setting_is_default), style = AppTheme.typography.caption) }
        } else {
            null
        },
    ) { Text(stringResource(row.title)) }
}

/** Blank is the build's own; anything else an http or https address. */
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
                    placeholder = { Text(setting.default) },
                    isError = !ok,
                    // The error marked by an icon as well as the colour, as Material 3 asks of a field in error.
                    trailingIcon = if (ok) null else {
                        { Icon(AppIcons.Alert, contentDescription = null) }
                    },
                    supportingText = {
                        Text(if (ok) stringResource(R.string.setting_default, setting.default) else stringResource(R.string.error_address))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    // Done on the keyboard, or Enter on one plugged in, saves as confirm does.
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(field.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (Setting.normalize(field.text) != setting.default) {
                    TextButton(onClick = { onSave(setting.default) }, modifier = Modifier.testTag("restore_default")) {
                        Text(stringResource(R.string.action_restore_default))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }, enabled = ok) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The API's token to type or paste in Material 3's secure text field, its dots shown as
 * what they are on the eye's tap; one already kept isn't shown again, only replaced, or
 * forgotten from under the field, in the error colour, as it can't be had back.
 */
@Composable
private fun TokenDialog(token: Token, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val field = rememberTextFieldState()
    var hidden by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_api_token)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedSecureTextField(
                    state = field,
                    placeholder = { Text(stringResource(if (token.isSet) R.string.token_replace else R.string.token_hint)) },
                    textObfuscationMode = if (hidden) TextObfuscationMode.RevealLastTyped else TextObfuscationMode.Visible,
                    trailingIcon = {
                        val label = stringResource(if (hidden) R.string.action_show_token else R.string.action_hide_token)
                        Tooltip(label) {
                            IconButton(onClick = { hidden = !hidden }, modifier = Modifier.testTag("api_token_reveal")) {
                                Icon(if (hidden) AppIcons.Eye else AppIcons.EyeOff, contentDescription = label)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { field.text.toString().takeIf { it.isNotBlank() }?.let(onSave) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("api_token_field"),
                )
                // What the token is for, said where it is set: the changes wait on the phone until published.
                Text(stringResource(R.string.settings_token_note), style = AppTheme.typography.caption, color = tokens.tx2)
                if (token.isSet) {
                    TextButton(
                        onClick = { onSave("") },
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                        modifier = Modifier.testTag("api_token_forget"),
                    ) { Text(stringResource(R.string.action_forget_token)) }
                }
            }
        },
        confirmButton = {
            val text = field.text.toString()
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("api_token_save")) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
