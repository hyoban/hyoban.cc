package cc.hyoban.moments.footprints

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationRequest
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.*
import androidx.core.location.LocationManagerCompat
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
    private var lastActivityNanos = SystemClock.elapsedRealtimeNanos()
    private val runId = java.util.UUID.randomUUID().toString()
    private val manager get() = getSystemService(LocationManager::class.java)
    private val sensors get() = getSystemService(SensorManager::class.java)
    private var subscribed = false
    private var listener: LocationListener? = null
    private var generation = 0
    private var acquiring = false
    private var batching = false
    private val batchHealth = BatchDeliveryHealth()
    private var configured = false
    private var segmentId: String? = null
    private var segmentStartNanos = 0L
    private var lastDeliveredNanos = 0L
    private var fallback: CancellationSignal? = null
    private var fallbackPolicy: DailyRecordingPolicy? = null
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
        live = this
        diagnostic("service_start", "app_version" to BuildConfig.VERSION_NAME, "app_version_code" to BuildConfig.VERSION_CODE,
            "policy" to "daily-v4", "sdk" to Build.VERSION.SDK_INT, "os" to Build.VERSION.RELEASE,
            "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "fix_timeout_ms" to DailyRecordingPolicy.FIX_TIMEOUT, "still_timeout_ms" to DailyRecordingPolicy.STILL_TIMEOUT,
            "rest_interval_ms" to DailyRecordingPolicy.REST_INTERVAL, "max_retry_ms" to DailyRecordingPolicy.MAX_RETRY)
        if (Build.VERSION.SDK_INT >= 34) diagnostic("gnss_capabilities", "scheduling" to manager.gnssCapabilities.hasScheduling())
        // Older builds permanently disabled batching after one failure. The new cooldown is reversible.
        getSharedPreferences("footprint-location", MODE_PRIVATE).edit().remove("disabled_build").apply()
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
            val message = if (intent?.action == ACTIVITY_WAKE && configured) _status.value ?: R.string.footprints_waiting else R.string.footprints_waiting
            ServiceCompat.startForeground(this, RecordingNotifications.ID, RecordingNotifications.build(this, message),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: Exception) {
            startFailed(e)
            return START_NOT_STICKY
        }
        events.trySend {
            configure(if (intent?.action == ACTIVITY_WAKE) "activity_recovery" else if (intent == null) "system_restart" else "start_command",
                if (intent?.action == ACTIVITY_WAKE) intent.getLongExtra("motion_elapsed_nanos", -1) else null)
        }
        return START_STICKY
    }

    private suspend fun configure(source: String, motionWake: Long? = null) {
        val state = withContext(Dispatchers.IO) { store.state() }
        if (motionWake != null) {
            // Recheck after delivery: a pause, trip start or permission change may have overtaken the wake.
            if (!ActivityWakePolicy.shouldRecover(state, true, motionWake, SystemClock.elapsedRealtimeNanos()) ||
                !activityRecoveryAllowed(this)) {
                diagnostic("activity_recovery_skipped", "reason" to "state_or_permission_changed")
                if (!configured) stopSelfResult(latestStartId)
                return
            }
            if (configured) {
                if (motionWake > lastActivityNanos) {
                    lastActivityNanos = motionWake
                    daily?.motion(SystemClock.elapsedRealtime())
                    applyDailyPolicy()
                }
                return
            }
        }
        configured = true
        diagnostic("configure", "source" to source, "daily" to state.daily, "trip" to (state.session != null),
            "paused" to state.paused, *environment())
        stopUpdates()
        stopFallback()
        stopMotion()
        stopPassive()
        policyTimer?.cancel()
        tripWatchdog?.cancel()
        daily = null
        reportedPhase = null
        (application as MomentsApplication).container.scope.launch {
            yield()
            ActivityTransitions.sync(this@RecordingService)
        }
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

    private suspend fun startUpdates(trip: Boolean, newSegment: Boolean = true, batch: Boolean = false) {
        val available = manager.getProviders(true)
        val provider = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER).firstOrNull { it in available }
        if (provider == null) { updateStatus(R.string.footprints_location_off); return }
        if (newSegment) {
            segmentId = withContext(Dispatchers.IO) { store.breakSegment() }
            segmentStartNanos = SystemClock.elapsedRealtimeNanos()
            lastDeliveredNanos = 0
        }
        val interval = if (trip) 5_000L else LocationDeliveryPolicy.DAILY_INTERVAL_MS
        val distance = if (trip) 5f else 0f
        val batched = !trip && batch && Build.VERSION.SDK_INT >= 31 && batchHealth.canBatch(SystemClock.elapsedRealtime())
        batching = batched
        acquiring = !trip && !batch
        val version = ++generation
        requestStarted = SystemClock.elapsedRealtime()
        if (batched) batchHealth.started(requestStarted)
        callbacks = 0
        usableFixes = 0
        storedFixes = 0
        val updates = object : LocationListener {
            override fun onLocationChanged(location: Location) = received(listOf(location), version)
            override fun onLocationChanged(locations: MutableList<Location>) = received(locations, version)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy LocationListener callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            listener = updates
            if (Build.VERSION.SDK_INT >= 31) {
                val request = LocationRequest.Builder(interval)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(interval)
                    .setMinUpdateDistanceMeters(distance)
                    .setMaxUpdateDelayMillis(if (batched) LocationDeliveryPolicy.BATCH_DELAY_MS else 0)
                    // First fix / stationary probes stop at the system even if our process sleeps.
                    .setDurationMillis(if (acquiring) DailyRecordingPolicy.FIX_TIMEOUT else Long.MAX_VALUE)
                    .build()
                manager.requestLocationUpdates(provider, request, mainExecutor, updates)
            } else manager.requestLocationUpdates(provider, interval, distance, updates, Looper.getMainLooper())
            subscribed = true
            diagnostic("location_request_start", "provider" to provider, "interval_ms" to interval,
                "distance_m" to distance, "trip" to trip, "segment_break" to newSegment,
                "batch_delay_ms" to if (batched) LocationDeliveryPolicy.BATCH_DELAY_MS else 0,
                "system_timeout_ms" to if (acquiring && Build.VERSION.SDK_INT >= 31) DailyRecordingPolicy.FIX_TIMEOUT else null)
            if (newSegment) {
                lastFix = SystemClock.elapsedRealtime()
                updateStatus(R.string.footprints_waiting)
            }
        } catch (e: SecurityException) { startFailed(e) }
    }

    private fun received(locations: List<Location>, version: Int) {
        val copies = locations.map(::Location).sortedBy { it.elapsedRealtimeNanos }
        events.trySend {
            if (version != generation || !subscribed) {
                diagnostic("fix_rejected", "reason" to "old_request", "callback_generation" to version)
                return@trySend
            }
            callbacks += copies.size
            val maxAge = if (daily != null) LocationDeliveryPolicy.DAILY_MAX_AGE_MS else 120_000L
            var freshSamples = 0
            val samples = copies.mapNotNull { copy ->
                if (!LocationDeliveryPolicy.acceptsTime(copy.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(),
                        segmentStartNanos, lastDeliveredNanos, maxAge)) {
                    diagnostic("fix_rejected", "reason" to "sample_time", *sampleFields(copy))
                    return@mapNotNull null
                }
                // Fresh but inaccurate locations are a quality problem, not a broken batch delivery.
                lastDeliveredNanos = copy.elapsedRealtimeNanos
                freshSamples++
                val point = usablePoint(copy, maxAge * 1_000_000, "active") ?: return@mapNotNull null
                copy to point
            }
            val deliveryTime = SystemClock.elapsedRealtime()
            if (batching && freshSamples > 0) batchHealth.freshDelivery(deliveryTime)
            if (samples.isEmpty()) {
                if (freshSamples > 0) diagnostic("location_delivery_quality", "fresh_samples" to freshSamples, "usable_samples" to 0)
                if (batching && freshSamples == 0 && batchHealth.staleOnly(deliveryTime))
                    backOffBatching("stale_delivery")
                if (daily != null) applyDailyPolicy()
                return@trySend
            }
            usableFixes += samples.size
            val decisions = withContext(Dispatchers.IO) { store.appendBatch(samples.map { it.second }, expectedSegment = segmentId) }
            for ((index, sample) in samples.withIndex()) {
                val (copy, point) = sample
                val decision = decisions[index]
                if (decision == FixDecision.ACCEPT) storedFixes++
                diagnostic("fix", "stored" to (decision == FixDecision.ACCEPT), "decision" to decision.name, *sampleFields(copy))
                if (decision == FixDecision.NOT_RECORDING) continue
                lastFix = copy.elapsedRealtimeNanos / 1_000_000
                val moving = copy.hasSpeed() && copy.hasSpeedAccuracy() &&
                    copy.speedAccuracyMetersPerSecond <= 0.5f && copy.speed - copy.speedAccuracyMetersPerSecond >= 0.5f
                // Observe the complete batch before deciding to sleep: its final points may be moving.
                daily?.fix(point, lastFix, moving, allowRest = index == samples.lastIndex)
            }
            if (decisions.all { it == FixDecision.NOT_RECORDING }) return@trySend
            diagnostic("location_delivery", "samples" to samples.size, "oldest_age_ms" to
                (SystemClock.elapsedRealtimeNanos() - samples.first().first.elapsedRealtimeNanos) / 1_000_000)
            updateStatus(R.string.footprints_recording)
            if (acquiring && daily?.phase == DailyRecordingPolicy.Phase.ACTIVE) {
                // Acquisition has no batching. Switch after a usable fix without creating a track gap.
                stopUpdates()
                startUpdates(trip = false, newSegment = false, batch = true)
            } else if (daily?.phase == DailyRecordingPolicy.Phase.ACTIVE && !batching && !acquiring &&
                Build.VERSION.SDK_INT >= 31 && batchHealth.canBatch(deliveryTime)) {
                // Retry only on a good active fix, never by waking GPS out of stationary rest.
                diagnostic("batching_retry")
                stopUpdates()
                startUpdates(trip = false, newSegment = false, batch = true)
            } else if (acquiring && daily?.phase == DailyRecordingPolicy.Phase.PROBE &&
                SystemClock.elapsedRealtime() - requestStarted >= DailyRecordingPolicy.FIX_TIMEOUT - 45_000) {
                // A late first fix still needs enough bounded time for independent stop observations.
                stopUpdates()
                startUpdates(trip = false, newSegment = false)
            }
            if (daily != null) applyDailyPolicy()
        }
    }

    private suspend fun backOffBatching(reason: String) {
        val now = SystemClock.elapsedRealtime()
        batchHealth.backOff(now)
        diagnostic("batching_backoff", "reason" to reason, "retry_in_ms" to (batchHealth.retryAt - now))
        stopUpdates()
        lastFix = SystemClock.elapsedRealtime()
        daily?.restartAcquisition(lastFix)
        startUpdates(trip = false, newSegment = false)
    }

    private fun stopUpdates() {
        if (subscribed) diagnostic("location_request_stop", "duration_ms" to (SystemClock.elapsedRealtime() - requestStarted),
            "callbacks" to callbacks, "usable_fixes" to usableFixes, "stored_fixes" to storedFixes)
        generation++ // Callbacks queued by an older request must not restart a sleeping recorder.
        listener?.let { manager.removeUpdates(it) }
        listener = null
        subscribed = false
        batching = false
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
            if (policy.restReason == DailyRecordingPolicy.RestReason.NO_FIX) startFallback(policy)
        } else {
            stopFallback()
            stopMotion()
            stopPassive()
            if (!subscribed) startUpdates(trip = false)
        }
        // Handler-backed delay does not wake the CPU from deep sleep. The significant-motion
        // sensor is the wake-up path; this fallback may run later under Doze, without exact alarms.
        policyTimer = scope.launch {
            val due = minOf(policy.nextCheck,
                if (batching) batchHealth.deadline else Long.MAX_VALUE)
            delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            events.trySend {
                if (daily === policy) {
                    diagnostic("timer", "late_ms" to (SystemClock.elapsedRealtime() - due).coerceAtLeast(0))
                    if (batching && batchHealth.stalled(SystemClock.elapsedRealtime()))
                        backOffBatching("delivery_timeout")
                    else policy.tick(SystemClock.elapsedRealtime())
                    applyDailyPolicy()
                }
            }
        }
    }

    private fun startFallback(policy: DailyRecordingPolicy) {
        if (fallbackPolicy === policy || fallback != null) return
        fallbackPolicy = policy
        val available = manager.getProviders(true)
        val provider = listOf(LocationManager.NETWORK_PROVIDER, "fused").firstOrNull { it in available } ?: return
        val cancellation = CancellationSignal()
        fallback = cancellation
        diagnostic("fallback_start", "provider" to provider)
        val accept: (Location?) -> Unit = { location ->
            val copy = location?.let(::Location)
            events.trySend {
                if (fallback !== cancellation || daily !== policy) return@trySend
                fallback = null
                val age = copy?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                val point = copy?.takeIf { it.hasAccuracy() && age != null && age in 0..30_000_000_000L }
                    ?.let { TrackPoint(it.time, it.latitude, it.longitude, it.accuracy.toDouble()) }
                    ?.takeIf { it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0 }
                val woke = point != null && policy.fallbackFix(point, SystemClock.elapsedRealtime())
                diagnostic("fallback_result", "available" to (point != null), "woke" to woke,
                    "accuracy_m" to point?.accuracy)
                if (woke) applyDailyPolicy()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                manager.getCurrentLocation(provider, LocationRequest.Builder(0)
                    .setQuality(LocationRequest.QUALITY_LOW_POWER)
                    .setDurationMillis(LocationDeliveryPolicy.FALLBACK_DURATION_MS).build(),
                    cancellation, mainExecutor, accept)
            } else {
                LocationManagerCompat.getCurrentLocation(manager, provider, cancellation,
                    ContextCompat.getMainExecutor(this), accept)
            }
        } catch (e: SecurityException) {
            fallback = null
            diagnostic("fallback_failed", "exception" to e.javaClass.simpleName, "reason" to "permission_denied")
        } catch (e: Exception) {
            fallback = null
            diagnostic("fallback_failed", "exception" to e.javaClass.simpleName)
        }
    }

    private fun stopFallback() {
        fallback?.cancel()
        fallback = null
        fallbackPolicy = null
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

    private fun startFailed(error: Exception) {
        diagnostic("recording_start_failed", "exception" to error.javaClass.simpleName,
            "reason" to RecordingFailure.reason(error))
        fail(RecordingFailure.message(error))
    }

    private fun fail(message: Int) {
        diagnostic("service_failure", "reason" to resources.getResourceEntryName(message), *environment())
        _status.value = message
        stopSelf()
    }

    private fun updateStatus(message: Int) {
        if (_status.value == message) return
        _status.value = message
        getSystemService(NotificationManager::class.java).notify(RecordingNotifications.ID, RecordingNotifications.build(this, message))
    }

    override fun onDestroy() {
        diagnostic("service_destroy")
        if (live === this) live = null
        (application as MomentsApplication).container.scope.launch { yield(); ActivityTransitions.sync(this@RecordingService) }
        stopUpdates()
        stopFallback()
        stopMotion()
        stopPassive()
        unregisterReceiver(providers)
        scope.cancel()
        events.close()
        if (_status.value in listOf(R.string.footprints_recording, R.string.footprints_waiting, R.string.footprints_location_off, R.string.footprints_resting)) _status.value = null
        super.onDestroy()
    }

    companion object {
        private const val ACTIVITY_WAKE = "cc.hyoban.moments.ACTIVITY_WAKE"
        private var live: RecordingService? = null

        internal fun refreshActivityRecognition(context: Context) {
            val app = context.applicationContext as MomentsApplication
            app.container.scope.launch { ActivityTransitions.sync(app) }
        }

        private fun activityRecoveryAllowed(context: Context) = ActivityTransitions.enabled(context) &&
            ActivityTransitions.permitted(context) && hasPreciseLocation(context) &&
            (Build.VERSION.SDK_INT < 29 || context.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))

        internal suspend fun activityTransition(context: Context, motion: ActivityMotion) {
            val (elapsedNanos, moving, activity) = motion
            val service = live
            if (service == null) {
                val container = (context.applicationContext as MomentsApplication).container
                val state = withContext(Dispatchers.IO) { container.footprints.state() }
                if (!ActivityWakePolicy.shouldRecover(state, moving, elapsedNanos, SystemClock.elapsedRealtimeNanos()) ||
                    !activityRecoveryAllowed(context)) {
                    container.footprintDiagnostics.record("activity_recovery_skipped", mapOf("reason" to "state_or_permission"))
                    return
                }
                try {
                    // Starting a service does not change saved state. configure() rechecks it before requesting GPS.
                    ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)
                        .setAction(ACTIVITY_WAKE).putExtra("motion_elapsed_nanos", elapsedNanos))
                    container.footprintDiagnostics.record("activity_recovery_requested", mapOf("activity" to activity))
                } catch (e: Exception) {
                    _status.value = RecordingFailure.message(e)
                    container.footprintDiagnostics.record("recording_start_failed", mapOf("source" to "activity_recovery",
                        "exception" to e.javaClass.simpleName, "reason" to RecordingFailure.reason(e)))
                }
                return
            }
            val done = CompletableDeferred<Unit>()
            if (service.events.trySend {
                try {
                    val policy = service.daily ?: return@trySend
                    if (!ActivityTransitions.enabled(service) || elapsedNanos <= service.lastActivityNanos) return@trySend
                    val state = withContext(Dispatchers.IO) { service.store.state() }
                    if (!state.recording || state.session != null) return@trySend
                    service.lastActivityNanos = elapsedNanos
                    service.diagnostic("activity_transition", "moving" to moving, "activity" to activity)
                    // STILL is advisory: only locations confirm a stop, so a traffic light cannot pause a trip.
                    if (moving) {
                        policy.motion(SystemClock.elapsedRealtime())
                        service.applyDailyPolicy()
                    }
                } finally { done.complete(Unit) }
            }.isSuccess) done.await()
        }

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
                // A dead service has no onDestroy callback to unregister its activity PendingIntent.
                container.scope.launch { ActivityTransitions.sync(app) }
            } else if (action != null || _status.value == null || RecordingFailure.isFailure(_status.value)) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
                } catch (e: Exception) {
                    _status.value = RecordingFailure.message(e)
                    container.footprintDiagnostics.record("recording_start_failed",
                        mapOf("exception" to e.javaClass.simpleName, "reason" to RecordingFailure.reason(e), "automatic" to (expectedState != null)))
                    return false
                }
            }
            return true
        }
    }
}
