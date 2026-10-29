package cc.hyoban.moments.footprints

import cc.hyoban.moments.ui.design.AppIcons
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.settings.Segment
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SwitchRow

@Composable
internal fun CompanionConnectionSettings(rules: ConnectionRules) {
    val context = LocalContext.current
    if (!CompanionConnections.supported(context) || rules.bluetooth.isEmpty()) return
    val manager = context.getSystemService(CompanionDeviceManager::class.java)
    var associations by remember { mutableStateOf(CompanionConnections.associations(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val currentRules by rememberUpdatedState(rules)
    fun refresh() {
        try {
            associations = CompanionConnections.associations(context)
            CompanionConnections.sync(context, currentRules)
        } catch (_: Exception) { error = true }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        busy = false
        refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    // One switch per chosen device, on while the system has it linked to the app.
    SegmentedGroup(
        stringResource(R.string.footprints_companion_title),
        stringResource(if (error) R.string.footprints_companion_failed else R.string.footprints_companion_note),
        rules.bluetooth.map<String, String, Segment> { (address, name) ->
            { shapes ->
                SwitchRow(address in associations, AppIcons.BluetoothOn, name, "footprints_companion_associate", shapes,
                    enabled = !busy && context.bluetoothAllowed()) {
                    error = false
                    val id = associations[address]
                    if (id != null) {
                        try { manager.disassociate(id); refresh() } catch (_: Exception) { error = true }
                    } else {
                        busy = true
                        val request = AssociationRequest.Builder().setSingleDevice(true)
                            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build()).build()
                        try {
                            manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                                override fun onAssociationPending(sender: IntentSender) {
                                    try { consent.launch(IntentSenderRequest.Builder(sender).build()) }
                                    catch (_: Exception) { busy = false; error = true }
                                }
                                override fun onAssociationCreated(association: AssociationInfo) { busy = false; refresh() }
                                override fun onFailure(message: CharSequence?) { busy = false; error = true }
                            })
                        } catch (_: Exception) { busy = false; error = true }
                    }
                }
            }
        },
    )
}
