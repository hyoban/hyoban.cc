package cc.hyoban.moments.ui.picker

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cc.hyoban.moments.data.media.Share
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.viewer.VideoControls
import cc.hyoban.moments.ui.viewer.VideoPlayback
import cc.hyoban.moments.ui.viewer.VideoState
import cc.hyoban.moments.ui.viewer.VideoWaiting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 相册 on the phone: its gallery and the paired camera before the NAS, its videos played by Media3, and the system's share sheet. */
class PhonePicker(private val context: Context) : PickerDevice {
    @Composable
    override fun sources(deletion: Deletion, onFallback: () -> Unit, onRefused: () -> Unit): List<PhotoSource> {
        val gallery = rememberGallery(deletion, onFallback)
        val camera = rememberCamera(deletion, onRefused)
        return listOf(gallery, camera)
    }

    /** The gallery's photos by where they were taken, read once each and kept (09 地图的照片). */
    @Composable
    override fun places(deletion: Deletion): List<Places> {
        val gallery = rememberGallery(deletion, onFallback = {})
        val locations = (context.applicationContext as cc.hyoban.moments.MomentsApplication).container.galleryLocations
        return listOf(remember(gallery) { GalleryPlaces(gallery, locations) })
    }

    override val share: (List<Uri>) -> Unit = { shareMedia(context, it) }

    override val saveWords = Res.string.album_save
}

/**
 * Hands [uris] to the system's share sheet as a Moment's photos go ([Share.uris]): each copied
 * without where it was taken, a motion photo's clip kept inside it, a video whole.
 */
internal fun shareMedia(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val app = context.applicationContext
    (app as cc.hyoban.moments.MomentsApplication).container.scope.launch {
        try {
            Share.uris(app, uris)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cc.hyoban.moments.logWarning("Moments", "album share failed", e)
            Toast.makeText(app, textOf(Res.string.error_share), Toast.LENGTH_SHORT).show()
        }
    }
}
