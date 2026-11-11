package cc.hyoban.moments.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import cc.hyoban.moments.ui.components.openAppSettings
import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.stringResource
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.ui.moment.ShareMenu
import cc.hyoban.moments.ui.moment.ShareTarget
import cc.hyoban.moments.ui.trips.PlaybackController
import cc.hyoban.moments.ui.map.AtlasMapStateStore

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** The phone's own way with what the pages ask of their device: MapLibre's maps, Android's sharing, the pages only it has. */
class PhoneDevice(private val context: Context) : Device, AutoCloseable {
    override val atlasMapStateStore = AtlasMapStateStore()

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

    @Composable
    override fun PhotoPicker(container: AppContainer, limit: Int, onDismiss: () -> Unit, onPicked: (List<android.net.Uri>) -> Unit) =
        cc.hyoban.moments.ui.picker.PhonePhotoPicker(container as PhoneContainer, limit, onDismiss, onPicked)

    // Asked with the sheet first (PermissionSheet); refused, the map waits for a drag or a search.
    @Composable
    override fun rememberLocationAsk(onResult: (Boolean) -> Unit): LocationAsk {
        val ask = cc.hyoban.moments.ui.components.rememberPermissionAsk(LOCATION_PERMISSIONS, cc.hyoban.moments.ui.components.PermissionWords(
            cc.hyoban.moments.ui.design.AppIcons.Locate,
            stringResource(Res.string.permission_locate_title),
            stringResource(Res.string.permission_locate_body),
            stringResource(Res.string.permission_not_now),
            listOf(cc.hyoban.moments.ui.design.PermissionFact(cc.hyoban.moments.ui.design.AppIcons.Pin, stringResource(Res.string.permission_locate_fact))),
        ), onResult = onResult)
        return remember(ask) { LocationAsk({ ask.asked }, { ask() }) }
    }

    override fun canLocate(): Boolean = LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun currentFix(): cc.hyoban.moments.data.local.Fix? =
        cc.hyoban.moments.data.local.currentLocation(context)?.let { cc.hyoban.moments.data.local.Fix(it.latitude, it.longitude) }

    override fun openAppSettings() = context.openAppSettings()

    override val hasCamera: Boolean = true

    // The phone's camera app itself, with all it can do; what it takes goes to the gallery, to be added from there.
    override fun openCamera(): Boolean =
        runCatching { context.startActivity(android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }.isSuccess

    override fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

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
        cc.hyoban.moments.ui.compose.EditPlace(container, id, onDismiss)

    @Composable
    override fun StorageSettings(container: AppContainer) = cc.hyoban.moments.ui.settings.PhoneStorageSettings(container as PhoneContainer)

    @Composable
    override fun UpdateSettings(container: AppContainer) {
        val phone = container as PhoneContainer
        cc.hyoban.moments.ui.settings.UpdateSection(phone.updates, phone.scope)
    }

    // The notification that keeps publishing going in the background asks once, on Android 13
    // and later; it publishes whatever the answer.
    @Composable
    override fun beforePublishing(publish: () -> Unit): () -> Unit {
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { publish() }
        return {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                publish()
            }
        }
    }

    // The app counts as started once the timeline has what it first shows, as the system measures
    // a start to the screen people came for. The splash stays up until then.
    @Composable
    override fun TimelineReady(ready: Boolean) {
        ReportDrawnWhen { ready }
        val activity = LocalActivity.current as? MainActivity
        LaunchedEffect(ready) { if (ready) activity?.showTimeline() }
    }

    override suspend fun moduleChanged(module: cc.hyoban.moments.data.spec.Module) = cc.hyoban.moments.ui.settings.applyModule(context, module)

    override fun copyText(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    }

    override fun navigateTo(place: Place): Boolean = cc.hyoban.moments.ui.map.navigateTo(context, place)

    override fun close() = atlasMapStateStore.close()
}
