package cc.hyoban.moments.camera

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The camera code's log tag, the app's. */
internal const val TAG = "Moments"

/** The camera paired with this phone: which it is, the system's association of it, and its switch. */
data class PairedCamera(
    val address: String,
    val name: String,
    val association: Int,
    /** Whether the phone sends the camera its location while it is near. */
    val sendLocation: Boolean = true,
)

/** Where the camera stands now, for 相机 and its notification. */
data class CameraStatus(
    val near: Boolean = false,
    val sending: Boolean = false,
    /** When a location last reached the camera, epoch millis. */
    val lastSent: Long? = null,
    /** Why it isn't taking locations, a string resource, or null. */
    val problem: Int? = null,
)

/**
 * The paired camera, kept beside the footprints' connection settings in `noBackupFilesDir`
 * (it names a device of the owner's), and the phone's own id it introduces itself to the camera by.
 */
object CameraStore {
    private val _camera = MutableStateFlow<PairedCamera?>(null)
    val camera: StateFlow<PairedCamera?> = _camera
    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status
    private var loaded = false
    private var guid: UUID? = null

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "camera.json"))

    @Synchronized fun load(context: Context): PairedCamera? {
        if (loaded) return _camera.value
        val file = file(context)
        val json = runCatching { JSONObject(file.readFully().decodeToString()) }.getOrNull()
        guid = json?.optString("guid")?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        _camera.value = json?.optJSONObject("camera")?.let {
            PairedCamera(it.getString("address"), it.optString("name"), it.getInt("association"), it.optBoolean("sendLocation", true))
        }
        _status.value = _status.value.copy(lastSent = json?.optLong("lastSent")?.takeIf { it > 0 })
        loaded = true
        return _camera.value
    }

    @Synchronized fun save(context: Context, camera: PairedCamera?) {
        load(context)
        _camera.value = camera
        write(context)
    }

    /** The id the phone gives itself to the camera's PTP/IP, made once and kept. */
    @Synchronized fun guid(context: Context): UUID {
        load(context)
        return guid ?: UUID.randomUUID().also { guid = it; write(context) }
    }

    fun update(change: (CameraStatus) -> CameraStatus) { _status.value = change(_status.value) }

    @Synchronized fun sent(context: Context, at: Long) {
        val before = _status.value.lastSent
        _status.value = _status.value.copy(lastSent = at, sending = true, problem = null)
        // Written now and then, not with every location.
        if (before == null || at - before > 10 * 60_000) write(context)
    }

    private fun write(context: Context) {
        val json = JSONObject()
        guid?.let { json.put("guid", it.toString()) }
        _status.value.lastSent?.let { json.put("lastSent", it) }
        _camera.value?.let {
            json.put("camera", JSONObject().put("address", it.address).put("name", it.name)
                .put("association", it.association).put("sendLocation", it.sendLocation))
        }
        val file = file(context)
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
}
