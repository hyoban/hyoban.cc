package cc.hyoban.moments.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import java.io.File
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.desktop_photos_saved
import kotlinx.coroutines.launch
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
    override fun ShareMoment(moment: MomentRecord, urls: MediaUrls, place: String?, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val show = cc.hyoban.moments.ui.rememberShowMessage()
        cc.hyoban.moments.ui.moment.DesktopShareDialog(
            moment, urls, place, onDismiss,
            savePhotos = { photos ->
                scope.launch {
                    val folder = chooseFolder() ?: return@launch
                    val count = withContext(Dispatchers.IO) { savePhotos(photos, folder, moment.dateKey) }
                    if (count > 0) show(org.jetbrains.compose.resources.getString(Res.string.desktop_photos_saved, count))
                    onDismiss()
                }
            },
        )
    }

    // A live photo shows its still; a video is handed to the Mac to play.
    override fun viewerMotion(media: cc.hyoban.moments.data.spec.MomentMedia): cc.hyoban.moments.ui.viewer.ViewerMotion? =
        if (media.type == cc.hyoban.moments.data.spec.MediaType.VIDEO) SystemVideo() else null

    /** A video played by whatever the Mac opens it with: its play badge over the poster opens it. */
    private class SystemVideo : cc.hyoban.moments.ui.viewer.ViewerMotion {
        private var url: String? = null

        @Composable
        override fun Over(url: String, active: Boolean, modifier: Modifier) {
            if (active) this.url = url
            androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
                cc.hyoban.moments.ui.components.PlayBadge(
                    androidx.compose.ui.Modifier.clickable(onClickLabel = null, role = androidx.compose.ui.semantics.Role.Button) { open(url) },
                )
            }
        }

        override fun toggle() {
            url?.let(::open)
        }

        private fun open(url: String) {
            runCatching { if (url.startsWith("http")) Desktop.getDesktop().browse(URI(url)) else Desktop.getDesktop().open(File(URI(url).path)) }
        }
    }

    // The one shown kept in a folder, as sharing a Moment keeps its photos.
    override suspend fun shareMedia(media: cc.hyoban.moments.data.spec.MomentMedia, urls: MediaUrls) {
        val source = media.published?.let { urls.image(it, 4096) } ?: media.local?.uri ?: return
        val folder = chooseFolder() ?: return
        withContext(Dispatchers.IO) { savePhotos(listOf(source), folder, media.takenAt?.take(10) ?: media.uid) }
    }

    // The system's file dialog, offering files by their names' endings.
    @Composable
    override fun rememberFilePicker(types: List<String>, onPicked: (String) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val endings = types.filter { '/' !in it }.toSet()
        return {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD).apply {
                        if (endings.isNotEmpty()) setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in endings }
                        isVisible = true
                    }
                    dialog.file?.let { File(dialog.directory, it) }
                }
                file?.let { onPicked(it.toURI().toString()) }
            }
        }
    }

    // The phone's backup as a way to start: its WebDAV directory asked for, then read.
    @Composable
    override fun onboardingStarts(container: AppContainer, next: () -> Unit) =
        listOf(cc.hyoban.moments.ui.settings.phoneBackupStart((container as DesktopContainer).phoneBackup, next))

    /** A folder chosen in the system's dialog, or null when it was left. */
    private suspend fun chooseFolder(): File? = withContext(Dispatchers.IO) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD).apply { isVisible = true }
            dialog.file?.let { File(dialog.directory, it) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    /** Fetches each of [photos] (a URL or a file) into [folder], numbered in order after [day]; how many arrived. */
    private fun savePhotos(photos: List<String>, folder: File, day: String): Int = photos.withIndex().count { (index, source) ->
        runCatching {
            val name = "$day-${index + 1}.${source.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..4 } ?: "jpg"}"
            val into = File(folder, name)
            if (source.startsWith("http")) {
                cc.hyoban.moments.data.Http.client.newCall(okhttp3.Request.Builder().url(source).build()).execute().use { response ->
                    check(response.isSuccessful)
                    into.outputStream().use { response.body!!.byteStream().copyTo(it) }
                }
            } else {
                File(URI(source).takeIf { source.startsWith("file:") }?.path ?: source).copyTo(into, overwrite = true)
            }
        }.isSuccess
    }

    @Composable
    override fun EditPlace(container: AppContainer, id: String, onDismiss: () -> Unit) = cc.hyoban.moments.ui.compose.EditPlace(container, id, onDismiss)

    // Where the phone backs up to, read here for its ledger and footprints.
    @Composable
    override fun StorageSettings(container: AppContainer) =
        cc.hyoban.moments.ui.settings.PhoneBackupSection((container as DesktopContainer).phoneBackup)

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
