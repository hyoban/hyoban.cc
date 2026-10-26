package cc.hyoban.moments.footprints

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import cc.hyoban.moments.MomentsApplication
import cc.hyoban.moments.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.StringWriter
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Emulator-only: exercise real service delivery and persisted state without altering a user's phone. */
class RecordingRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MomentsApplication
    private val store get() = app.container.footprints
    private var original: RecordingState? = null
    private var activityEnabled = false

    @Before fun prepare() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assumeTrue(store.state().session == null)
        original = store.state()
        activityEnabled = ActivityTransitions.enabled(app)
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) +
            (if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()) +
            (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
        }
        // Stand in for the real Activity Recognition broadcast's background-start exemption.
        shell("cmd deviceidle tempwhitelist -d 60000 ${app.packageName}")
        stopService()
        app.getSharedPreferences("footprint-activity", 0).edit().putBoolean("enabled", true).commit()
        store.command("daily_on")
    }

    @After fun restore() {
        val state = original ?: return
        stopService()
        store.command(if (state.daily) "daily_on" else "daily_off")
        store.command(if (state.paused) "pause" else "resume")
        app.getSharedPreferences("footprint-activity", 0).edit().putBoolean("enabled", activityEnabled).commit()
        runBlocking(Dispatchers.Main) { ActivityTransitions.sync(app) }
        shell("cmd deviceidle tempwhitelist -r ${app.packageName}")
    }

    @Test fun movementRecreatesMissingServiceWithoutChangingSelection() {
        assumeTrue(ActivityTransitions.available(app))
        val state = store.state()
        runBlocking(Dispatchers.Main) {
            RecordingService.activityTransition(app, ActivityMotion(SystemClock.elapsedRealtimeNanos(), true, 7))
        }
        waitUntil { running() && RecordingService.status.value in listOf(
            R.string.footprints_waiting, R.string.footprints_recording, R.string.footprints_resting) }
        assertEquals(state, store.state())
        stopService()
        runBlocking(Dispatchers.Main) { ActivityTransitions.sync(app) }
        assertEquals(R.string.footprints_activity_active, ActivityTransitions.status.value)
        runBlocking(Dispatchers.Main) { RecordingService.control(app, "pause") }
        runBlocking(Dispatchers.Main) { ActivityTransitions.sync(app) }
        assertEquals(R.string.footprints_activity_ready, ActivityTransitions.status.value)
    }

    @Test fun movementDoesNotRestartAManuallyPausedRecorder() {
        store.command("pause")
        runBlocking(Dispatchers.Main) {
            RecordingService.activityTransition(app, ActivityMotion(SystemClock.elapsedRealtimeNanos(), true, 7))
        }
        SystemClock.sleep(500)
        assertFalse(running())
        assertTrue(store.state().paused)
    }

    @Test fun pauseOvertakingARecoveryIntentPreventsLocationRequests() {
        val intent = Intent(app, RecordingService::class.java).setAction("cc.hyoban.moments.ACTIVITY_WAKE")
            .putExtra("motion_elapsed_nanos", SystemClock.elapsedRealtimeNanos())
        store.command("pause")
        app.startForegroundService(intent)
        SystemClock.sleep(1_000)
        waitUntil { !running() }
        assertTrue(store.state().paused)
    }

    @Suppress("DEPRECATION")
    @Test fun freshInaccurateDeliveryDoesNotBackOffBatching() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val manager = app.getSystemService(LocationManager::class.java)
        val start = System.currentTimeMillis()
        shell("appops set ${app.packageName} android:mock_location allow")
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false,
                true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            app.getSharedPreferences("footprint-activity", 0).edit().putBoolean("enabled", false).commit()
            runBlocking(Dispatchers.Main) { RecordingService.control(app) }
            waitUntil { RecordingService.status.value == R.string.footprints_waiting }
            fun fix(accuracy: Float) = manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 37.422; longitude = -122.084; this.accuracy = accuracy
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            fix(5f)
            waitUntil { eventsSince(start).any { it["batch_delay_ms"]?.jsonPrimitive?.longOrNull == 60_000L } }
            // The old implementation downgraded any fully rejected batch after this delivery window.
            SystemClock.sleep(LocationDeliveryPolicy.BATCH_DELAY_MS + 1_000)
            fix(250f)
            waitUntil { eventsSince(start).any { it["event"]?.jsonPrimitive?.content == "location_delivery_quality" } }
            assertFalse(eventsSince(start).any { it["event"]?.jsonPrimitive?.content == "batching_backoff" })
        } finally {
            stopService()
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set ${app.packageName} android:mock_location default")
        }
    }

    private fun eventsSince(start: Long): List<JsonObject> {
        val writer = StringWriter()
        runBlocking { app.container.footprintDiagnostics.export(writer) }
        return writer.toString().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .filter { (it["time_ms"]?.jsonPrimitive?.long ?: 0) >= start }.toList()
    }

    private fun stopService() {
        app.stopService(Intent(app, RecordingService::class.java))
        waitUntil { !running() }
        instrumentation.waitForIdleSync()
    }

    @Suppress("DEPRECATION")
    private fun running() = app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == RecordingService::class.java.name }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Service state did not settle", predicate())
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
