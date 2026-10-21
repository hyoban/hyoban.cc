package cc.hyoban.moments.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One service executes one explicitly requested adapter flow at a time. */
open class ShareAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: AssistSession? = null
    private var nextActionAt = 0L
    private val tick = Runnable { inspect() }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        // State only: no destination, caption, or third-party screen contents in diagnostics.
        writer.println("assist=${assistState.value}, stage=${session?.request?.flow?.stage ?: "idle"}")
    }

    override fun onServiceConnected() {
        instance = this
        listenTo(packageName)
        connectedState.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (session != null && !handler.hasCallbacks(tick)) handler.postDelayed(tick, POLL_MS)
    }

    override fun onInterrupt() = stop(AssistStop.CANCELLED)

    override fun onDestroy() {
        stop(AssistStop.CANCELLED)
        if (instance === this) {
            instance = null
            connectedState.value = false
        }
        super.onDestroy()
    }

    private fun listenTo(targetPackage: String) {
        serviceInfo = serviceInfo.apply { packageNames = arrayOf(targetPackage) }
    }

    private fun begin(request: AssistRequest) {
        check(session == null) { "Another accessibility task is already running" }
        handler.removeCallbacks(tick)
        val now = SystemClock.elapsedRealtime()
        listenTo(request.adapter.packageName)
        session = AssistSession(request, now)
        nextActionAt = now + 500
        assistState.value = AssistState(request.adapter.id, AssistStatus.Running)
        handler.postDelayed(tick, POLL_MS)
    }

    private fun inspect() {
        val current = session ?: return
        val now = SystemClock.elapsedRealtime()
        var root: AccessibilityNodeInfo? = null
        val raw = mutableListOf<AccessibilityNodeInfo>()
        try {
            root = rootInActiveWindow
            val windowPackage = root?.packageName?.toString()
            val snapshot = if (root != null && windowPackage == current.packageName && now >= nextActionAt) {
                mutableListOf<AssistNode>().also { collect(root, current.packageName, false, raw, it) }
            } else null
            when (val action = current.next(windowPackage, snapshot, now)) {
                AssistAction.Wait -> Unit
                AssistAction.Ready -> finish(current)
                is AssistAction.Stop -> finish(current)
                is AssistAction.SetText -> act(raw, action.node, current) { setText(it, action.text) }
                is AssistAction.Click -> act(raw, action.node, current) { click(it, action, current) }
            }
        } catch (_: Exception) {
            stop(AssistStop.CHANGED)
        } finally {
            @Suppress("DEPRECATION")
            raw.filter { it !== root }.forEach { it.recycle() }
            @Suppress("DEPRECATION")
            root?.recycle()
            if (session != null && !handler.hasCallbacks(tick)) handler.postDelayed(tick, POLL_MS)
        }
    }

    private fun act(nodes: List<AccessibilityNodeInfo>, index: Int, current: AssistSession, action: (AccessibilityNodeInfo) -> Boolean) {
        val node = nodes.getOrNull(index)
        if (node == null || !node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
            node.packageName?.toString() != current.packageName || !action(node)) {
            stop(AssistStop.CHANGED)
        } else {
            nextActionAt = SystemClock.elapsedRealtime() + 650
        }
    }

    private fun click(node: AccessibilityNodeInfo, action: AssistAction.Click, current: AssistSession): Boolean {
        if (action.expectedText != null && node.text?.toString() != action.expectedText) return false
        if (!action.gestureFallback ||
            (node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Some apps expose ACTION_CLICK on non-clickable rows. Only adapters that
        // verified a specific target may opt into tapping its freshly read bounds.
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (session === current) stop(AssistStop.CHANGED)
            }
        }, handler)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean = node.isEditable &&
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

    private fun collect(node: AccessibilityNodeInfo, targetPackage: String, inList: Boolean, raw: MutableList<AccessibilityNodeInfo>, result: MutableList<AssistNode>) {
        // Bound traversal even if a third-party view exposes a malformed/very large tree.
        if (raw.size >= 500) {
            @Suppress("DEPRECATION")
            node.recycle()
            return
        }
        val id = raw.size
        raw.add(node)
        val children = (0 until node.childCount.coerceAtMost(100)).mapNotNull(node::getChild)
        val inRecycler = inList || node.className?.toString()?.contains("RecyclerView") == true
        if (node.isVisibleToUser && node.packageName?.toString() == targetPackage) {
            result.add(AssistNode(
                id = id,
                text = node.text?.toString().orEmpty(),
                description = node.contentDescription?.toString().orEmpty(),
                hint = node.hintText?.toString().orEmpty(),
                editable = node.isEditable,
                enabled = node.isEnabled,
                inList = inList,
                hasToggle = children.any { it.isCheckable },
                checked = node.isChecked || children.any { it.isChecked },
                viewId = node.viewIdResourceName.orEmpty(),
            ))
        }
        children.forEach { collect(it, targetPackage, inRecycler, raw, result) }
    }

    private fun stop(reason: AssistStop) {
        val current = session ?: return
        current.stop(reason)
        finish(current)
    }

    private fun finish(current: AssistSession) {
        session = null
        handler.removeCallbacks(tick)
        val adapter = current.request.adapter
        assistState.value = AssistState(adapter.id, current.status)
        listenTo(packageName)
        val message = if (current.status == AssistStatus.Ready) adapter.readyMessage else adapter.stoppedMessage
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val POLL_MS = 350L
        private var instance: ShareAccessibilityService? = null
        private val connectedState = MutableStateFlow(false)
        val connected = connectedState.asStateFlow()
        private val assistState = MutableStateFlow(AssistState())
        internal val state = assistState.asStateFlow()

        /** Called on the main thread immediately before the prepared media intent is launched. */
        internal fun start(request: AssistRequest) {
            val service = checkNotNull(instance) { "Share accessibility service is not connected" }
            service.begin(request)
        }

        internal fun cancel(adapterId: String) {
            if (assistState.value.adapterId == adapterId) instance?.stop(AssistStop.CANCELLED)
        }

        internal fun resetStatus(adapterId: String) {
            if (assistState.value.adapterId == adapterId && assistState.value.status != AssistStatus.Running) {
                assistState.value = AssistState()
            }
        }
    }
}
