package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.Setting
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.PageHeader
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.theme.LocalSurfaceLevel
import cc.hyoban.moments.ui.theme.fill
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
    val rows = listOf(
        Row(settings.apiUrl, R.string.setting_api_url, Icons.Rounded.Cloud) { container.changeApi(it) },
        Row(settings.assetOrigin, R.string.setting_asset_origin, Icons.Rounded.Image) { settings.assetOrigin.set(it) },
        Row(settings.mapStyleLight, R.string.setting_map_style_light, Icons.Rounded.LightMode) { settings.mapStyleLight.set(it) },
        Row(settings.mapStyleDark, R.string.setting_map_style_dark, Icons.Rounded.DarkMode) { settings.mapStyleDark.set(it) },
    )

    Box(Modifier.fillMaxSize()) {
        PageWidth { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                // Room for the back button, which floats over the page.
                Spacer(Modifier.height(56.dp))
                PageHeader(stringResource(R.string.title_settings), null)
                ThemeSettings(settings)
                Section(stringResource(R.string.section_sources), rows.subList(0, 2), 0, stringResource(R.string.settings_api_note)) { editing = it }
                Section(stringResource(R.string.section_map), rows.subList(2, 4), 2, stringResource(R.string.settings_map_note)) { editing = it }
                Credits()
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
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

/** Rows grouped into one card under a caption, and a note under them. */
@Composable
private fun Section(title: String, rows: List<Row>, first: Int, note: String?, onEdit: (Int) -> Unit) {
    Group(title, note) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingRow(row) { onEdit(first + index) }
        }
    }
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
