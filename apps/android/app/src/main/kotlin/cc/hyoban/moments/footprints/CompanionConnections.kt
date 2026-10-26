package cc.hyoban.moments.footprints

import android.annotation.SuppressLint
import android.companion.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.annotation.ChecksSdkIntAtLeast
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import cc.hyoban.moments.MomentsApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** The system owns associations. Existing paired-device rules work without an association. */
internal object CompanionConnections {
    val eventLock = Mutex()
    @ChecksSdkIntAtLeast(api = 36)
    fun supported(context: Context) = Build.VERSION.SDK_INT >= 36 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    fun associations(context: Context): Map<String, Int> {
        if (!supported(context)) return emptyMap()
        return runCatching { context.getSystemService(CompanionDeviceManager::class.java).myAssociations.mapNotNull {
            it.deviceMacAddress?.toString()?.uppercase(Locale.ROOT)?.let { address -> address to it.id }
        }.toMap() }.getOrDefault(emptyMap())
    }

    @SuppressLint("MissingPermission") // Bluetooth permission is checked; failures preserve ordinary monitoring.
    fun sync(context: Context, rules: ConnectionRules) {
        if (!supported(context)) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        val diagnostics = (context.applicationContext as MomentsApplication).container.footprintDiagnostics
        associations(context).forEach { (address, id) ->
            val enabled = rules.enabled && address in rules.bluetooth
            try {
                val request = ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 && context.bluetoothAllowed()) {
                        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
                        if (device != null) previousAndSave(context, id,
                            device.isConnected(BluetoothDevice.TRANSPORT_BREDR) || device.isConnected(BluetoothDevice.TRANSPORT_LE), seed = true)
                    }
                    manager.startObservingDevicePresence(request)
                }
                else {
                    manager.stopObservingDevicePresence(request)
                    previousAndSave(context, id, null)
                }
                diagnostics.record("companion_monitor", mapOf("enabled" to enabled))
            } catch (e: Exception) {
                diagnostics.record("companion_monitor_failed", mapOf("exception" to e.javaClass.simpleName))
            }
        }
    }

    @Synchronized fun resetBaselines(context: Context) {
        AtomicFile(File(context.noBackupFilesDir, "companion-connections.json")).delete()
    }

    /** Only our own association IDs; the baseline is local and excluded from cloud/device backups. */
    @Synchronized fun previousAndSave(context: Context, id: Int, connected: Boolean?, seed: Boolean = false): Boolean? {
        val file = AtomicFile(File(context.noBackupFilesDir, "companion-connections.json"))
        val values = if (file.baseFile.exists()) file.openRead().bufferedReader().use { JSONObject(it.readText()) } else JSONObject()
        val key = id.toString()
        val previous = if (values.has(key)) values.getBoolean(key) else null
        if (seed && previous != null) return previous
        if (connected == null) {
            if (!values.has(key)) return previous
            values.remove(key)
        } else values.put(key, connected)
        val out = file.startWrite()
        try { out.write(values.toString().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        return previous
    }
}

/** Bound only by Android; BLE proximity is intentionally not treated as a Bluetooth connection. */
@SuppressLint("NewApi") // Declared on all versions, but only registered/used on API 36+.
class FootprintCompanionService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val connected = when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> true
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> false
            else -> return
        }
        val container = (application as MomentsApplication).container
        container.scope.launch { CompanionConnections.eventLock.withLock {
            try {
                val rules = withContext(Dispatchers.IO) { container.connectionRules.load() }
                val address = CompanionConnections.associations(this@FootprintCompanionService)
                    .entries.firstOrNull { it.value == event.associationId }?.key ?: return@withLock
                if (!rules.enabled || address !in rules.bluetooth) return@withLock
                val previous = withContext(Dispatchers.IO) {
                    CompanionConnections.previousAndSave(this@FootprintCompanionService, event.associationId, connected)
                }
                container.footprintDiagnostics.record("companion_connection", mapOf("connected" to connected, "initial" to (previous == null)))
                ConnectionReminderService.companionEvent(this@FootprintCompanionService,
                    ConnectionSignal(ConnectionKind.BLUETOOTH, address, connected), previous)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                container.footprintDiagnostics.record("companion_event_failed", mapOf("exception" to e.javaClass.simpleName))
            }
        } }
    }
}
