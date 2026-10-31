package cc.hyoban.moments.sidekey

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

/** Read the system's torch state for each toggle, including changes made by other apps. */
internal class SideKeyTorch(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    suspend fun toggle(): Boolean = mutex.withLock {
        val camera = withContext(Dispatchers.IO) {
            manager.cameraIdList.firstOrNull { id ->
                val info = manager.getCameraCharacteristics(id)
                info[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                    info[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
            }
        } ?: error("No rear camera with a flash unit")
        val current = CompletableDeferred<Boolean>()
        val applied = CompletableDeferred<Boolean>()
        val requested = AtomicReference<Boolean?>(null)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != camera) return
                val target = requested.get()
                if (target == null) current.complete(enabled)
                else if (target == enabled) applied.complete(enabled)
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId != camera) return
                val error = IllegalStateException("Torch unavailable")
                current.completeExceptionally(error)
                applied.completeExceptionally(error)
            }
        }
        manager.registerTorchCallback(callback, handler)
        try {
            val desired = !withTimeout(2000) { current.await() }
            requested.set(desired)
            withContext(Dispatchers.IO) { manager.setTorchMode(camera, desired) }
            withTimeout(2000) { applied.await() }
        } finally {
            manager.unregisterTorchCallback(callback)
        }
    }
}
