package cc.hyoban.moments.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.NoteSignIn
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.settings.AddressDialog
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsRow
import cc.hyoban.moments.ui.settings.TokenDialog
import cc.hyoban.moments.ui.settings.TokenWords
import cc.hyoban.moments.ui.settings.addressRow
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import org.jetbrains.compose.resources.stringResource

/**
 * Planning with AI, on every device: its group on 同步与来源, the model a plan is talked over with
 * (its address, the owner's key, the model's name and what the owner tells it besides), and,
 * where the device reads it, Xiaohongshu, signed in to on its own page.
 */
object AiPlanPlugin : Plugin, SettingsGroup {
    override val page = SettingsPage.SYNC
    override val order = 40

    private val KEY = TokenWords(Res.string.setting_ai_key, Res.string.ai_key_hint, Res.string.tencent_key_replace, Res.string.settings_ai_key_note, Res.string.action_forget_tencent_key, Res.string.action_show_key, Res.string.action_hide_key)

    override fun Contributions.contribute() {
        add(Points.settingsGroups, this@AiPlanPlugin)
        // The plans' conversations stay on the device and in its WebDAV backup.
        add(Points.kept, KeptFiles(files = listOf(cc.hyoban.moments.PLAN_CHATS_FILE)))
    }

    @Composable
    override fun group(container: AppContainer, nav: SettingsPageNav): SettingsGroupSpec {
        val settings = container.settings
        var editingUrl by rememberSaveable { mutableStateOf(false) }
        var editingKey by rememberSaveable { mutableStateOf(false) }
        var editingModel by rememberSaveable { mutableStateOf(false) }
        var editingNotes by rememberSaveable { mutableStateOf(false) }
        val address = addressRow(settings.aiUrl, Res.string.setting_ai_url, AppIcons.Cloud) { editingUrl = true }
        val aiKey = SettingsRow.Value(
            AppIcons.Key, stringResource(Res.string.setting_ai_key), "ai_key",
            stringResource(if (settings.aiKey.isSet) Res.string.token_set else Res.string.token_unset),
            maxLines = Int.MAX_VALUE,
        ) { editingKey = true }
        val aiModel = SettingsRow.Value(
            AppIcons.Sliders, stringResource(Res.string.setting_ai_model), "ai_model",
            settings.aiModel.value.ifEmpty { stringResource(Res.string.setting_unset) },
        ) { editingModel = true }
        // What the owner tells the model besides, its first line shown.
        val aiNotes = SettingsRow.Value(
            AppIcons.Notes, stringResource(Res.string.setting_ai_notes), "ai_notes",
            settings.aiNotes.value.trim().lineSequence().first().ifEmpty { stringResource(Res.string.ai_notes_unset) },
        ) { editingNotes = true }
        // Xiaohongshu, for the model to search its notes: signed in to on its own page, where the device has it.
        val noteSearch = container.noteSearch
        val navigate = nav.navigate
        val xiaohongshu = if (noteSearch != null && navigate != null) {
            SettingsRow.Page(
                AppIcons.Xiaohongshu, stringResource(Res.string.setting_xiaohongshu), "xiaohongshu",
                supporting = stringResource(if (noteSearch.signedIn.value) Res.string.xiaohongshu_signed_in else Res.string.xiaohongshu_signed_out),
                onClick = { navigate(NoteSignIn) },
            )
        } else {
            null
        }
        val note = stringResource(Res.string.settings_ai_note).let { note ->
            if (xiaohongshu != null) note + stringResource(Res.string.settings_ai_note_xiaohongshu) else note
        }

        if (editingKey) {
            TokenDialog(settings.aiKey, KEY, "ai_key", onDismiss = { editingKey = false }) { text ->
                settings.aiKey.set(text)
                editingKey = false
            }
        }
        if (editingModel) {
            ModelDialog(settings.aiModel.value, settings.aiUrl.value, settings.aiKey.value, onDismiss = { editingModel = false }) { text ->
                settings.aiModel.set(text)
                editingModel = false
            }
        }
        if (editingNotes) {
            NotesDialog(settings.aiNotes.value, onDismiss = { editingNotes = false }) { text ->
                settings.aiNotes.set(text.trim())
                editingNotes = false
            }
        }
        if (editingUrl) {
            AddressDialog(settings.aiUrl, stringResource(Res.string.setting_ai_url), onDismiss = { editingUrl = false }) { text ->
                settings.aiUrl.set(text)
                editingUrl = false
            }
        }
        return SettingsGroupSpec(stringResource(Res.string.section_ai), note, listOfNotNull(address, aiKey, aiModel, aiNotes, xiaohongshu))
    }
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
