package cc.hyoban.moments.camera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import cc.hyoban.moments.footprints.CompanionConnections
import cc.hyoban.moments.ui.components.hasPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Pairing the camera and knowing when it is near: the system's companion association of it
 * (the device list it chooses from, and its presence while the app sleeps), the Bluetooth bond
 * it asks for, and the switch that starts and stops [CameraLocationService].
 */
object CameraLink {
    /** Sony's company id in a Bluetooth advertisement, and the type its cameras give. */
    private const val SONY = 0x012D
    private val CAMERA_TYPE = byteArrayOf(0x03, 0x00)

    /** On the phones that can wake the app for a companion device's presence, as the footprints' automation needs too. */
    @ChecksSdkIntAtLeast(api = 36)
    fun supported(context: Context) = CompanionConnections.supported(context)

    fun bluetoothAllowed(context: Context) = context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    fun locationAllowed(context: Context) = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    fun backgroundLocationAllowed(context: Context) = context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Joining the camera's Wi‑Fi and, from Android 17, reaching it on that network. */
    fun wifiAllowed(context: Context) = wifiPermissions.all(context::hasPermission)

    /** What taking photos off the camera needs: Bluetooth to wake its Wi‑Fi, and the Wi‑Fi. */
    fun transferAllowed(context: Context) = bluetoothAllowed(context) && wifiAllowed(context)

    val wifiPermissions: Array<String> = buildList {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK)
    }.toTypedArray()

    private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The system's list of Sony cameras near, for the owner to choose theirs from. */
    @RequiresApi(36)
    fun associate(context: Context, onPending: (IntentSender) -> Unit, onDone: (AssociationInfo?) -> Unit) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setManufacturerData(SONY, CAMERA_TYPE, byteArrayOf(-1, -1)).build())
            .build()
        val request = AssociationRequest.Builder().setSingleDevice(false).addDeviceFilter(filter).build()
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) = onPending(sender)
            override fun onAssociationCreated(association: AssociationInfo) = onDone(association)
            override fun onFailure(message: CharSequence?) { Log.w(TAG, "camera association failed: $message"); onDone(null) }
        })
    }

    /**
     * Keeps [association] as the camera and has the phone bond with it: the camera asks to
     * confirm, on its 配对 screen. True once bonded.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(36)
    suspend fun pair(context: Context, association: AssociationInfo): Boolean {
        val address = association.deviceMacAddress?.toString()?.uppercase(Locale.ROOT) ?: return false
        val device = device(context, address) ?: return false
        val name = association.displayName?.toString() ?: device.name ?: address
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) return false
            // Bonding, until the camera's confirmation is accepted or turned down (back to none).
            withTimeoutOrNull(BOND_TIMEOUT_MS) {
                var started = false
                while (device.bondState != BluetoothDevice.BOND_BONDED) {
                    if (device.bondState == BluetoothDevice.BOND_BONDING) started = true
                    else if (started) break
                    delay(300)
                }
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) return false
        }
        CameraStore.save(context, PairedCamera(address, name, association.id))
        sync(context)
        return true
    }

    @SuppressLint("MissingPermission")
    fun device(context: Context, address: String): BluetoothDevice? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)

    /** Forgets the camera: its association, the bond and the location service. */
    @SuppressLint("MissingPermission")
    fun unpair(context: Context) {
        val camera = CameraStore.load(context) ?: return
        CameraLocationService.stop(context)
        if (supported(context)) runCatching {
            val manager = context.getSystemService(CompanionDeviceManager::class.java)
            manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(camera.association).build())
            // The bond goes with it, which only an association's app may remove.
            manager.removeBond(camera.association)
            manager.disassociate(camera.association)
        }
        CameraStore.save(context, null)
        CameraStore.update { CameraStatus() }
    }

    fun setSendLocation(context: Context, on: Boolean) {
        val camera = CameraStore.load(context) ?: return
        CameraStore.save(context, camera.copy(sendLocation = on))
        sync(context)
        if (!on) CameraLocationService.stop(context)
        else if (CameraStore.status.value.near) CameraLocationService.start(context)
    }

    /** Has the system watch for the camera while its location is wanted. */
    fun sync(context: Context) {
        if (!supported(context)) return
        val camera = CameraStore.load(context) ?: return
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        val request = ObservingDevicePresenceRequest.Builder().setAssociationId(camera.association).build()
        runCatching {
            if (camera.sendLocation && bluetoothAllowed(context)) manager.startObservingDevicePresence(request)
            else manager.stopObservingDevicePresence(request)
        }.onFailure { Log.w(TAG, "camera presence not watched", it) }
    }

    /** The camera's association, kept out of the footprints' Bluetooth rules. */
    fun association(context: Context): Int? = CameraStore.load(context)?.association

    /** A presence event the companion service had: true when it was the camera's. */
    @RequiresApi(36)
    fun onPresence(context: Context, event: DevicePresenceEvent): Boolean {
        val camera = CameraStore.load(context) ?: return false
        if (event.associationId != camera.association) return false
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> {
                CameraStore.update { it.copy(near = true) }
                if (camera.sendLocation) CameraLocationService.start(context)
            }
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> {
                CameraStore.update { it.copy(near = false) }
                CameraLocationService.stop(context)
            }
        }
        return true
    }

    private const val BOND_TIMEOUT_MS = 90_000L
}
