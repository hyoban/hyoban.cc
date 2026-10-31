package cc.hyoban.moments.sidekey

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.design.Button
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.ModalBottomSheet
import cc.hyoban.moments.ui.design.RadioButton
import cc.hyoban.moments.ui.design.SegmentedListItem
import cc.hyoban.moments.ui.settings.segmentColors
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import cc.hyoban.moments.footprints.ActionRow
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.OutlinedTextField
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SideKeySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by SideKeys.config.collectAsStateWithLifecycle()
    val state by SideKeys.state.collectAsStateWithLifecycle()
    val connected by ShareAccessibilityService.connected.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var timing by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        SideKeys.refresh()
        onPauseOrDispose {}
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == 712 && result == PackageManager.PERMISSION_GRANTED) SideKeys.setEnabled(true)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    SubPage(stringResource(R.string.side_key_title), onBack) {
        PageWidth(maxColumns = 1) { columns ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = columns.margin).padding(bottom = Space.xl)) {
                SegmentedGroup(null, stringResource(R.string.side_key_note), listOf { shapes ->
                    SwitchRow(config.enabled, AppIcons.Phone,
                        stringResource(R.string.side_key_enabled), "sidekey_enabled", shapes,
                        supporting = stringResource(state.phase.label)) { SideKeys.setEnabled(!config.enabled) }
                })
                // What it needs, each with where it stands, the way to the system's own settings at its end.
                SegmentedGroup(stringResource(R.string.side_key_needs), null, buildList {
                    add { shapes -> ActionRow(AppIcons.Accessibility, stringResource(R.string.side_key_service), shapes,
                        supporting = stringResource(if (connected) R.string.side_key_service_on else R.string.side_key_service_off),
                        tag = "sidekey_accessibility", external = true) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } }
                    add { shapes -> ActionRow(AppIcons.Key,
                        stringResource(R.string.side_key_shizuku), shapes, tag = "sidekey_shizuku", external = true) {
                        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (intent != null) context.startActivity(intent)
                        else Toast.makeText(context, R.string.side_key_shizuku_missing, Toast.LENGTH_LONG).show()
                    } }
                    if (state.phase == SideKeyPhase.NEEDS_PERMISSION) add { shapes -> ActionRow(AppIcons.Check,
                        stringResource(R.string.side_key_grant), shapes, tag = "sidekey_grant") {
                        try { Shizuku.requestPermission(712) }
                        catch (_: Exception) { Toast.makeText(context, R.string.side_key_needs_shizuku, Toast.LENGTH_LONG).show() }
                    } }
                })
                SegmentedGroup(stringResource(R.string.side_key_gestures), stringResource(R.string.side_key_payment_note),
                    SideKeys.gestures.map { gesture -> { shapes ->
                        ActionRow(AppIcons.Sliders, stringResource(SideKeyActions.gestureLabel(gesture)), shapes,
                            supporting = actionLabel(config.actions[gesture] ?: "none", config.labels[gesture].orEmpty()),
                            tag = "sidekey_$gesture") { editing = gesture }
                    } })
                SegmentedGroup(null, stringResource(R.string.side_key_timing_note), listOf { shapes ->
                    ActionRow(AppIcons.Clock, stringResource(R.string.side_key_timing), shapes,
                        supporting = stringResource(R.string.side_key_timing_value, config.doubleMs, config.longMs),
                        tag = "sidekey_timing") { timing = true }
                })
                SegmentedGroup(stringResource(R.string.side_key_last), null, listOf { _ ->
                    Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        val gesture = state.lastGesture
                        Text(if (gesture != null && state.lastTime != null) stringResource(R.string.side_key_last_value,
                            stringResource(SideKeyActions.gestureLabel(gesture)), DateFormat.getTimeInstance().format(Date(state.lastTime!!)))
                            else stringResource(R.string.side_key_no_last), style = AppTheme.typography.body)
                        state.message?.let { Text(it, style = AppTheme.typography.caption, color = tokens.tx2) }
                        if (state.restorePending) Text(stringResource(R.string.side_key_restore_pending), style = AppTheme.typography.caption, color = tokens.tx2)
                    }
                })
            }
        }
    }
    editing?.let { gesture ->
        val current = config.actions[gesture] ?: "none"
        ActionSheet(stringResource(SideKeyActions.gestureLabel(gesture)), current, onDismiss = { editing = null }) { action, label ->
            SideKeys.setAction(gesture, action, label.orEmpty()); editing = null
        }
    }
    if (timing) TimingPicker(config, onDismiss = { timing = false }) { double, long -> SideKeys.setTiming(double, long); timing = false }
}

