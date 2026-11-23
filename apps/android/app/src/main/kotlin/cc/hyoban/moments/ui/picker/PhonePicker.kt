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

/** 相册 on the phone: the system's share sheet, and its gallery as where photos are kept. */
class PhonePicker(private val context: Context) : PickerDevice {
    override val share: (List<Uri>) -> Unit = { shareMedia(context, it) }

    override val saveWords = Res.string.album_save
}

/** The gallery, one for 相册, 选照片 and 地图's photos alike, listed first. */
object GalleryPhotoSources : PhotoSources {
    override val order = 0

    @Volatile private var made: GallerySource? = null

    private fun gallery(container: cc.hyoban.moments.AppContainer): GallerySource =
        made ?: synchronized(this) { made ?: GallerySource(cc.hyoban.moments.AndroidApp.context, container.settings).also { made = it } }

    @Composable
    override fun sources(container: cc.hyoban.moments.AppContainer, deletion: Deletion, onFallback: () -> Unit, onRefused: () -> Unit): List<PhotoSource> =
        listOf(rememberGallery(gallery(container), deletion, onFallback))

    /** The gallery's photos by where they were taken, read once each and kept (09 地图的照片). */
    @Composable
    override fun places(container: cc.hyoban.moments.AppContainer, deletion: Deletion, listed: List<PhotoSource>?): List<Places> {
        val gallery = rememberGallery(gallery(container), deletion, onFallback = {})
        val locations = (container as cc.hyoban.moments.PhoneContainer).galleryLocations
        return listOf(remember(gallery) { GalleryPlaces(gallery, locations) })
    }
}

/** The paired camera's photos, after the gallery and the published ones. */
object CameraPhotoSources : PhotoSources {
    override val order = 10

    @Composable
    override fun sources(container: cc.hyoban.moments.AppContainer, deletion: Deletion, onFallback: () -> Unit, onRefused: () -> Unit): List<PhotoSource> =
        listOf(rememberCamera(deletion, onRefused))
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
