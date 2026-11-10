package cc.hyoban.moments.camera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Sends the paired camera where the phone is while the camera is near and 给相机发位置 is on,
 * so the photos it takes carry their place: a foreground service, started by the system's
 * companion presence (the camera's Bluetooth seen) and stopped when it goes.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class CameraLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var latest: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val camera = CameraStore.load(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(this, camera?.name), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.w(TAG, "could not start in the foreground", e)
            stopSelf(); return START_NOT_STICKY
        }
        if (camera == null || !camera.sendLocation) { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive != true) job = scope.launch { run(camera) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun run(camera: PairedCamera) {
        CameraStore.update { it.copy(problem = null) }
        val listener = LocationListener { latest = it }
        val locations = getSystemService(LocationManager::class.java)
        val provider = locations?.getProviders(true)?.let { p -> listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in p } }
        if (provider == null || !CameraLink.locationAllowed(this)) {
            CameraStore.update { it.copy(sending = false, problem = R.string.camera_problem_location) }
            stopSelf(); return
        }
        locations.requestLocationUpdates(provider, FIX_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        try {
            val device = CameraLink.device(this, camera.address) ?: return
            while (scope.isActive) {
                val gatt = runCatching { Gatt.connect(this, device) }.getOrElse {
                    Log.w(TAG, "camera not reached", it); delay(RETRY_MS); continue
                }
                try { stream(gatt) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "location stream ended", e)
                }
                finally {
                    withTimeoutOrNull(3_000) {
                        runCatching { gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_ENABLE, byteArrayOf(0)) }
                        runCatching { gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_LOCK, byteArrayOf(0)) }
                    }
                    gatt.close()
                    CameraStore.update { it.copy(sending = false) }
                }
                // The camera said no for a reason only it can change: wait for it to come back.
                if (CameraStore.status.value.problem != null) { stopSelf(); return }
                delay(RETRY_MS)
            }
        } finally {
            locations.removeUpdates(listener)
        }
    }

    /** Holds the camera's location lock and writes a location every [SEND_INTERVAL_MS] until the link drops. */
    private suspend fun stream(gatt: Gatt) {
        gatt.subscribe(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_STATUS)
        try {
            gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_LOCK, byteArrayOf(1))
        } catch (e: GattException) {
            val problem = when (e.status) {
                SonyBle.STATUS_LOCATION_OFF -> R.string.camera_problem_link_off
                SonyBle.STATUS_LOCATION_TAKEN -> R.string.camera_problem_taken
                else -> null
            }
            if (problem != null) CameraStore.update { it.copy(sending = false, problem = problem) }
            throw e
        }
        gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_ENABLE, byteArrayOf(1))
        val withZone = SonyBle.takesTimeZone(runCatching { gatt.read(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_INFO) }.getOrNull())
        val stopped = scope.launch {
            // The camera says on DD01 when it stops taking locations (位置信息链接 turned off on it).
            gatt.notifications.first { it.first == SonyBle.LOCATION_STATUS }
            CameraStore.update { it.copy(problem = R.string.camera_problem_link_off) }
            gatt.close()
        }
        try {
            while (gatt.connected.value) {
                val fix = latest
                if (fix != null && fixAgeMs(fix) < MAX_FIX_AGE_MS) {
                    // A fix newer than the last write's goes as it is; an older one, standing still,
                    // goes again with the time now, or the camera marks the link as lost after a while.
                    val time = if (fixAgeMs(fix) < FRESH_MS) Instant.ofEpochMilli(fix.time) else Instant.now()
                    gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_DATA,
                        SonyBle.locationPacket(fix.latitude, fix.longitude, time, ZoneId.systemDefault(), withZone))
                    CameraStore.sent(this, System.currentTimeMillis())
                }
                delay(SEND_INTERVAL_MS)
            }
        } finally { stopped.cancel() }
    }

    private fun fixAgeMs(fix: Location) = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000

    override fun onDestroy() {
        scope.cancel()
        CameraStore.update { it.copy(sending = false) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION = 7301
        private const val CHANNEL = "camera_location"
        private const val FIX_INTERVAL_MS = 5_000L
        const val SEND_INTERVAL_MS = 10_000L
        private const val FRESH_MS = 15_000L
        private const val MAX_FIX_AGE_MS = 10 * 60_000L
        private const val RETRY_MS = 15_000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CameraLocationService::class.java)) }
                .onFailure { Log.w(TAG, "camera location service not started", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraLocationService::class.java))
        }

        private fun notification(context: Context, name: String?): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.camera_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0,
                Intent(Intent.ACTION_VIEW, "moments://camera".toUri(), context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_camera)
                .setContentTitle(context.getString(R.string.camera_sending))
                .setContentText(name ?: context.getString(R.string.camera_title))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
