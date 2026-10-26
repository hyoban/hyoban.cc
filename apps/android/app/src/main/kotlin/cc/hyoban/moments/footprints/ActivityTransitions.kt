package cc.hyoban.moments.footprints

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Optional Google service enhancement; GNSS and the wake-up sensor never depend on it. */
internal object ActivityTransitions {
    private val lock = Mutex()
    private val _status = MutableStateFlow(R.string.footprints_activity_off)
    val status = _status.asStateFlow()
    private fun preferences(context: Context) = context.getSharedPreferences("footprint-activity", Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
        RecordingService.refreshActivityRecognition(context)
    }
    fun available(context: Context) = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 29 || context.granted(Manifest.permission.ACTIVITY_RECOGNITION)

    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 2401,
        Intent(context, ActivityTransitionReceiver::class.java).setAction("cc.hyoban.moments.ACTIVITY_TRANSITION"),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

    suspend fun sync(context: Context) = lock.withLock {
        val diagnostics = (context.applicationContext as MomentsApplication).container.footprintDiagnostics
        if (!available(context)) {
            _status.value = R.string.footprints_activity_unavailable
            diagnostics.record("activity_recognition", mapOf("state" to "unavailable"))
            return@withLock
        }
        val client = ActivityRecognition.getClient(context)
        try {
            if (!enabled(context) || !RecordingService.dailyRecordingActive() || !permitted(context)) {
                client.removeActivityTransitionUpdates(pending(context)).awaitResult()
                _status.value = when {
                    !enabled(context) -> R.string.footprints_activity_off
                    !permitted(context) -> R.string.footprints_activity_permission
                    else -> R.string.footprints_activity_ready
                }
            } else {
                val transitions = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING,
                    DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE, DetectedActivity.STILL).flatMap { activity ->
                    listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { type ->
                        ActivityTransition.Builder().setActivityType(activity).setActivityTransition(type).build()
                    }
                }
                client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(context)).awaitResult()
                _status.value = R.string.footprints_activity_active
            }
            diagnostics.record("activity_recognition", mapOf("state" to context.resources.getResourceEntryName(_status.value)))
        } catch (e: SecurityException) {
            _status.value = R.string.footprints_activity_permission
            diagnostics.record("activity_recognition", mapOf("state" to "permission_denied", "exception" to e.javaClass.simpleName))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            _status.value = R.string.footprints_activity_failed
            diagnostics.record("activity_recognition", mapOf("state" to "failed", "exception" to e.javaClass.simpleName))
        }
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitions.enabled(context) || !ActivityTransitions.permitted(context)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val pending = goAsync()
        val container = (context.applicationContext as MomentsApplication).container
        container.scope.launch {
            try {
                // Keep the broadcast alive until the recorder has processed the wake-up, within its deadline.
                withTimeoutOrNull(8_000) {
                    result.transitionEvents.sortedBy { it.elapsedRealTimeNanos }.forEach { event ->
                        if (SystemClock.elapsedRealtimeNanos() - event.elapsedRealTimeNanos !in 0..120_000_000_000L) return@forEach
                        val moving = event.activityType != DetectedActivity.STILL && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER ||
                            event.activityType == DetectedActivity.STILL && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
                        RecordingService.activityTransition(event.elapsedRealTimeNanos, moving, event.activityType)
                    }
                }
            } finally { pending.finish() }
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Activity recognition task canceled"))
    }
}
