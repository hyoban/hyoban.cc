package cc.hyoban.moments.camera

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one connection to the paired camera: made when its card in 选照片 asks, then kept while Here
 * is in the foreground, wherever the owner goes in it, so the card keeps being saved and followed.
 * Once no screen of Here is started (not a rotation) it is released and the phone leaves the
 * camera's network; the card asks again when it is next shown.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object CameraConnection {
    var transfer by mutableStateOf<CameraTransfer?>(null)
        private set

    /** Connecting outlives the page that asked. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var started = 0

    /** Connects unless connected or on the way; [again] starts over, as after a failure. */
    fun connect(context: Context, again: Boolean = false) {
        watch(context.applicationContext as Application)
        val current = transfer
        if (current != null && !again && current.state.value !is CameraTransfer.State.Failed) return
        current?.close()
        transfer = CameraTransfer(context.applicationContext).also { scope.launch { it.connect() } }
    }

    fun release() {
        transfer?.close()
        transfer = null
    }

    /** Counts Here's started screens from the first connection on, asked for from one that is. */
    private fun watch(app: Application) {
        if (watching) return
        watching = true
        started = 1
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) release()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
