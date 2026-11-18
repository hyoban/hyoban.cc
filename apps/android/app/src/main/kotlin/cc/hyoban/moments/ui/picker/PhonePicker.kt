package cc.hyoban.moments.ui.picker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.viewer.VideoControls
import cc.hyoban.moments.ui.viewer.VideoPlayback
import cc.hyoban.moments.ui.viewer.VideoState
import cc.hyoban.moments.ui.viewer.VideoWaiting

/** 相册 on the phone: its gallery and the paired camera before the NAS, its videos played by Media3, and the system's share sheet. */
class PhonePicker(private val context: Context) : PickerDevice {
    @Composable
    override fun sources(deletion: Deletion, onFallback: () -> Unit, onRefused: () -> Unit): List<PhotoSource> {
        val gallery = rememberGallery(deletion, onFallback)
        val camera = rememberCamera(deletion, onRefused)
        return listOf(gallery, camera)
    }

    @Composable
    override fun rememberVideos(): PickerVideos = remember { PhoneVideos() }

    override val share: (List<Uri>) -> Unit = { shareMedia(context, it) }

    override val saveWords = Res.string.album_save
}

/** The picker's videos, each its own player's state by its photo's key, so the controls under the viewer work the one shown. */
class PhoneVideos : PickerVideos {
    private val states = mutableMapOf<String, VideoState>()

    /** [key]'s video's state, for a page of the phone's own that plays it itself. */
    fun state(key: String): VideoState = states.getOrPut(key) { VideoState() }

    @Composable
    override fun Play(key: String, url: String, headers: Map<String, String>, active: Boolean, modifier: Modifier): Boolean {
        VideoPlayback(url, active, state(key), modifier, headers = headers, window = false)
        return true
    }

    @Composable override fun Waiting(key: String) = VideoWaiting(state(key))
    @Composable override fun Controls(key: String, modifier: Modifier) = VideoControls(state(key), modifier)
}

/**
 * Hands [uris] to the system's share sheet: the gallery's own, as the media store names them,
 * without asking for the original (which only this app may), each readable by the app chosen.
 */
internal fun shareMedia(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val plain = uris.map { it.buildUpon().clearQuery().build() }
    val types = plain.map { context.contentResolver.getType(it).orEmpty() }
    val type = when {
        types.all { it.startsWith("image/") } -> "image/*"
        types.all { it.startsWith("video/") } -> "video/*"
        else -> "*/*"
    }
    val send = if (plain.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, plain.single())
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(plain))
    send.setType(type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .clipData = ClipData.newRawUri(null, plain.first()).apply { plain.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(send, null))
}

