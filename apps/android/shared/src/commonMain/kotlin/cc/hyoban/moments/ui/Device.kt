package cc.hyoban.moments.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.ui.trips.PlaybackController
import cc.hyoban.moments.ui.map.AtlasMapStateStore

/** A point on the map, in degrees. */
data class MapPoint(val latitude: Double, val longitude: Double)

/** Something of a day marked on its track's map, by what it is: a Moment, a stop of its own (a check-in) or a record (a bill). */
data class DayMark(val at: MapPoint, val kind: Kind, val icon: ImageVector) {
    enum class Kind { MOMENT, STOP, RECORD }
}

/** Starts asking for where the device is; [asked] says whether it has been asked before. */
class LocationAsk(private val wasAsked: () -> Boolean, private val start: () -> Unit) {
    val asked get() = wasAsked()
    operator fun invoke() = start()
}

/**
 * What the pages ask of the device they run on, which the phone and the Mac each do their own
 * way: the maps, sharing and the menus that lead to it, and the pages not yet the same on both.
 * The app's shell provides it; a page calls it where it would draw the thing itself.
 */
interface Device {
    /** The phone keeps its main atlas alive across tab changes; null until another device needs that host. */
    val atlasMapStateStore: AtlasMapStateStore? get() = null

    /** A trip's stops and its route between them. */
    @Composable fun TripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier)

    /** One place as a dot on its streets, clear of [topInset] at its top. */
    @Composable fun PlaceMap(place: Place, modifier: Modifier, topInset: Dp)

    /** A day's footprints, each of [stretches] a line, with what happened along them marked. */
    @Composable fun DayTrackMap(stretches: List<List<Pair<Double, Double>>>, marks: List<DayMark>, modifier: Modifier, topInset: Dp)

    /** The map a trip plays back on, keeping what it follows clear of [clear] at its edges. */
    @Composable fun PlaybackMap(controller: PlaybackController, clear: List<Dp>, modifier: Modifier, credit: IntArray?)

    /**
     * A map to choose a place on, a pin standing at its middle: the known [places] as dots,
     * [chosen] marked, one tapped told to [onPlace]; where the middle comes to rest told to
     * [onCenter], with whether it was moved there by hand. Each new [target] flies it there.
     */
    @Composable fun PickerMap(
        places: Map<String, Place>,
        chosen: String?,
        target: cc.hyoban.moments.ui.map.MapTarget?,
        onCenter: (latitude: Double, longitude: Double, byUser: Boolean) -> Unit,
        onPlace: (String) -> Unit,
        modifier: Modifier,
        onDrag: (Boolean) -> Unit,
    )

    /** Choosing photos for a Moment, [limit] at most: what was picked told to [onPicked], or [onDismiss] with none. */
    @Composable fun PhotoPicker(container: AppContainer, limit: Int, onDismiss: () -> Unit, onPicked: (List<cc.hyoban.moments.data.local.PhotoRef>) -> Unit)

    /** What 相册 has of the device: sharing and where it keeps photos; its sources are plugins' (`Points.photoSources`). */
    val picker: cc.hyoban.moments.ui.picker.PickerDevice get() = cc.hyoban.moments.ui.picker.NasOnly

    /** Asking for where the device is, as it asks: [onResult] hears whether it may be read. */
    @Composable fun rememberLocationAsk(onResult: (Boolean) -> Unit): LocationAsk

    /** Whether the device can tell where it is at all; a computer isn't asked. */
    val hasLocation: Boolean

    /** Whether where the device is may be read now. */
    fun canLocate(): Boolean

    /** Where the device is, or null when it can't tell. */
    suspend fun currentFix(): cc.hyoban.moments.data.local.Fix?

    /** Opens the app's page in the system's settings, where what it may do is changed. */
    fun openAppSettings()

    /** Whether the device has a camera app of its own to open for a new photo. */
    val hasCamera: Boolean

    /** Opens the device's camera app; false when there is none. */
    fun openCamera(): Boolean

    /**
     * What the camera app has kept since [since] (in milliseconds), oldest first, again as more is
     * kept or one is written again; nothing where the device can't see where it keeps them.
     */
    fun cameraShots(since: Long): kotlinx.coroutines.flow.Flow<List<CameraShot>> = kotlinx.coroutines.flow.emptyFlow()

    /** Says [text] for a moment, over whatever is shown. */
    fun toast(text: String)

    /** Choosing where a Moment is shared to, then sharing it there, [place] its place's name and [trip] its trip's title. */
    @Composable fun ShareMoment(moment: MomentRecord, urls: MediaUrls, place: String?, trip: String?, onDismiss: () -> Unit)

    /**
     * What moves of an item of [type] in a viewer, a live photo's clip or a video; null for a still.
     * A Moment's viewer and 相册's both play live photos by it.
     */
    fun viewerMotion(type: cc.hyoban.moments.data.spec.MediaType): cc.hyoban.moments.ui.viewer.ViewerMotion?

    /** As [viewerMotion], in a viewer that is a dialog of its own ([inDialog]), where a video doesn't go on in a window when the app is left. */
    fun viewerMotion(type: cc.hyoban.moments.data.spec.MediaType, inDialog: Boolean): cc.hyoban.moments.ui.viewer.ViewerMotion? = viewerMotion(type)

    /** Shares the one item [media] shown in the viewer, fetched first when it is the site's, with its Moment's [text] where the device can send words with it. */
    suspend fun shareMedia(media: cc.hyoban.moments.data.spec.MomentMedia, urls: MediaUrls, text: String)

    /** Choosing a file of [types] (media types, or a name's ending): what the returned call opens, its address told to [onPicked]. */
    @Composable fun rememberFilePicker(types: List<String>, onPicked: (String) -> Unit): () -> Unit

    /** A place's name, point and kind, to change. */
    @Composable fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit)

    /** What starts publishing: [publish] itself, after whatever the device asks first (the phone, to show its progress). */
    @Composable fun beforePublishing(publish: () -> Unit): () -> Unit

    /** Told once the timeline has what it first shows, for what the device holds back until then. */
    @Composable fun TimelineReady(ready: Boolean)

    /** Brings the device's background work in line with [module] just turned on or off. */
    suspend fun moduleChanged(module: cc.hyoban.moments.data.spec.Module)

    /** Puts [text] on the clipboard. */
    fun copyText(text: String)

    /** Opens the way to [place] in the device's maps; false when nothing can. */
    fun navigateTo(place: Place): Boolean
}

val LocalDevice = staticCompositionLocalOf<Device> { error("No device provided") }

/** A photo or video the camera app kept, [bytes] its size as now written. */
data class CameraShot(val ref: cc.hyoban.moments.data.local.PhotoRef, val bytes: Long)
