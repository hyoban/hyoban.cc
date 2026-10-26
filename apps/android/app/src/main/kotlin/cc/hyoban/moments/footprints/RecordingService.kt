package cc.hyoban.moments.footprints

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cc.hyoban.moments.BuildConfig
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A user-enabled location foreground service; it never talks to the Moments API. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val store get() = (application as MomentsApplication).container.footprints
    private val diagnostics get() = (application as MomentsApplication).container.footprintDiagnostics
    private val runId = java.util.UUID.randomUUID().toString()
    private val manager get() = getSystemService(LocationManager::class.java)
    private val sensors get() = getSystemService(SensorManager::class.java)
    private var subscribed = false
    private var listener: LocationListener? = null
    private var generation = 0
    private var daily: DailyRecordingPolicy? = null
    private var policyTimer: Job? = null
    private var tripWatchdog: Job? = null
    private var motionListener: TriggerEventListener? = null
    private var passiveListener: LocationListener? = null
    private var reportedPhase: DailyRecordingPolicy.Phase? = null
    private var lastFix = 0L
    private var latestStartId = 0
    private var requestStarted = 0L
    private var callbacks = 0
    private var usableFixes = 0
    private var storedFixes = 0
    private val providers = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            events.trySend { configure("providers_changed") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        diagnostic("service_start", "app_version" to BuildConfig.VERSION_NAME, "app_version_code" to BuildConfig.VERSION_CODE,
            "policy" to "daily-v2", "sdk" to Build.VERSION.SDK_INT, "os" to Build.VERSION.RELEASE,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "fix_timeout_ms" to DailyRecordingPolicy.FIX_TIMEOUT, "still_timeout_ms" to DailyRecordingPolicy.STILL_TIMEOUT,
            "rest_interval_ms" to DailyRecordingPolicy.REST_INTERVAL, "max_retry_ms" to DailyRecordingPolicy.MAX_RETRY)
        RecordingNotifications.createChannels(this)
        ContextCompat.registerReceiver(this, providers, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            for (event in events) {
                try { event() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    diagnostic("event_error", "exception" to e.javaClass.simpleName)
                    fail(R.string.footprints_error)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // A pause from the notification still works after permissions have been revoked.
        if (intent?.action == RecordingNotifications.PAUSE) {
            events.trySend {
                diagnostic("command", "action" to "notification_pause")
                withContext(Dispatchers.IO) { store.command("pause") }
                stopSelfResult(latestStartId)
            }
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, RecordingNotifications.build(this, R.string.footprints_waiting),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (_: Exception) {
            fail(R.string.footprints_permission_needed)
            return START_NOT_STICKY
        }
        events.trySend { configure(if (intent == null) "system_restart" else "start_command") }
        return START_STICKY
    }

    private suspend fun configure(source: String) {
        val state = withContext(Dispatchers.IO) { store.state() }
        diagnostic("configure", "source" to source, "daily" to state.daily, "trip" to (state.session != null),
            "paused" to state.paused, *environment())
        stopUpdates()
        stopMotion()
        stopPassive()
        policyTimer?.cancel()
        tripWatchdog?.cancel()
        daily = null
        reportedPhase = null
        if (!state.recording) { stopSelfResult(latestStartId); return }
        if (!hasPreciseLocation(this)) { fail(R.string.footprints_permission_needed); return }
        if (!manager.isLocationEnabled) {
            updateStatus(R.string.footprints_location_off)
            return // Keep the service alive to recover when location is switched back on.
        }
        if (state.session == null) {
            daily = DailyRecordingPolicy(SystemClock.elapsedRealtime())
            applyDailyPolicy()
        } else {
            startUpdates(trip = true)
            tripWatchdog = scope.launch {
                while (isActive) {
                    delay(15_000)
                    if (subscribed && SystemClock.elapsedRealtime() - lastFix > 120_000) updateStatus(R.string.footprints_waiting)
                }
            }
        }
    }

    private suspend fun startUpdates(trip: Boolean) {
        val available = manager.getProviders(true)
        // Prefer GNSS: a balanced fused provider may never start GPS when offline.
        val provider = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER).firstOrNull { it in available }
        if (provider == null) { updateStatus(R.string.footprints_location_off); return }
        // Daily fixes need to arrive while stationary too, so the policy can stop GPS.
        // The store still filters stationary points rather than writing every callback.
        val interval = if (trip) 5_000L else 15_000L
        val distance = if (trip) 5f else 0f
        withContext(Dispatchers.IO) { store.command("break") }
        val version = ++generation
        requestStarted = SystemClock.elapsedRealtime()
        callbacks = 0
        usableFixes = 0
        storedFixes = 0
        val updates = object : LocationListener {
            override fun onLocationChanged(location: Location) = received(location, version)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy LocationListener callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            listener = updates
            manager.requestLocationUpdates(provider, interval, distance, updates, Looper.getMainLooper())
            subscribed = true
            diagnostic("location_request_start", "provider" to provider, "interval_ms" to interval,
                "distance_m" to distance, "trip" to trip, "segment_break" to true)
            lastFix = SystemClock.elapsedRealtime()
            updateStatus(R.string.footprints_waiting)
        } catch (_: SecurityException) { fail(R.string.footprints_permission_needed) }
    }

    private fun received(location: Location, version: Int) {
        val copy = Location(location)
        events.trySend {
            if (version != generation || !subscribed) {
                diagnostic("fix_rejected", "reason" to "old_request", "callback_generation" to version)
                return@trySend
            }
            callbacks++
            val point = usablePoint(copy, if (daily != null) 30_000_000_000L else 120_000_000_000L, "active") ?: return@trySend
            usableFixes++
            val decision = withContext(Dispatchers.IO) { store.append(point) }
            if (decision == FixDecision.ACCEPT) storedFixes++
            diagnostic("fix", "stored" to (decision == FixDecision.ACCEPT), "decision" to decision.name, *sampleFields(copy))
            lastFix = SystemClock.elapsedRealtime()
            updateStatus(R.string.footprints_recording)
            daily?.let {
                // Accurate Doppler speed can distinguish slow walking from noisy coordinates.
                val moving = copy.hasSpeed() && copy.hasSpeedAccuracy() &&
                    copy.speedAccuracyMetersPerSecond <= 0.5f && copy.speed - copy.speedAccuracyMetersPerSecond >= 0.5f
                it.fix(point, lastFix, moving)
                applyDailyPolicy()
            }
        }
    }

    private fun stopUpdates() {
        if (subscribed) diagnostic("location_request_stop", "duration_ms" to (SystemClock.elapsedRealtime() - requestStarted),
            "callbacks" to callbacks, "usable_fixes" to usableFixes, "stored_fixes" to storedFixes)
        generation++ // Callbacks queued by an older request must not restart a sleeping recorder.
        listener?.let { manager.removeUpdates(it) }
        listener = null
        subscribed = false
    }

    private suspend fun applyDailyPolicy() {
        val policy = daily ?: return
        policyTimer?.cancel()
        if (reportedPhase != policy.phase) {
            android.util.Log.i("Footprints", "Daily ${reportedPhase ?: "start"} -> ${policy.phase}; reason=${policy.restReason}")
            diagnostic("phase", "from" to reportedPhase?.name, "to" to policy.phase.name,
                "reason" to policy.restReason?.name, "next_check_in_ms" to (policy.nextCheck - SystemClock.elapsedRealtime()),
                "last_fix_age_ms" to lastFix.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it }, *environment())
            reportedPhase = policy.phase
        }
        if (policy.phase == DailyRecordingPolicy.Phase.REST) {
            stopUpdates()
            armMotion()
            startPassive()
            updateStatus(R.string.footprints_resting)
        } else {
            stopMotion()
            stopPassive()
            if (!subscribed) startUpdates(trip = false)
        }
        // Handler-backed delay does not wake the CPU from deep sleep. The significant-motion
        // sensor is the wake-up path; this fallback may run later under Doze, without exact alarms.
        policyTimer = scope.launch {
            val due = policy.nextCheck
            delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            events.trySend {
                if (daily === policy) {
                    diagnostic("timer", "late_ms" to (SystemClock.elapsedRealtime() - due).coerceAtLeast(0))
                    policy.tick(SystemClock.elapsedRealtime())
                    applyDailyPolicy()
                }
            }
        }
    }

    private fun armMotion() {
        if (motionListener != null) return
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensor == null) {
            diagnostic("motion_sensor", "available" to false)
            return
        }
        val trigger = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                val source = this
                events.trySend {
                    if (motionListener !== source) return@trySend
                    diagnostic("wake", "source" to "significant_motion")
                    motionListener = null // One-shot sensor is automatically disarmed by Android.
                    daily?.motion(SystemClock.elapsedRealtime())
                    applyDailyPolicy()
                }
            }
        }
        val armed = runCatching { sensors.requestTriggerSensor(trigger, sensor) }.getOrDefault(false)
        if (armed) motionListener = trigger
        diagnostic("motion_sensor", "available" to true, "armed" to armed, "wake_up" to sensor.isWakeUpSensor)
    }

    private fun stopMotion() {
        motionListener?.let { sensors?.cancelTriggerSensor(it, null) }
        motionListener = null
    }

    private fun usablePoint(location: Location, maxAge: Long, source: String): TrackPoint? {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age !in 0..maxAge || !location.hasAccuracy()) {
            diagnostic("fix_rejected", "source" to source,
                "reason" to if (!location.hasAccuracy()) "missing_accuracy" else "elapsed_age", *sampleFields(location))
            return null
        }
        val point = TrackPoint(location.time, location.latitude, location.longitude, location.accuracy.toDouble(),
            if (location.hasAltitude()) location.altitude else null)
        val decision = evaluateFix(point, null, System.currentTimeMillis())
        if (decision != FixDecision.ACCEPT) diagnostic("fix_rejected", "source" to source,
            "reason" to decision.name, *sampleFields(location))
        return point.takeIf { decision == FixDecision.ACCEPT }
    }

    private fun startPassive() {
        if (passiveListener != null) return
        val updates = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val source = this
                val copy = Location(location)
                events.trySend {
                    if (passiveListener !== source) return@trySend
                    val point = usablePoint(copy, 30_000_000_000L, "passive") ?: return@trySend
                    val woke = daily?.passiveFix(point, SystemClock.elapsedRealtime()) == true
                    diagnostic("passive_fix", "woke" to woke, *sampleFields(copy))
                    if (woke) {
                        diagnostic("wake", "source" to "passive_displacement")
                        android.util.Log.i("Footprints", "Passive displacement woke daily recording")
                        applyDailyPolicy()
                    }
                }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy LocationListener callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            // Opportunistic observations only: this provider does not turn on GPS or scan Wi-Fi.
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 30_000L, 0f, updates, Looper.getMainLooper())
            passiveListener = updates
            diagnostic("passive_listener", "enabled" to true)
        } catch (e: IllegalArgumentException) { // Some devices do not provide passive locations.
            diagnostic("passive_listener", "enabled" to false, "exception" to e.javaClass.simpleName)
        } catch (e: SecurityException) { // Motion sensing and timed probes remain available.
            diagnostic("passive_listener", "enabled" to false, "exception" to e.javaClass.simpleName)
        }
    }

    private fun stopPassive() {
        if (passiveListener != null) diagnostic("passive_listener", "enabled" to false)
        passiveListener?.let { manager.removeUpdates(it) }
        passiveListener = null
    }

    private fun diagnostic(event: String, vararg fields: Pair<String, Any?>) {
        diagnostics.record(event, mapOf("run" to runId, "elapsed_ms" to SystemClock.elapsedRealtime(),
            "generation" to generation) + fields.toMap())
    }

    private fun sampleFields(location: Location): Array<Pair<String, Any?>> = arrayOf(
        "fix_time_ms" to location.time, "provider" to location.provider,
        "age_ms" to (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000,
        "accuracy_m" to location.accuracy.takeIf { location.hasAccuracy() },
        "speed_mps" to location.speed.takeIf { location.hasSpeed() },
        "speed_accuracy_mps" to location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
    )

    private fun environment(): Array<Pair<String, Any?>> {
        val power = getSystemService(PowerManager::class.java)
        return arrayOf(
            "battery_percent" to getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "power_save" to power.isPowerSaveMode, "device_idle" to power.isDeviceIdleMode,
            "interactive" to power.isInteractive, "location_enabled" to manager.isLocationEnabled,
            "precise_permission" to hasPreciseLocation(this),
            "background_permission" to (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED),
            "notifications_enabled" to getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        )
    }

    private fun fail(message: Int) {
        diagnostic("service_failure", "reason" to resources.getResourceEntryName(message), *environment())
        _status.value = message
        stopSelf()
    }

    private fun updateStatus(message: Int) {
        if (_status.value == message) return
        _status.value = message
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, RecordingNotifications.build(this, message))
    }

    override fun onDestroy() {
        diagnostic("service_destroy")
        stopUpdates()
        stopMotion()
        stopPassive()
        unregisterReceiver(providers)
        scope.cancel()
        events.close()
        if (_status.value in listOf(R.string.footprints_recording, R.string.footprints_waiting, R.string.footprints_location_off, R.string.footprints_resting)) _status.value = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION = 2201
        private val _status = MutableStateFlow<Int?>(null)
        val status = _status.asStateFlow()

        fun hasPreciseLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** State is saved before starting/stopping; denied permissions cannot trap the toggle on. */
        suspend fun control(context: Context, action: String? = null, expectedState: RecordingState? = null): Boolean {
            val app = context.applicationContext
            val container = (app as MomentsApplication).container
            val store = container.footprints
            val state = withContext(Dispatchers.IO) {
                if (action != null) {
                    if (expectedState != null) {
                        if (!store.commandIfUnchanged(action, expectedState)) return@withContext null
                    } else store.command(action)
                }
                store.state()
            } ?: return false
            if (action != null) container.footprintDiagnostics.record("command",
                mapOf("action" to action, "daily" to state.daily, "trip" to (state.session != null), "paused" to state.paused,
                    "elapsed_ms" to SystemClock.elapsedRealtime()))
            if (!state.recording) {
                app.stopService(Intent(app, RecordingService::class.java))
                _status.value = null
            } else if (action != null || _status.value == null) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
                } catch (e: Exception) {
                    _status.value = R.string.footprints_permission_needed
                    container.footprintDiagnostics.record("recording_start_failed",
                        mapOf("exception" to e.javaClass.simpleName, "automatic" to (expectedState != null)))
                    return false
                }
            }
            return true
        }
    }
}
