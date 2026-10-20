package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.Setting
import cc.hyoban.moments.Token
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.theme.Space
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Laid out as FlClash lays out its settings: rows grouped into a card under a small
// caption, a note under the group, and a tap on a row opening a dialog to change it.

/** Where the app reads from: the API, the photos' origin and the map's styles, each back to the build's own on a tap. */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    // The setting whose dialog is open, by its place in the list, so it stays open across a rotation.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingToken by rememberSaveable { mutableStateOf(false) }
    val rows = listOf(
        Row(settings.apiUrl, R.string.setting_api_url, Icons.Rounded.Cloud) { container.changeApi(it) },
        Row(settings.assetOrigin, R.string.setting_asset_origin, Icons.Rounded.Image) { settings.assetOrigin.set(it) },
        Row(settings.mapStyleLight, R.string.setting_map_style_light, Icons.Rounded.LightMode) { settings.mapStyleLight.set(it) },
        Row(settings.mapStyleDark, R.string.setting_map_style_dark, Icons.Rounded.DarkMode) { settings.mapStyleDark.set(it) },
    )

    SubPage(stringResource(R.string.title_settings), onBack, Modifier.background(AppColors.page)) {
        PageWidth { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                ThemeSettings(settings)
                LanguageSettings()
                Section(stringResource(R.string.section_sources), rows.subList(0, 2), 0, stringResource(R.string.settings_api_note)) { editing = it }
                Group(stringResource(R.string.section_publishing), stringResource(R.string.settings_token_note)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.setting_api_token)) },
                        supportingContent = { Text(stringResource(if (settings.apiToken.isSet) R.string.token_set else R.string.token_unset)) },
                        leadingContent = { Icon(Icons.Rounded.Key, contentDescription = null) },
                        colors = rowColors(),
                        modifier = Modifier.clickable { editingToken = true }.testTag("api_token"),
                    )
                }
                Section(stringResource(R.string.section_map), rows.subList(2, 4), 2, stringResource(R.string.settings_map_note)) { editing = it }
                Credits()
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

/** Rows as one segmented group under a caption, and a note under them. */
@Composable
private fun Section(title: String, rows: List<Row>, first: Int, note: String?, onEdit: (Int) -> Unit) {
    SegmentedGroup(title, note, rows.mapIndexed { index, row -> { SettingRow(row) { onEdit(first + index) } } })
}

@Composable
private fun SettingRow(row: Row, onClick: () -> Unit) {
    val setting = row.setting
    ListItem(
        headlineContent = { Text(stringResource(row.title)) },
        supportingContent = {
            // The scheme said by the dialog; the row has room only for where it points.
            Text(setting.value.substringAfter("://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(row.icon, contentDescription = null) },
        trailingContent = if (setting.isDefault) {
            { Text(stringResource(R.string.setting_is_default), style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
        colors = rowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** FlClash, whose design the app's is after, opening its repository on a tap. */
@Composable
private fun Credits() {
    val uri = LocalUriHandler.current
    Group(stringResource(R.string.section_credits), null) {
        ListItem(
            headlineContent = { Text("FlClash") },
            supportingContent = { Text(stringResource(R.string.credit_flclash_note)) },
            leadingContent = { Icon(Icons.Rounded.Palette, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
            colors = rowColors(),
            modifier = Modifier.clickable { uri.openUri(FLCLASH) },
        )
    }
}

private const val FLCLASH = "https://github.com/chen08209/FlClash"

/** Blank is the build's own; anything else an http or https address. */
private fun valid(text: String) = text.isBlank() || Setting.normalize(text).toHttpUrlOrNull() != null

/**
 * A setting's address to change, the whole of it selected to type over. Reset while it
 * differs from the build's own, which saves that at once; cancel once it is that.
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
                    supportingText = {
                        Text(if (ok) stringResource(R.string.setting_default, setting.default) else stringResource(R.string.error_address))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field.text) }, enabled = ok) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            if (Setting.normalize(field.text) != setting.default) {
                TextButton(onClick = { onSave(setting.default) }) { Text(stringResource(R.string.action_restore_default)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * The API's token to type or paste, shown as dots; one already kept isn't shown again,
 * only replaced or forgotten.
 */
@Composable
private fun TokenDialog(token: Token, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_api_token)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text(stringResource(if (token.isSet) R.string.token_replace else R.string.token_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("api_token_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(field) }, enabled = field.isNotBlank(), modifier = Modifier.testTag("api_token_save")) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            if (token.isSet) {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.action_forget_token)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
