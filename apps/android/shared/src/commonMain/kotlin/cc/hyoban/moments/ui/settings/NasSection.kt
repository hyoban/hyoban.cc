package cc.hyoban.moments.ui.settings

import cc.hyoban.moments.res.*
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.logWarning
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.nas.NasMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
fun NasSection(nas: Nas, linking: NasLinking?, onEdit: () -> Unit) = SettingsGroupView(nasGroup(nas, linking, onEdit))

/** 绿联 NAS as its rows: 在相册里显示, who signs in where, and 关联已发布的照片 once it is set. */
@Composable
fun nasGroup(nas: Nas, linking: NasLinking?, onEdit: () -> Unit): SettingsGroupSpec {
    // 在相册里显示 first: the NAS's photos in 相册, 选照片 and 地图's photos, its sign-in kept either way.
    val settings = cc.hyoban.moments.ui.LocalSettings.current
    val key = cc.hyoban.moments.plugins.NasPlugin.KEY
    val shown = settings.isPluginOn(key)
    val switch = SettingsRow.Switch(AppIcons.Photos, stringResource(Res.string.source_in_album), "nas_in_album", shown) { settings.changePlugin(key, !shown) }
    val account = SettingsRow.Value(
        AppIcons.Nas, stringResource(Res.string.nas_photos), "nas_setting",
        if (nas.configured) stringResource(Res.string.nas_as, nas.link.value, nas.user.value) else stringResource(Res.string.setting_unset),
        onClick = onEdit,
    )
    // 关联已发布的照片: the Moments' photos written before they were linked, found on the NAS.
    val link = linking?.takeIf { nas.configured }?.let { linking ->
        val state = linking.state
        SettingsRow.Action(
            AppIcons.Cloud, stringResource(Res.string.nas_match), "nas_match",
            supporting = when (state) {
                is NasLinking.State.Reading -> when {
                    state.stage == NasMatch.Stage.SAVING -> stringResource(Res.string.nas_match_saving, state.total)
                    state.total == 0 -> stringResource(Res.string.nas_match_starting)
                    state.stage == NasMatch.Stage.DAYS -> stringResource(Res.string.nas_match_reading, state.done + 1, state.total)
                    else -> stringResource(Res.string.nas_match_comparing, state.done, state.total)
                }
                is NasLinking.State.Done -> stringResource(Res.string.nas_match_done, state.linked)
                NasLinking.State.Failed -> stringResource(Res.string.nas_match_failed)
                null -> stringResource(Res.string.nas_match_note)
            },
            maxLines = 2,
            enabled = !linking.running,
            onClick = linking::start,
        )
    }
    return SettingsGroupSpec(stringResource(Res.string.section_nas), stringResource(Res.string.settings_nas_note), listOfNotNull(switch, account, link))
}

/** 关联已发布的照片 under way, or how it went: the Moments' photos linked to the NAS's ([AppContainer.linkNas]). */
class NasLinking(private val container: AppContainer, private val scope: CoroutineScope) {
    sealed interface State {
        data class Reading(val stage: NasMatch.Stage, val done: Int, val total: Int) : State
        data class Done(val linked: Int) : State
        data object Failed : State
    }

    var state by mutableStateOf<State?>(null)
        private set
    val running get() = state is State.Reading

    fun start() {
        if (running) return
        state = State.Reading(NasMatch.Stage.DAYS, 0, 0)
        scope.launch {
            state = try {
                State.Done(container.linkNas { stage, done, total -> state = State.Reading(stage, done, total) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logWarning("Moments", "nas: photos not linked", e)
                State.Failed
            }
        }
    }
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
        title = { Text(stringResource(Res.string.section_nas)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(Res.string.nas_link)) },
                    supportingText = { Text(stringResource(Res.string.nas_link_note)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("nas_link"),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(Res.string.webdav_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("nas_user"),
                )
                OutlinedSecureTextField(
                    state = password,
                    label = { Text(stringResource(Res.string.webdav_password)) },
                    placeholder = if (nas.password.isSet) {
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
                    modifier = Modifier.fillMaxWidth().testTag("nas_password"),
                )
                if (nas.configured) {
                    TextButton(
                        onClick = onForget,
                        colors = ButtonDefaults.textButtonColors(contentColor = tokens.danger),
                        modifier = Modifier.testTag("nas_forget"),
                    ) { Text(stringResource(Res.string.action_stop_nas)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = ok, modifier = Modifier.testTag("nas_save")) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

/** 同步与来源's NAS section with the dialog it opens: the phone's and the computer's alike. */
@Composable
fun NasSettings(container: AppContainer) {
    val nas = container.nas
    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val linking = remember { NasLinking(container, scope) }
    NasSection(nas, linking) { editing = true }
    if (editing) NasDialog(
        nas,
        onDismiss = { editing = false },
        onForget = {
            nas.forget()
            editing = false
        },
    ) { link, user, password ->
        nas.change(link, user, password)
        editing = false
    }
}
