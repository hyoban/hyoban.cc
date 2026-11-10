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

/** A point on the map, in degrees. */
data class MapPoint(val latitude: Double, val longitude: Double)

/** Something of a day marked on its track's map, by what it is: a Moment, a check-in or a bill. */
data class DayMark(val at: MapPoint, val kind: Kind, val icon: ImageVector) {
    enum class Kind { MOMENT, CHECKIN, BILL }
}

/**
 * What the pages ask of the device they run on, which the phone and the Mac each do their own
 * way: the maps, sharing and the menus that lead to it, and the pages not yet the same on both.
 * The app's shell provides it; a page calls it where it would draw the thing itself.
 */
interface Device {
    /** A trip's stops and its route between them. */
    @Composable fun TripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier)

    /** One place as a dot on its streets, clear of [topInset] at its top. */
    @Composable fun PlaceMap(place: Place, modifier: Modifier, topInset: Dp)

    /** A day's footprints, each of [stretches] a line, with what happened along them marked. */
    @Composable fun DayTrackMap(stretches: List<List<Pair<Double, Double>>>, marks: List<DayMark>, modifier: Modifier, topInset: Dp)

    /** The map a trip plays back on, keeping what it follows clear of [clear] at its edges. */
    @Composable fun PlaybackMap(controller: PlaybackController, clear: List<Dp>, modifier: Modifier, credit: IntArray?)

    /** A Moment's menu, opened [at] where it was pressed; nothing while [at] is null. */
    @Composable fun MomentMenu(moment: MomentRecord, urls: MediaUrls, at: DpOffset?, onDismiss: () -> Unit)

    /** Choosing where a Moment is shared to, then sharing it there, [place] its place's name. */
    @Composable fun ShareMoment(moment: MomentRecord, urls: MediaUrls, place: String?, onDismiss: () -> Unit)

    /** A place's name, point and kind, to change. */
    @Composable fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit)

    /** The changes waiting to publish, as a row at the head of the timeline. */
    @Composable fun PendingChangesRow(container: AppContainer, onClick: () -> Unit, modifier: Modifier)

    /** Told once the timeline has what it first shows, for what the device holds back until then. */
    @Composable fun TimelineReady(ready: Boolean)

    /** Puts [text] on the clipboard. */
    fun copyText(text: String)

    /** Opens the way to [place] in the device's maps; false when nothing can. */
    fun navigateTo(place: Place): Boolean
}

val LocalDevice = staticCompositionLocalOf<Device> { error("No device provided") }
