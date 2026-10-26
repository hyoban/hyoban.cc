package cc.hyoban.moments.footprints

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event-driven automation remains alive while GPS recording is paused; it never requests fixes.
 * Keep the original Android component name for upgrades from connection reminders.
 */
@SuppressLint("MissingPermission") // Every registration and read is guarded; revocation is handled too.
class ConnectionReminderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val container get() = (application as MomentsApplication).container
    private val connectivity get() = getSystemService(ConnectivityManager::class.java)
    private val bluetooth get() = getSystemService(BluetoothManager::class.java)?.adapter
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var configured = false
    private var rules = ConnectionRules()
    private var transitions = ConnectionTransitions(rules)
    private var epoch = 0
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var identityCallback: ConnectivityManager.NetworkCallback? = null
    private var identityTimeout: Job? = null
    private val unresolvedNetworks = mutableSetOf<Network>()
    private var identitySeed = true
    private var receiver: BroadcastReceiver? = null
    private val profiles = mutableListOf<Pair<Int, BluetoothProfile>>()
    private val networks = mutableMapOf<Network, String?>()
    private val initialNetworks = mutableSetOf<Network>()
    private var debounce: Job? = null
    private var changeVersion = 0
    private var permissionState = false
    private var recording: RecordingState? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        notifications.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, getString(R.string.connection_automation_title), NotificationManager.IMPORTANCE_LOW))
        notifications.cancel(2302) // Remove any pre-upgrade confirmation notification.
        scope.launch {
            for (event in events) try { event() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { updateStatus(R.string.connection_automation_error) }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { container.connectionRules.load() }
                container.connectionRules.rules.collect { value -> post { if (!configured || value != rules) configure(value) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { updateStatus(R.string.connection_automation_error) }
        }
        scope.launch {
            container.footprints.revision.collect {
                post {
                    val state = withContext(Dispatchers.IO) { container.footprints.state() }
                    if (recording != null && recording != state) {
                        // Manual changes take precedence over any unsettled connection event.
                        debounce?.cancel()
                        changeVersion++
                        transitions.settle(state, manual = true)
                    }
                    recording = state
                }
            }
        }
        scope.launch {
            RecordingService.status.collect { status ->
                if (RecordingFailure.isFailure(status)) post {
                    if (rules.enabled) updateStatus(status ?: R.string.connection_automation_auto_failed)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, WATCH_ID, watchNotification(R.string.connection_automation_watching),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) {
            _status.value = R.string.connection_automation_error
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra("companion_address")?.let { address ->
            val signal = ConnectionSignal(ConnectionKind.BLUETOOTH, address, intent.getBooleanExtra("companion_connected", false))
            val previous = if (intent.hasExtra("companion_previous")) intent.getBooleanExtra("companion_previous", false) else null
            companionSignal(signal, previous, cold = true)
        }
        return START_STICKY
    }

    private fun post(block: suspend () -> Unit) { events.trySend(block) }
    private fun post(generation: Int, block: suspend () -> Unit) = post { if (generation == epoch) block() }

    @Suppress("DEPRECATION") // Initial network identities distinguish callback snapshots from new connections.
    private suspend fun configure(value: ConnectionRules) {
        releaseListeners()
        rules = value
        configured = true
        diagnostic("connection_configure", "enabled" to value.enabled, "wifi_targets" to value.wifi.size,
            "bluetooth_targets" to value.bluetooth.size)
        transitions = ConnectionTransitions(value)
        recording = withContext(Dispatchers.IO) { container.footprints.state() }
        if (!value.enabled || !value.hasTargets) { stopSelf(); return }
        permissionState = connectionAutomationAllowed(value)
        diagnostic("connection_permissions", "allowed" to permissionState)
        val generation = epoch
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (value.bluetooth.isNotEmpty()) {
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                post(generation) {
                    if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION || !connectionAutomationAllowed(rules)) {
                        configure(rules)
                    } else if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                            transitions.bluetoothOff()
                            changed()
                        }
                    } else {
                        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        device?.address?.let { address ->
                            if (transitions.bluetooth(address, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)) changed()
                        }
                    }
                }
            }
        }
        // Bluetooth broadcasts originate from a privileged system app, not always the system UID.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (!permissionState) { updateStatus(R.string.connection_automation_permissions); return }
        updateStatus(R.string.connection_automation_watching)
        if (value.wifi.isNotEmpty()) {
            initialNetworks += connectivity.allNetworks.filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            transitions.wifi(emptySet(), seed = true)
            val caps: (Network, NetworkCapabilities) -> Unit = { network, capabilities ->
                post(generation) {
                    if (!wifiAllowed(background = true)) { configure(rules); return@post }
                    val ssid = connectedSsid(capabilities)
                    if (network in networks && networks[network] == ssid) return@post
                    networks[network] = ssid
                    val initial = if (ssid != null) initialNetworks.remove(network) else network in initialNetworks
                    updateWifi(initial)
                }
            }
            val lost: (Network) -> Unit = { network ->
                post(generation) {
                    val known = networks.containsKey(network)
                    val pending = unresolvedNetworks.remove(network)
                    networks.remove(network)
                    initialNetworks.remove(network)
                    if (known || pending) {
                        identitySeed = false
                        transitions.resolvingWifi(unresolvedNetworks.isNotEmpty())
                        if (unresolvedNetworks.isEmpty()) stopIdentityLookup("disconnected")
                        updateWifi(seed = false)
                    }
                }
            }
            callback = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback() {
                // Persistent callbacks deliberately omit location info: RSSI/validation changes
                // must not keep accessing location after an SSID has already been identified.
                override fun onAvailable(network: Network) = post(generation) {
                    if (network in networks || !unresolvedNetworks.add(network)) return@post
                    if (identityCallback == null) identitySeed = network in initialNetworks
                    else if (network !in initialNetworks) identitySeed = false
                    transitions.resolvingWifi(true)
                    startIdentityLookup(generation)
                }
                override fun onLost(network: Network) = lost(network)
            } else object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = caps(network, capabilities)
                override fun onLost(network: Network) = lost(network)
            }
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback!!)
        }
        if (value.bluetooth.isNotEmpty()) seedBluetooth(generation)
    }

    private fun updateWifi(seed: Boolean) {
        if (unresolvedNetworks.isNotEmpty()) return
        val known = networks.values.none { it == null }
        transitions.wifi(if (known) networks.values.filterNotNull().toSet() else null, seed)
        updateStatus(if (known) R.string.connection_automation_watching else R.string.connection_automation_wifi_unknown)
        changed()
    }

    @androidx.annotation.RequiresApi(31)
    private fun startIdentityLookup(generation: Int) {
        if (identityCallback != null) return
        val lookup = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                post(generation) {
                    if (identityCallback !== this || network !in unresolvedNetworks) return@post
                    if (!wifiAllowed(background = true)) { configure(rules); return@post }
                    // Never substitute the primary Wi-Fi's SSID for a secondary network.
                    val ssid = capabilities.wifiSsid() ?: return@post
                    networks[network] = ssid
                    unresolvedNetworks.remove(network)
                    initialNetworks.remove(network)
                    if (unresolvedNetworks.isEmpty()) {
                        transitions.resolvingWifi(false)
                        stopIdentityLookup("resolved")
                        updateWifi(identitySeed)
                    }
                }
            }
        }
        identityCallback = lookup
        // Bound access even when the system redacts identity. No polling of stable connections.
        identityTimeout = scope.launch {
            delay(IDENTITY_TIMEOUT_MS)
            post(generation) {
                if (identityCallback !== lookup) return@post
                unresolvedNetworks.forEach { networks[it] = null }
                unresolvedNetworks.clear()
                transitions.resolvingWifi(false)
                stopIdentityLookup("timeout")
                updateWifi(identitySeed)
            }
        }
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), lookup)
            diagnostic("wifi_identity_lookup", "state" to "started")
        } catch (e: Exception) {
            unresolvedNetworks.forEach { networks[it] = null }
            unresolvedNetworks.clear()
            transitions.resolvingWifi(false)
            stopIdentityLookup("registration_failed")
            updateWifi(identitySeed)
            throw e
        }
    }

    private fun stopIdentityLookup(reason: String) {
        identityTimeout?.cancel()
        identityTimeout = null
        identityCallback?.let {
            runCatching { connectivity.unregisterNetworkCallback(it) }
            diagnostic("wifi_identity_lookup", "state" to "stopped", "reason" to reason)
        }
        identityCallback = null
    }

    private fun seedBluetooth(generation: Int) {
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            rules.bluetooth.keys.forEach { address ->
                val device = bluetooth?.getRemoteDevice(address) ?: return@forEach
                transitions.bluetooth(address, device.isConnected(BluetoothDevice.TRANSPORT_BREDR) ||
                    device.isConnected(BluetoothDevice.TRANSPORT_LE), seed = true)
            }
            return
        }
        val manager = getSystemService(BluetoothManager::class.java) ?: return
        for (profile in listOf(BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER)) {
            runCatching { manager.getConnectedDevices(profile) }.getOrDefault(emptyList()).forEach {
                transitions.bluetooth(it.address, true, seed = true)
            }
        }
        for (profile in listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)) {
            bluetooth?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                    if (generation != epoch) { bluetooth?.closeProfileProxy(type, proxy); return }
                    post {
                        if (generation != epoch) bluetooth?.closeProfileProxy(type, proxy)
                        else {
                            profiles += type to proxy
                            proxy.connectedDevices.forEach { transitions.bluetooth(it.address, true, seed = true) }
                        }
                    }
                }
                override fun onServiceDisconnected(type: Int) = Unit
            }, profile)
        }
    }

    private fun companionSignal(signal: ConnectionSignal, previous: Boolean?, cold: Boolean) = post {
        val current = withContext(Dispatchers.IO) { container.connectionRules.load() }
        if (!current.enabled || signal.id !in current.bluetooth) return@post
        if (!configured || current != rules) configure(current)
        if (!permissionState) return@post
        val hasChanged = if (cold) transitions.restoreBluetoothTransition(signal.id, previous, signal.connected)
            else transitions.bluetooth(signal.id, signal.connected, seed = previous == null)
        if (hasChanged) changed()
    }

    private fun changed() {
        diagnostic("connection_change_pending", "settle_after_ms" to SETTLE_DELAY_MS)
        debounce?.cancel()
        val version = ++changeVersion
        val generation = epoch
        debounce = scope.launch {
            delay(SETTLE_DELAY_MS)
            post(generation) {
                if (version != changeVersion) return@post
                if (!connectionAutomationAllowed(rules)) { configure(rules); return@post }
                val state = withContext(Dispatchers.IO) { container.footprints.state() }
                val config = withContext(Dispatchers.IO) { container.connectionRules.load() }
                if (config != rules || !config.enabled) return@post
                if (recording != null && recording != state) {
                    // A manual change may reach the database before its revision observer runs.
                    transitions.settle(state, manual = true)
                    recording = state
                    diagnostic("connection_action_skipped", "reason" to "recording_changed")
                    return@post
                }
                val signal = transitions.settle(state) ?: return@post
                val action = signal.command(state) ?: return@post
                val applied = RecordingService.control(this@ConnectionReminderService, action, expectedState = state)
                diagnostic("connection_action", "kind" to signal.kind.name, "connected" to signal.connected,
                    "action" to action, "accepted" to applied)
                recording = withContext(Dispatchers.IO) { container.footprints.state() }
                val failed = RecordingFailure.isFailure(RecordingService.status.value)
                updateStatus(if (failed) RecordingService.status.value ?: R.string.connection_automation_auto_failed else R.string.connection_automation_watching)
            }
        }
    }

    private fun diagnostic(event: String, vararg fields: Pair<String, Any?>) {
        container.footprintDiagnostics.record(event, mapOf("elapsed_ms" to SystemClock.elapsedRealtime()) + fields.toMap())
    }

    private fun openMap() = PendingIntent.getActivity(this, 2300,
        Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData("moments://map".toUri()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun watchNotification(message: Int) = NotificationCompat.Builder(this, WATCH_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_connections).setContentTitle(getString(R.string.connection_automation_title))
        .setContentText(getString(message)).setContentIntent(openMap()).setOngoing(true).setOnlyAlertOnce(true).build()

    private fun updateStatus(message: Int) {
        _status.value = message
        notifications.notify(WATCH_ID, watchNotification(message))
    }

    private fun releaseListeners() {
        epoch++
        debounce?.cancel()
        changeVersion++
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        stopIdentityLookup("released")
        unresolvedNetworks.clear()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        profiles.forEach { (type, proxy) -> runCatching { bluetooth?.closeProfileProxy(type, proxy) } }
        profiles.clear()
        networks.clear()
        initialNetworks.clear()
    }

    override fun onDestroy() {
        releaseListeners()
        events.close()
        scope.cancel()
        if (live === this) { live = null; _status.value = null }
        super.onDestroy()
    }

    companion object {
        private const val SETTLE_DELAY_MS = 5_000L
        private const val IDENTITY_TIMEOUT_MS = 10_000L
        private const val WATCH_CHANNEL = "connection-watch"
        private const val WATCH_ID = 2301
        private var live: ConnectionReminderService? = null
        private val _status = MutableStateFlow<Int?>(null)
        val status = _status.asStateFlow()

        internal fun companionEvent(context: Context, signal: ConnectionSignal, previous: Boolean?) {
            val service = live
            if (service != null) {
                service.companionSignal(signal, previous, cold = false)
                return
            }
            val intent = Intent(context, ConnectionReminderService::class.java)
                .putExtra("companion_address", signal.id).putExtra("companion_connected", signal.connected)
            if (previous != null) intent.putExtra("companion_previous", previous)
            try { ContextCompat.startForegroundService(context, intent) }
            catch (e: Exception) {
                _status.value = RecordingFailure.message(e)
                (context.applicationContext as MomentsApplication).container.footprintDiagnostics.record(
                    "companion_start_failed", mapOf("reason" to RecordingFailure.reason(e), "exception" to e.javaClass.simpleName))
            }
        }

        suspend fun sync(context: Context) {
            val app = context.applicationContext
            val rules = withContext(Dispatchers.IO) { (app as MomentsApplication).container.connectionRules.load() }
            CompanionConnections.sync(app, rules)
            if (!rules.enabled || !rules.hasTargets) {
                app.stopService(Intent(app, ConnectionReminderService::class.java))
            } else {
                val service = live
                if (service == null) {
                    try { ContextCompat.startForegroundService(app, Intent(app, ConnectionReminderService::class.java)) }
                    catch (_: Exception) { _status.value = R.string.connection_automation_error }
                } else service.post {
                    if (service.permissionState != app.connectionAutomationAllowed(rules)) service.configure(rules)
                }
            }
        }
    }
}
