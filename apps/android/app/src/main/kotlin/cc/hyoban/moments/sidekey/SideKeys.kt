package cc.hyoban.moments.sidekey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.ShareAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

internal data class SideKeyConfig(
    val enabled: Boolean = false,
    val actions: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val doubleMs: Int = 300,
    val longMs: Int = 600,
)

internal enum class SideKeyPhase(val label: Int) {
    OFF(R.string.side_key_off), CONNECTING(R.string.side_key_connecting),
    RUNNING(R.string.side_key_running), NEEDS_ACCESSIBILITY(R.string.side_key_needs_accessibility),
    NEEDS_SHIZUKU(R.string.side_key_needs_shizuku), NEEDS_PERMISSION(R.string.side_key_needs_permission),
    ERROR(R.string.side_key_error),
}

internal data class SideKeyState(
    val phase: SideKeyPhase = SideKeyPhase.OFF,
    val lastGesture: String? = null,
    val lastTime: Long? = null,
    val message: String? = null,
    val restorePending: Boolean = false,
)

/** A single listener hosted by the accessibility service; preferences stay on this phone. */
internal object SideKeys {
    val gestures = listOf("single", "double", "long")
    private const val ORIGINAL = "original"
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val configState = MutableStateFlow(SideKeyConfig())
    val config = configState.asStateFlow()
    private val runtime = MutableStateFlow(SideKeyState())
    val state = runtime.asStateFlow()
    private var accessible = false
    private var remote: IKeyBridge? = null
    private var connection: ServiceConnection? = null
    private var generation = 0
    private var failed = false
    private val args = Shizuku.UserServiceArgs(ComponentName("cc.hyoban.moments", KeyBridge::class.java.name))
        .daemon(false).processNameSuffix("sidekey").tag("side-key").version(1)
    private val detector = GestureDetector(::execute)
    private val tick = Runnable {
        detector.tick(SystemClock.uptimeMillis())
        scheduleTick()
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != ORIGINAL) scope.launch {
            configState.value = readConfig()
            detector.doubleMs = configState.value.doubleMs.toLong()
            detector.longMs = configState.value.longMs.toLong()
            reconcile()
        }
    }

    fun initialize(base: Context) {
        if (::context.isInitialized) return
        context = base.applicationContext
        prefs = context.getSharedPreferences("side-key", Context.MODE_PRIVATE)
        configState.value = readConfig()
        detector.doubleMs = configState.value.doubleMs.toLong()
        detector.longMs = configState.value.longMs.toLong()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        Shizuku.addBinderReceivedListenerSticky { scope.launch { reconcile() } }
        Shizuku.addBinderDeadListener { scope.launch { reconcile() } }
        scope.launch { reconcile() }
    }

    private fun readConfig() = SideKeyConfig(
        prefs.getBoolean("enabled", false),
        gestures.associateWith { prefs.getString(it, "none") ?: "none" },
        gestures.associateWith { prefs.getString("${it}Label", "") ?: "" },
        prefs.getInt("doubleMs", 300).coerceIn(150, 600),
        prefs.getInt("longMs", 600).coerceIn(400, 2000),
    )

    fun refresh() { scope.launch { reconcile() } }

    fun accessibilityChanged(connected: Boolean) {
        accessible = connected
        scope.launch { reconcile() }
    }

    fun setEnabled(enabled: Boolean) {
        failed = false
        runtime.value = runtime.value.copy(message = null)
        prefs.edit().putBoolean("enabled", enabled).apply()
        scope.launch { configState.value = readConfig(); reconcile() }
    }

    fun setAction(gesture: String, action: String, label: String = "") {
        require(gesture in gestures && SideKeyActions.valid(action))
        prefs.edit().putString(gesture, action).putString("${gesture}Label", label).apply()
    }

    fun setTiming(doubleMs: Int, longMs: Int) {
        require(doubleMs in 150..600 && longMs in 400..2000)
        resetGesture()
        prefs.edit().putInt("doubleMs", doubleMs).putInt("longMs", longMs).apply()
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun shouldRun() = configState.value.enabled && accessible && shizukuReady()

    private fun desiredPhase(): SideKeyPhase = when {
        !configState.value.enabled -> if (failed) SideKeyPhase.ERROR else SideKeyPhase.OFF
        !accessible -> SideKeyPhase.NEEDS_ACCESSIBILITY
        !Shizuku.pingBinder() -> SideKeyPhase.NEEDS_SHIZUKU
        !shizukuReady() -> SideKeyPhase.NEEDS_PERMISSION
        else -> SideKeyPhase.CONNECTING
    }

    private fun showPhase(phase: SideKeyPhase) {
        runtime.value = runtime.value.copy(phase = phase, restorePending = prefs.contains(ORIGINAL) && phase != SideKeyPhase.RUNNING)
    }

    /** Serialize setup and restoration so a quick disable cannot leave a reader running. */
    private suspend fun reconcile() = mutex.withLock {
        if (!shouldRun()) {
            disconnect()
            showPhase(desiredPhase())
            if (prefs.contains(ORIGINAL) && shizukuReady()) bind()
        } else if (connection == null) {
            showPhase(SideKeyPhase.CONNECTING)
            bind()
        }
    }

    private fun bind() {
        val token = ++generation
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                scope.launch {
                    mutex.withLock {
                        if (token != generation) return@withLock
                        val bridge = IKeyBridge.Stub.asInterface(binder)
                        remote = bridge
                        try {
                            withContext(Dispatchers.IO) {
                                if (shouldRun()) {
                                    if (!prefs.contains(ORIGINAL)) {
                                        val original = bridge.getSystemAction()
                                        check(prefs.edit().putString(ORIGINAL, original).commit())
                                    }
                                    bridge.setSystemAction("nothing")
                                    bridge.start(object : IKeyCallback.Stub() {
                                        override fun event(down: Boolean, time: Long) {
                                            scope.launch {
                                                if (token == generation && shouldRun()) {
                                                    detector.event(down, time)
                                                    scheduleTick()
                                                }
                                            }
                                        }
                                        override fun status(code: Int) {
                                            scope.launch {
                                                if (token == generation) {
                                                    if (code == 0) showPhase(SideKeyPhase.RUNNING) else fail()
                                                }
                                            }
                                        }
                                    })
                                }
                            }
                            if (!shouldRun()) {
                                disconnect()
                                showPhase(desiredPhase())
                            }
                        } catch (_: Exception) {
                            fail()
                            disconnect()
                            showPhase(SideKeyPhase.ERROR)
                        }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                scope.launch {
                    mutex.withLock {
                        if (token != generation) return@withLock
                        remote = null
                        connection = null
                        generation++
                        resetGesture()
                        if (shizukuReady()) fail()
                        showPhase(desiredPhase())
                    }
                }
            }
        }
        connection = candidate
        try { Shizuku.bindUserService(args, candidate) }
        catch (_: Exception) { connection = null; fail(); showPhase(SideKeyPhase.ERROR) }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val next = detector.nextDeadline()
        if (next != Long.MAX_VALUE) handler.postAtTime(tick, next)
    }

    private fun resetGesture() { handler.removeCallbacks(tick); detector.reset() }

    private suspend fun disconnect() {
        resetGesture()
        generation++
        val oldConnection = connection
        val bridge = remote
        connection = null
        remote = null
        try {
            withContext(Dispatchers.IO) {
                bridge?.stop()
                val original = prefs.getString(ORIGINAL, null)
                if (bridge != null && original != null) {
                    bridge.setSystemAction(original)
                    check(prefs.edit().remove(ORIGINAL).commit())
                }
            }
        } catch (_: Exception) {
            runtime.value = runtime.value.copy(message = context.getString(R.string.side_key_restore_pending))
        } finally {
            if (oldConnection != null) runCatching { Shizuku.unbindUserService(args, oldConnection, true) }
        }
    }

    private fun fail() {
        failed = true
        runtime.value = runtime.value.copy(message = context.getString(R.string.side_key_error))
        prefs.edit().putBoolean("enabled", false).apply()
        scope.launch { configState.value = readConfig(); reconcile() }
    }

    private fun report(message: String, toast: Boolean = false) {
        runtime.value = runtime.value.copy(message = message)
        if (toast) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun execute(gesture: String) {
        runtime.value = runtime.value.copy(lastGesture = gesture, lastTime = System.currentTimeMillis(), message = null)
        val action = configState.value.actions[gesture] ?: "none"
        try {
            when {
                action == "none" -> Unit
                action == "ledger:capture" -> {
                    val result = ShareAccessibilityService.capturePayment()
                    report(context.getString(result.message), result != cc.hyoban.moments.accessibility.PaymentCapture.OPENED)
                }
                action.startsWith("media:") -> {
                    val bridge = checkNotNull(remote)
                    scope.launch {
                        try { withContext(Dispatchers.IO) { bridge.media(action.substringAfter(':').toInt()) } }
                        catch (_: Exception) { report(context.getString(R.string.side_key_action_failed), true) }
                    }
                }
                action.startsWith("nav:") -> check(ShareAccessibilityService.sideKeyNavigation(action.substringAfter(':').toInt()))
                action.startsWith("app:") -> {
                    val intent = context.packageManager.getLaunchIntentForPackage(action.substringAfter(':'))
                    check(intent != null && ShareAccessibilityService.sideKeyOpen(intent))
                }
                action.startsWith("uri:") && SideKeyActions.validLink(action.substringAfter(':')) ->
                    check(ShareAccessibilityService.sideKeyOpen(Intent(Intent.ACTION_VIEW, Uri.parse(action.substringAfter(':')))))
                else -> report(context.getString(R.string.side_key_action_failed), true)
            }
        } catch (_: Exception) { report(context.getString(R.string.side_key_action_failed), true) }
    }
}
