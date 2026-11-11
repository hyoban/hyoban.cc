package cc.hyoban.moments.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.data.local.toPhotoRef
import cc.hyoban.moments.ui.LocationAsk
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.ui.DayMark
import cc.hyoban.moments.ui.Device
import cc.hyoban.moments.ui.trips.PlaybackController
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/**
 * The Mac's way with what the pages ask of their device: the shared MapLibre maps, drawn with Metal
 * into the window's host (`Main.kt`). A Moment's menu and sharing are still to come here: what
 * would open closes again at once.
 */
object DesktopDevice : Device {
    @Composable
    override fun TripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier) =
        cc.hyoban.moments.ui.map.SharedTripMap(stops, segments, modifier)

    @Composable
    override fun PlaceMap(place: Place, modifier: Modifier, topInset: Dp) = cc.hyoban.moments.ui.map.SharedPlaceMap(place, modifier, topInset)

    @Composable
    override fun DayTrackMap(stretches: List<List<Pair<Double, Double>>>, marks: List<DayMark>, modifier: Modifier, topInset: Dp) =
        cc.hyoban.moments.ui.map.SharedDayTrackMap(stretches, marks, modifier, topInset)

    @Composable
    override fun PlaybackMap(controller: PlaybackController, clear: List<Dp>, modifier: Modifier, credit: IntArray?) =
        cc.hyoban.moments.ui.map.SharedPlaybackMap(controller, clear, modifier, credit)

    @Composable
    override fun PickerMap(
        places: Map<String, Place>,
        chosen: String?,
        target: cc.hyoban.moments.ui.map.MapTarget?,
        onCenter: (latitude: Double, longitude: Double, byUser: Boolean) -> Unit,
        onPlace: (String) -> Unit,
        modifier: Modifier,
        onDrag: (Boolean) -> Unit,
    ) = cc.hyoban.moments.ui.map.SharedPickerMap(places, chosen, target, onCenter, onPlace, modifier, onDrag)

    // The system's own file dialog, for pictures; what was chosen comes back at once.
    @Composable
    override fun PhotoPicker(container: AppContainer, limit: Int, onDismiss: () -> Unit, onPicked: (List<PhotoRef>) -> Unit) {
        LaunchedEffect(Unit) {
            val files = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD).apply {
                    isMultipleMode = true
                    setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in PICTURES }
                    isVisible = true
                }
                dialog.files.orEmpty().toList()
            }
            if (files.isEmpty()) onDismiss() else onPicked(files.take(limit).map { it.toPhotoRef() })
        }
    }

    @Composable
    override fun rememberLocationAsk(onResult: (Boolean) -> Unit): LocationAsk = remember { LocationAsk({ true }, { onResult(false) }) }

    // A computer isn't asked where it is: a place is searched for, or taken from a photo.
    override val hasLocation: Boolean = false

    override fun canLocate(): Boolean = false

    override suspend fun currentFix(): Fix? = null

    override fun openAppSettings() {
    }

    override val hasCamera: Boolean = false

    override fun openCamera(): Boolean = false

    override fun toast(text: String) {
    }

    @Composable
    override fun MomentMenu(moment: MomentRecord, urls: MediaUrls, at: DpOffset?, onDismiss: () -> Unit) {
        if (at != null) LaunchedEffect(at) { onDismiss() }
    }

    @Composable
    override fun ShareMoment(moment: MomentRecord, urls: MediaUrls, place: String?, onDismiss: () -> Unit) {
        LaunchedEffect(moment.uid) { onDismiss() }
    }

    @Composable
    override fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit) = cc.hyoban.moments.ui.compose.EditPlace(container, id, onDismiss)

    @Composable
    override fun StorageSettings(container: AppContainer) {
    }

    @Composable
    override fun UpdateSettings(container: AppContainer) {
    }

    @Composable
    override fun beforePublishing(publish: () -> Unit): () -> Unit = publish

    @Composable
    override fun TimelineReady(ready: Boolean) {
    }

    override suspend fun moduleChanged(module: cc.hyoban.moments.data.spec.Module) {
    }

    override fun copyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    // Apple's Maps, by the link it takes: the way to the point, named.
    override fun navigateTo(place: Place): Boolean = runCatching {
        val name = java.net.URLEncoder.encode(place.name, "UTF-8")
        Desktop.getDesktop().browse(URI("https://maps.apple.com/?daddr=${place.latitude},${place.longitude}&q=$name"))
    }.isSuccess
}

/** The pictures the file dialog offers, by their names' endings. */
private val PICTURES = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