@Composable
private fun actionLabel(action: String, label: String): String = when {
    action.startsWith("app:") -> label.ifEmpty { action.substringAfter(':') }
    action.startsWith("uri:") -> action.substringAfter(':')
    else -> stringResource(SideKeyActions.choices[action] ?: R.string.side_key_none)
}

/**
 * What a gesture does, chosen in a sheet over the page, the one it does now marked: an action, or
 * an app searched for or a link typed in the same sheet, never a page further down.
 */
@Composable
private fun ActionSheet(title: String, current: String, onDismiss: () -> Unit, onPick: (String, String?) -> Unit) {
    var mode by remember { mutableStateOf("actions") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.sheet, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        Text(title, style = AppTheme.typography.title, modifier = Modifier.padding(horizontal = Space.gutter).semantics { heading() })
        when (mode) {
            "actions" -> Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = Space.l)) {
                SegmentedGroup(null, null, SideKeyActions.choices.map { (value, label) ->
                    { shapes ->
                        val chosen = current == value || current.startsWith("$value:") && (value == "app" || value == "uri")
                        SegmentedListItem(
                            onClick = { if (value == "app" || value == "uri") mode = value else onPick(value, null) },
                            shapes = shapes,
                            colors = segmentColors(),
                            trailingContent = { RadioButton(chosen, onClick = null) },
                            modifier = Modifier.testTag("sidekey_action_$value"),
                        ) { Text(stringResource(label)) }
                    }
                })
            }
            "app" -> AppList(Modifier.weight(1f, fill = false)) { pkg, label -> onPick("app:$pkg", label) }
            else -> LinkField(current.takeIf { it.startsWith("uri:") }?.substringAfter(':').orEmpty()) { link -> onPick("uri:$link", null) }
        }
    }
}

private data class LaunchableApp(val packageName: String, val label: String)

/** The apps that can be opened, searched as typed. */
@Composable
private fun AppList(modifier: Modifier, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<LaunchableApp>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
                .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
    }
    val duplicates = remember(apps) { apps.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys }
    Column(modifier.padding(top = Space.l, bottom = Space.l)) {
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.side_key_search_apps)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).testTag("sidekey_app_search"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = Space.s)) {
            apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }.forEach { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = if (app.label in duplicates) {
                        { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    modifier = Modifier.clickable { onPick(app.packageName, app.label) }.padding(horizontal = Space.gutter - 16.dp),
                )
            }
        }
    }
}

/** A link or an app's URI typed in, saved once it is one. */
@Composable
private fun LinkField(initial: String, onPick: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.l)) {
        OutlinedTextField(value, { value = it; error = false }, label = { Text(stringResource(R.string.side_key_uri_label)) },
            isError = error, supportingText = if (error) ({ Text(stringResource(R.string.side_key_invalid_uri)) }) else null,
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("sidekey_uri"))
        Button(onClick = { val link = value.trim(); if (SideKeyActions.validLink(link)) onPick(link) else error = true },
            modifier = Modifier.align(Alignment.End).padding(top = Space.l)) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun TimingPicker(config: SideKeyConfig, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    var double by remember { mutableStateOf(config.doubleMs.toString()) }
    var long by remember { mutableStateOf(config.longMs.toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.side_key_timing)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedTextField(double, { double = it; error = false }, label = { Text(stringResource(R.string.side_key_double_time)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = error)
            OutlinedTextField(long, { long = it; error = false }, label = { Text(stringResource(R.string.side_key_long_time)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = error)
            if (error) Text(stringResource(R.string.side_key_invalid_timing), style = AppTheme.typography.caption)
        }
    }, confirmButton = { TextButton(onClick = {
        val d = double.toIntOrNull(); val l = long.toIntOrNull()
        if (d != null && l != null && d in 150..600 && l in 400..2000) onPick(d, l) else error = true
    }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
