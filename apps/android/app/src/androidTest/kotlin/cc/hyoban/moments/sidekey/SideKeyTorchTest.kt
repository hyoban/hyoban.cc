package cc.hyoban.moments.sidekey

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SideKeyTorchTest {
    @Test
    fun toggleFollowsSystemChangesAndRestoresOriginalState() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = manager.cameraIdList.first { id ->
            val info = manager.getCameraCharacteristics(id)
            info[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                info[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
        }
        val state = MutableStateFlow<Boolean?>(null)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == camera) state.value = enabled
            }
        }
        manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        var original: Boolean? = null
        try {
            val before = withTimeout(3000) { state.filterNotNull().first() }
            original = before
            val torch = SideKeyTorch(context)
            assertEquals(!before, torch.toggle())
            withTimeout(3000) { state.first { it == !before } }
            // Simulate a change from Quick Settings while our torch callback is unregistered.
            manager.setTorchMode(camera, before)
            withTimeout(3000) { state.first { it == before } }
            assertEquals(!before, torch.toggle())
            withTimeout(3000) { state.first { it == !before } }
        } finally {
            try {
                original?.let { enabled ->
                    manager.setTorchMode(camera, enabled)
                    withTimeout(3000) { state.first { it == enabled } }
                }
            } finally {
                manager.unregisterTorchCallback(callback)
            }
        }
    }
}
