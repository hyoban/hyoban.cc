package cc.hyoban.moments.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** A GATT operation the camera refused, with its ATT status. */
internal class GattException(val status: Int, what: String) : IOException("$what failed with status $status")

/**
 * A connection to the camera over Bluetooth LE, one operation at a time as GATT allows, each
 * waited for. Called once the app holds Nearby devices.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class Gatt private constructor(context: Context, device: BluetoothDevice) : Closeable {
    private val lock = Mutex()
    private var pending: CompletableDeferred<ByteArray>? = null
    private val _connected = MutableStateFlow(false)
    /** Whether the link is up; false for good once it drops. */
    val connected: StateFlow<Boolean> = _connected
    private val _notifications = MutableSharedFlow<Pair<UUID, ByteArray>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notifications: SharedFlow<Pair<UUID, ByteArray>> = _notifications
    private val ready = CompletableDeferred<Unit>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                gatt.requestMtu(MTU)
            } else {
                _connected.value = false
                val error = GattException(status, "connection")
                ready.completeExceptionally(error)
                pending?.completeExceptionally(error)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!gatt.discoverServices()) ready.completeExceptionally(IOException("discovery did not start"))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit) else ready.completeExceptionally(GattException(status, "discovery"))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, "read ${characteristic.uuid}", value)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            finish(status, "write ${characteristic.uuid}", null)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(status, "subscribe ${descriptor.characteristic.uuid}", null)

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(characteristic.uuid to value)
        }
    }

    private fun finish(status: Int, what: String, value: ByteArray?) {
        val waiting = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) waiting.complete(value ?: ByteArray(0)) else waiting.completeExceptionally(GattException(status, what))
    }

    private val gatt: BluetoothGatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        ?: throw IOException("no GATT")

    private fun characteristic(service: UUID, id: UUID): BluetoothGattCharacteristic =
        gatt.getService(service)?.getCharacteristic(id) ?: throw IOException("the camera has no $id")

    /** Runs one operation [start]s and waits for its answer: what was read, or nothing for a write. */
    private suspend fun op(timeoutMs: Long, start: () -> Boolean): ByteArray = lock.withLock {
        val waiting = CompletableDeferred<ByteArray>()
        pending = waiting
        try {
            if (!_connected.value) throw IOException("not connected")
            // The stack refuses while another operation on the radio is under way, another
            // connection's included: try again shortly.
            var started = start()
            var tries = 0
            while (!started && tries++ < START_TRIES) { delay(START_RETRY_MS); started = start() }
            if (!started) throw IOException("the operation did not start")
            withTimeoutOrNull(timeoutMs) { waiting.await() } ?: throw IOException("the camera didn't answer")
        } finally { pending = null }
    }

    suspend fun read(service: UUID, id: UUID, timeoutMs: Long = OP_TIMEOUT_MS): ByteArray {
        val c = characteristic(service, id)
        return op(timeoutMs) { gatt.readCharacteristic(c) }
    }

    suspend fun write(service: UUID, id: UUID, value: ByteArray, timeoutMs: Long = OP_TIMEOUT_MS) {
        val c = characteristic(service, id)
        op(timeoutMs) { gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS }
    }

    /** Has the camera tell what changes in [id]; a characteristic it doesn't have is passed over. */
    suspend fun subscribe(service: UUID, id: UUID) {
        val c = gatt.getService(service)?.getCharacteristic(id) ?: return
        val descriptor = c.getDescriptor(SonyBle.CCCD) ?: return
        if (!gatt.setCharacteristicNotification(c, true)) return
        op(OP_TIMEOUT_MS) { gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS }
    }

    override fun close() {
        _connected.value = false
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        pending?.completeExceptionally(IOException("closed"))
    }

    companion object {
        /** Big enough for the 95-byte location in one write. */
        private const val MTU = 158
        private const val OP_TIMEOUT_MS = 15_000L
        private const val START_TRIES = 20
        private const val START_RETRY_MS = 150L

        /** Connects to [device] and lists its services. */
        suspend fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long = 30_000): Gatt {
            val gatt = Gatt(context.applicationContext, device)
            try {
                withTimeoutOrNull(timeoutMs) { gatt.ready.await() } ?: throw IOException("the camera didn't connect")
                return gatt
            } catch (e: Throwable) { gatt.close(); throw e }
        }
    }
}
