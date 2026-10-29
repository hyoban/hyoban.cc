package cc.hyoban.moments.footprints

import cc.hyoban.moments.ui.design.Checkbox
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.TextButton
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import cc.hyoban.moments.ui.design.*
import cc.hyoban.moments.ui.design.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starting and pausing by Wi-Fi and Bluetooth, on the footprint settings page; all identifiers stay on this phone. */
@Composable
fun ConnectionAutomationSettings(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = container.connectionRules
    val rules by store.rules.collectAsState()
    val status by ConnectionReminderService.status.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var permissionHelp by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun work(persistent: Boolean = false, block: suspend () -> Unit) {
        (if (persistent) container.scope else scope).launch {
            busy = true
            error = null
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = R.string.connection_automation_error }
            finally { busy = false }
        }
    }
    fun save(next: ConnectionRules) = work(persistent = true) {
        withContext(Dispatchers.IO) { store.save(next) }
        ConnectionReminderService.sync(context)
    }
    fun openPicker(kind: String) = work {
        choices = withContext(Dispatchers.IO) {
            if (kind == "wifi") (context.wifiChoices() + rules.wifi).distinct().associateWith { it }
            else rules.bluetooth + context.bluetoothChoices()
        }.entries.sortedWith(compareBy({ it.value }, { it.key })).associate { it.toPair() }
        selected = if (kind == "wifi") rules.wifi else rules.bluetooth.keys
        picker = kind
    }
    fun afterPermission(action: String) {
        when (action) {
            "wifi" -> {
                permissionHelp = !context.wifiAllowed(background = false)
                openPicker(action)
            }
            "bluetooth" -> {
                permissionHelp = !context.bluetoothAllowed()
                openPicker(action)
            }
            "enable" -> if (context.connectionAutomationAllowed(rules)) {
                permissionHelp = false
                save(rules.copy(enabled = true))
            } else permissionHelp = true
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let(::afterPermission)
        pending = null
    }
    fun request(action: String) {
        val needed = mutableListOf<String>()
        if (action == "wifi" || action == "enable") {
            if (!context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
                needed += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && (action == "bluetooth" || (action == "enable" && rules.bluetooth.isNotEmpty())) && !context.bluetoothAllowed()) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (action == "enable" && Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) afterPermission(action)
        else { pending = action; permissions.launch(needed.toTypedArray()) }
    }
    LaunchedEffect(store) {
        work { withContext(Dispatchers.IO) { store.load() }; loaded = true }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Returning from system settings never opts the user in by itself.
        scope.launch {
            try { ConnectionReminderService.sync(context) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = R.string.connection_automation_error }
        }
    }
    val names = { set: Collection<String> -> set.sorted().joinToString("、") }
    val none = stringResource(R.string.connection_automation_none)
    SegmentedGroup(
        stringResource(R.string.connection_automation_title),
        error?.let { stringResource(it) },
        listOf(
            { shapes ->
                SwitchRow(
                    rules.enabled,
                    AppIcons.Link,
                    stringResource(R.string.connection_automation_switch),
                    "connection_automation_enabled",
                    shapes,
                    supporting = stringResource(when {
                        !rules.hasTargets -> R.string.connection_automation_choose_first
                        rules.enabled -> status ?: R.string.connection_automation_watching
                        else -> R.string.connection_automation_off
                    }),
                    enabled = loaded && !busy && rules.hasTargets,
                ) { if (!rules.enabled) request("enable") else save(rules.copy(enabled = false)) }
            },
            { shapes ->
                ActionRow(AppIcons.Wifi, stringResource(R.string.connection_automation_wifi_row), shapes,
                    supporting = rules.wifi.takeIf { it.isNotEmpty() }?.let(names) ?: none, tag = "connection_automation_wifi", enabled = !busy) { request("wifi") }
            },
            { shapes ->
                ActionRow(AppIcons.Bluetooth, stringResource(R.string.connection_automation_bluetooth_row), shapes,
                    supporting = rules.bluetooth.values.takeIf { it.isNotEmpty() }?.let(names) ?: none, tag = "connection_automation_bluetooth", enabled = !busy) { request("bluetooth") }
            },
            { shapes ->
                ActionRow(AppIcons.Info, stringResource(R.string.connection_automation_how), shapes, tag = "connection_automation_settings") {
                    explaining = true
                }
            },
        ),
    )
    CompanionConnectionSettings(rules)
    if (permissionHelp || (rules.enabled && status == R.string.connection_automation_permissions)) {
        val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        SegmentedGroup(
            stringResource(R.string.connection_automation_permission_title),
            stringResource(R.string.connection_automation_permission_help),
            listOfNotNull(
                { shapes ->
                    ActionRow(AppIcons.Settings, stringResource(R.string.footprints_settings), shapes, external = true) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                },
                { shapes ->
                    ActionRow(AppIcons.Pin, stringResource(R.string.connection_automation_location_settings), shapes, external = true) {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                },
                if (Build.VERSION.SDK_INT == 29) { shapes ->
                    ActionRow(AppIcons.Refresh, stringResource(R.string.footprints_background_permission), shapes) {
                        background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                } else null,
            ),
        )
    }
    if (explaining) InfoDialog(
        stringResource(R.string.connection_automation_how),
        listOf(stringResource(R.string.connection_automation_rules), stringResource(R.string.connection_automation_monitor_note)),
    ) { explaining = false }
    picker?.let { kind ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { picker = null },
            title = { Text(stringResource(if (kind == "wifi") R.string.connection_automation_wifi_title else R.string.connection_automation_bluetooth_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (kind == "wifi") R.string.connection_automation_wifi_note else R.string.connection_automation_bluetooth_note))
                    if (choices.isEmpty()) Text(stringResource(R.string.connection_automation_no_devices))
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(choices.entries.toList(), key = { it.key }) { (id, name) ->
                            Row(Modifier.fillMaxWidth().toggleable(id in selected, role = Role.Checkbox) {
                                selected = if (it) selected + id else selected - id
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in selected, onCheckedChange = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(name)
                                    if (kind == "bluetooth" && name != id) Text(id, style = AppTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                val next = if (kind == "wifi") rules.copy(wifi = selected) else rules.copy(bluetooth = choices.filterKeys { it in selected })
                // A newly added target may need another permission; do not silently keep a broken monitor enabled.
                save(next.copy(enabled = next.enabled && context.connectionAutomationAllowed(next)))
                permissionHelp = next.enabled && !context.connectionAutomationAllowed(next)
                picker = null
            }, modifier = Modifier.testTag("connection_automation_save")) { Text(stringResource(R.string.connection_automation_save)) } },
            dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
