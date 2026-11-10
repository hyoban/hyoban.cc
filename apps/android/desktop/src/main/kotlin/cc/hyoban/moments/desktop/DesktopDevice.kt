package cc.hyoban.moments.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.trips.PlaybackController
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/**
 * The Mac's way with what the pages ask of their device. The maps, a Moment's menu, sharing and
 * editing a place are still to come here: where a map would be there is its space, left blank, and
 * what would open closes again at once.
 */
object DesktopDevice : Device {
    @Composable
    private fun MapSpace(modifier: Modifier) = Box(modifier.background(tokens.fill))

    @Composable
    override fun TripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier) = MapSpace(modifier)

    @Composable
    override fun PlaceMap(place: Place, modifier: Modifier, topInset: Dp) = MapSpace(modifier)

    @Composable
    override fun DayTrackMap(stretches: List<List<Pair<Double, Double>>>, marks: List<DayMark>, modifier: Modifier, topInset: Dp) = MapSpace(modifier)

    @Composable
    override fun PlaybackMap(controller: PlaybackController, clear: List<Dp>, modifier: Modifier, credit: IntArray?) = MapSpace(modifier)

    @Composable
    override fun MomentMenu(moment: MomentRecord, urls: MediaUrls, at: DpOffset?, onDismiss: () -> Unit) {
        if (at != null) LaunchedEffect(at) { onDismiss() }
    }

    @Composable
    override fun ShareMoment(moment: MomentRecord, urls: MediaUrls, place: String?, onDismiss: () -> Unit) {
        LaunchedEffect(moment.uid) { onDismiss() }
    }

    @Composable
    override fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit) {
        LaunchedEffect(id) { onDismiss() }
    }

    @Composable
    override fun PendingChangesRow(container: AppContainer, onClick: () -> Unit, modifier: Modifier) {
    }

    @Composable
    override fun TimelineReady(ready: Boolean) {
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
