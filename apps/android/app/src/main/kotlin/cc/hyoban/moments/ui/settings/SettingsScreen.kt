package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.Setting
import cc.hyoban.moments.ui.components.FloatingBack
import cc.hyoban.moments.ui.components.PageHeader
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SectionHeader
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the app reads from: the API, the photos' origin and the map's styles, each back to the build's own on a tap. */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val api = rememberField(settings.apiUrl)
    val assets = rememberField(settings.assetOrigin)
    val light = rememberField(settings.mapStyleLight)
    val dark = rememberField(settings.mapStyleDark)
    val fields = listOf(api, assets, light, dark)
    val changed = fields.any { it.changed }
    val valid = fields.all { it.valid }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.message_settings_saved)

    Box(Modifier.fillMaxSize()) {
        PageWidth { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = columns.margin),
            ) {
                // Room for the back button, which floats over the page.
                Spacer(Modifier.height(56.dp))
                PageHeader(stringResource(R.string.title_settings), null)
                Column(
                    Modifier.padding(horizontal = Space.xl, vertical = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    SectionHeader(Icons.Rounded.Cloud, stringResource(R.string.section_sources))
                    AddressField(api, R.string.setting_api_url)
                    AddressField(assets, R.string.setting_asset_origin)
                    Spacer(Modifier.height(Space.s))
                    SectionHeader(Icons.Rounded.Map, stringResource(R.string.section_map))
                    AddressField(light, R.string.setting_map_style_light)
                    AddressField(dark, R.string.setting_map_style_dark)
                    Text(
                        stringResource(R.string.settings_api_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            container.changeApi(api.text)
                            assets.setting.set(assets.text)
                            light.setting.set(light.text)
                            dark.setting.set(dark.text)
                            fields.forEach { it.text = it.setting.value }
                            scope.launch { snackbar.showSnackbar(saved) }
                        },
                        enabled = changed && valid,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
        FloatingBack(onBack, Modifier.align(Alignment.TopStart))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/** A setting being edited: the text typed, kept until saved. */
private class Field(val setting: Setting, state: MutableState<String>) {
    var text by state

    val changed get() = Setting.normalize(text).ifEmpty { setting.default } != setting.value

    /** Blank is the build's own; anything else an http or https address. */
    val valid get() = text.isBlank() || Setting.normalize(text).toHttpUrlOrNull() != null
}

/** Kept across a rotation as what was typed. */
@Composable
private fun rememberField(setting: Setting): Field {
    val text = rememberSaveable { mutableStateOf(setting.value) }
    return remember(setting, text) { Field(setting, text) }
}

@Composable
private fun AddressField(field: Field, label: Int) {
    val default = field.setting.default
    OutlinedTextField(
        value = field.text,
        onValueChange = { field.text = it },
        label = { Text(stringResource(label)) },
        placeholder = { Text(default) },
        singleLine = true,
        isError = !field.valid,
        supportingText = {
            Text(if (field.valid) stringResource(R.string.setting_default, default) else stringResource(R.string.error_address))
        },
        trailingIcon = if (Setting.normalize(field.text) != default) {
            {
                IconButton(onClick = { field.text = default }) {
                    Icon(Icons.Rounded.Restore, contentDescription = stringResource(R.string.action_restore_default))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}
