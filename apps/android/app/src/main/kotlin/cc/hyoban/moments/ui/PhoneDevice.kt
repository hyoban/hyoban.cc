package cc.hyoban.moments.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.MainActivity
import cc.hyoban.moments.PhoneContainer
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.ui.moment.ShareMenu
import cc.hyoban.moments.ui.moment.ShareTarget
import cc.hyoban.moments.ui.trips.PlaybackController

/** The phone's own way with what the pages ask of their device: MapLibre's maps, Android's sharing, the pages only it has. */
class PhoneDevice(private val context: Context) : Device {
    @Composable
    override fun TripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier) =
        cc.hyoban.moments.ui.map.TripMap(stops, segments, modifier)

    @Composable
    override fun PlaceMap(place: Place, modifier: Modifier, topInset: Dp) = cc.hyoban.moments.ui.map.PlaceMap(place, modifier, topInset)

    @Composable
    override fun DayTrackMap(stretches: List<List<Pair<Double, Double>>>, marks: List<DayMark>, modifier: Modifier, topInset: Dp) =
        cc.hyoban.moments.ui.map.DayTrackMap(stretches, marks, modifier, topInset)

    @Composable
    override fun PlaybackMap(controller: PlaybackController, clear: List<Dp>, modifier: Modifier, credit: IntArray?) =
        cc.hyoban.moments.ui.map.PlaybackMap(controller, clear, modifier, credit)

    @Composable
    override fun MomentMenu(moment: MomentRecord, urls: MediaUrls, at: DpOffset?, onDismiss: () -> Unit) =
        cc.hyoban.moments.ui.moment.MomentMenu(moment, urls, at, onDismiss)

    // The sheet of where to share stays under the page it opens, as it did when the Moment's page held both.
    @Composable
    override fun ShareMoment(moment: MomentRecord, urls: MediaUrls, place: String?, onDismiss: () -> Unit) {
        var target by remember(moment.uid) { mutableStateOf<ShareTarget?>(null) }
        ShareMenu(onSelect = { target = it }, onDismiss = onDismiss)
        target?.let { cc.hyoban.moments.ui.moment.ShareMoment(moment, urls, it, onDismiss = { target = null }, place = place) }
    }

    @Composable
    override fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit) =
        cc.hyoban.moments.ui.compose.EditPlace(container as PhoneContainer, id, onDismiss)

    @Composable
    override fun PendingChangesRow(container: AppContainer, onClick: () -> Unit, modifier: Modifier) =
        cc.hyoban.moments.ui.publish.PendingChangesRow(container as PhoneContainer, onClick, modifier)

    // The app counts as started once the timeline has what it first shows, as the system measures
    // a start to the screen people came for. The splash stays up until then.
    @Composable
    override fun TimelineReady(ready: Boolean) {
        ReportDrawnWhen { ready }
        val activity = LocalActivity.current as? MainActivity
        LaunchedEffect(ready) { if (ready) activity?.showTimeline() }
    }

    override fun copyText(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    }

    override fun navigateTo(place: Place): Boolean = cc.hyoban.moments.ui.map.navigateTo(context, place)
}
