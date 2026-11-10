package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.ui.viewer.VideoPlayback
import cc.hyoban.moments.ui.viewer.VideoState
import cc.hyoban.moments.ui.viewer.VideoWaiting
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.camera.TAG
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.nas.NasAlbum
import cc.hyoban.moments.nas.NasDeleteKind
import cc.hyoban.moments.nas.NasException
import cc.hyoban.moments.nas.NasFailure
import cc.hyoban.moments.nas.NasPhoto
import cc.hyoban.moments.nas.NasProtocol
import cc.hyoban.moments.nas.NasSpot
import cc.hyoban.moments.ui.design.AlertDialog
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.PhotoDetails
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The NAS's photos, asked for only once they are listed, and its albums then, for the title's menu. */
@Composable
internal fun rememberNas(nas: Nas, deletion: Deletion): NasSource {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { NasSource(nas, context, scope, deletion) }
}

internal class NasSource(
    val nas: Nas,
    val context: Context,
    private val scope: CoroutineScope,
    private val deletion: Deletion,
) : PhotoSource {
    private var albums by mutableStateOf<List<NasAlbum>>(emptyList())
    /** The one listing read, the timeline or an album: another starts again. */
    var list by mutableStateOf<NasList?>(null)
        private set
    private val root = NasListing(this, null)
    /** Where the photos were taken, for the map of them all. */
    val places = NasPlaces(this, scope)

    /** Reads the first page of [album]'s photos, the timeline's without one, and the albums if they aren't yet. */
    suspend fun show(album: NasAlbum?) {
        if (!nas.configured) return
        val shown = list?.takeIf { it.album == album } ?: NasList(nas, album).also { list = it }
        if (!shown.started) shown.more()
        if (albums.isEmpty()) albums = try { nas.albums() } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    fun more(list: NasList) {
        scope.launch { list.more() }
    }

    @Composable
    override fun choices(): List<Choice> {
        if (!nas.configured) return emptyList()
        return listOf(Choice(stringResource(R.string.section_nas), null, AppIcons.Nas, "picker_nas", root)) +
            albums.map { Choice(it.name, it.count, AppIcons.Nas, "picker_nas_${it.uuid}", NasListing(this, it)) }
    }

    override val fetches = true

    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<Uri> =
        if (picks.isEmpty()) emptyList() else nas.download(picks.map { (it as NasPick).photo }, progress)

    // The NAS says first whether they go to its recycle bin, and the dialog says so.
    private var deleting by mutableStateOf<Pair<List<NasPhoto>, NasDeleteKind>?>(null)

    override fun delete(picks: List<Pick>, fromViewer: Boolean) {
        val these = picks.map { (it as NasPick).photo }
        scope.launch {
            try { deleting = these to nas.deleteKind(these) } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(context, R.string.camera_delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override val deleteTag = "nas_delete_chosen"
    override val deleteWords = R.string.camera_delete

    @Composable
    override fun Dialogs() {
        val (these, kind) = deleting ?: return
        val one = these.singleOrNull()
        val bin = kind == NasDeleteKind.BIN
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (one != null) stringResource(R.string.camera_delete_title) else stringResource(R.string.camera_delete_many_title, these.size)) },
            text = {
                Text(when {
                    one != null && bin -> stringResource(R.string.nas_delete_bin, one.name)
                    one != null -> stringResource(R.string.nas_delete_forever, one.name)
                    bin -> stringResource(R.string.nas_delete_bin_many)
                    else -> stringResource(R.string.nas_delete_forever_many)
                })
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val ids = these.map { it.id }.toSet()
                    deletion.several(these.size) {
                        try {
                            val missed = nas.delete(these)
                            done = these.size
                            if (missed == 0) {
                                list?.let { list -> list.photos.removeAll { it.id in ids }; list.total = list.total?.minus(these.size) }
                                places.removed(ids)
                                deletion.gone(these.map { it.key }.toSet())
                            } else Toast.makeText(context, context.getString(R.string.camera_delete_missed, missed), Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Toast.makeText(context, R.string.camera_delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.testTag("nas_delete_confirm")) { Text(stringResource(R.string.camera_delete), color = tokens.danger) }
            },
        )
    }
}

/** The NAS's timeline, or its [album]. */
internal data class NasListing(val nas: NasSource, val album: NasAlbum?) : Listing {
    override val source get() = nas

    private val list get() = nas.list?.takeIf { it.album == album }

    // The NAS's map is of all its photos, not an album's.
    override val places get() = nas.places.takeIf { album == null }

    @Composable
    override fun title() = album?.name ?: stringResource(R.string.section_nas)

    @Composable
    override fun picks(): List<Pick> =
        list?.let { list -> remember(list, list.photos.size) { list.photos.map { NasPick(it, nas) } } }.orEmpty()

    @Composable
    override fun count(shown: List<Pick>) = list?.total ?: shown.size.takeIf { it > 0 }

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) {
        LaunchedEffect(album) { nas.show(album) }
        val list = list
        val failure = list?.failure
        when {
            failure != null && shown.isEmpty() -> Failed(AppIcons.Nas, nasReason(failure), R.string.nas_failed_note) { list.retry(); nas.more(list) }
            list == null || shown.isEmpty() && (list.loading || !list.started) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            else -> grid(null) { nas.more(list) }
        }
    }
}

internal data class NasPick(val photo: NasPhoto, override val source: NasSource) : Pick {
    override val key get() = photo.key
    override val day get() = photo.taken?.toLocalDate()

    /** The NAS keeps its own thumbnails, small for the grid and big for the viewer, and its client caches them. */
    override suspend fun thumbnail(context: Context, size: Int): Bitmap? =
        source.nas.thumbnail(photo, if (size > 320) NasProtocol.BIG else NasProtocol.SMALL)

    @Composable
    override fun badge(): String? = when {
        photo.video -> photo.duration?.trimStart('0')?.let { if (it.startsWith(':')) "0$it" else it }
        photo.live -> stringResource(R.string.label_live)
        else -> null
    }

    @Composable
    override fun description() =
        stringResource(if (photo.video) R.string.picker_video_description else R.string.camera_photo_description, photo.name)

    @Composable
    override fun place() = photo.album?.name ?: stringResource(R.string.section_nas)

    @Composable
    override fun rememberDetails(): PhotoDetails? {
        val nasName = stringResource(R.string.section_nas)
        return produceState<PhotoDetails?>(null, key) {
            val base = PhotoDetails(
                takenAt = photo.taken?.atZone(ZoneId.systemDefault())?.toOffsetDateTime(),
                name = photo.name, format = PhotoDetails.format(photo.mime, photo.name),
                width = photo.width, height = photo.height, bytes = photo.size, album = photo.album?.name ?: nasName,
            )
            value = base
            // Its EXIF from the start of the original, as a published one's.
            val head = if (photo.video) null else source.nas.head(photo)
            value = if (head != null) PhotoDetails.read(base) { head.inputStream() } else base
        }.value
    }

    /**
     * Its big thumbnail over its small one; the original comes down only when it is added. A
     * video plays from the NAS over it, as much of it as is watched.
     */
    @Composable
    override fun Page(active: Boolean, video: VideoState, onZoom: (Boolean) -> Unit) {
        val plays = photo.video && photo.mime in PLAYED
        val stream by produceState<Pair<String, Map<String, String>>?>(null, key, active) {
            if (active && plays) value = runCatching { source.nas.original(photo) }.getOrNull()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val small by rememberThumb(this@NasPick)
            val large by rememberThumb(this@NasPick, size = 1440)
            val shown = large ?: small
            val shape = shown?.let { it.width.toFloat() / it.height }
                ?: if (photo.width != null && photo.height != null && photo.height > 0) photo.width.toFloat() / photo.height else 1f
            ZoomablePhoto(shape, onZoom) {
                shown?.let { Image(it.asImageBitmap(), contentDescription = photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                stream?.let { (url, headers) -> VideoPlayback(url, active, video, Modifier.fillMaxSize(), headers = headers, window = false) }
            }
            if (shown == null) Spinner(color = MediaOverlay.content)
            if (stream != null) VideoWaiting(video) else if (photo.video && !plays) Icon(AppIcons.Play, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(48.dp))
        }
    }
}

/**
 * Where the NAS's photos were taken, as its photo app keeps them, read whole the first time the
 * map is shown; and the photos of the heap opened, read a page at a time.
 */
internal class NasPlaces(private val source: NasSource, private val scope: CoroutineScope) : Places {
    private val nas get() = source.nas
    private var known by mutableStateOf<Map<Long, NasSpot>?>(null)
    private var failure by mutableStateOf<NasFailure?>(null)

    override var spots by mutableStateOf<List<Spot>?>(null)
        private set

    override val failed: (@Composable () -> Unit)?
        get() = failure?.let { why -> { Failed(AppIcons.Nas, nasReason(why), R.string.nas_failed_note) { failure = null; scope.launch { load() } } } }

    override suspend fun load() {
        if (spots != null || failure != null || !nas.configured) return
        try {
            val read = nas.spots()
            known = read.associateBy { it.id }
            spots = read.map { Spot(it.id, it.latitude, it.longitude) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w(TAG, "nas: places not read", e)
            failure = (e as? NasException)?.failure ?: if (e is java.io.IOException) NasFailure.UNREACHABLE else NasFailure.OTHER
        }
    }

    override suspend fun thumbnail(spot: Spot): Bitmap? = known?.get(spot.id)?.let { nas.thumbnail(it) }

    override var opened by mutableStateOf<List<Spot>?>(null)
        private set
    private val photos = mutableStateListOf<NasPhoto>()
    private var ended = false
    override var reading by mutableStateOf(false)
        private set
    private var job: Job? = null

    override fun open(heap: List<Spot>?) {
        job?.cancel()
        photos.clear()
        ended = false
        reading = false
        opened = heap
        if (heap != null) more()
    }

    override fun more() {
        val heap = opened ?: return
        if (reading || ended) return
        reading = true
        job = scope.launch {
            try {
                val page = nas.spotPhotos(heap.map { it.id }, photos.size)
                photos.addAll(page)
                ended = page.size < Nas.PAGE || photos.size >= heap.size
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w(TAG, "nas: a heap's photos not read", e)
                ended = true
                Toast.makeText(source.context, nasReason((e as? NasException)?.failure ?: NasFailure.UNREACHABLE), Toast.LENGTH_SHORT).show()
            } finally { reading = false }
        }
    }

    @Composable
    override fun picks(): List<Pick> = remember(opened, photos.size) { photos.map { NasPick(it, source) } }

    /** What was deleted leaves the map and the heap opened. */
    fun removed(ids: Set<Long>) {
        spots = spots?.filter { it.id !in ids }
        photos.removeAll { it.id in ids }
        opened = opened?.filter { it.id !in ids }?.takeIf { it.isNotEmpty() }
    }
}

/** The videos the viewer plays itself, as the players read MP4 alone. */
private val PLAYED = setOf("video/mp4", "video/quicktime")

/**
 * The NAS's photos for one listing, its timeline or one of its albums, read a page at a time as
 * the grid nears the end of what is read; [failure] says why it couldn't be read.
 */
internal class NasList(private val nas: Nas, val album: NasAlbum?) {
    val photos: SnapshotStateList<NasPhoto> = mutableStateListOf()
    var total by mutableStateOf<Int?>(null)
    var loading by mutableStateOf(false)
    var failure by mutableStateOf<NasFailure?>(null)
    var started by mutableStateOf(false)
    private val ended get() = total?.let { photos.size >= it } ?: false

    suspend fun more() {
        if (loading || ended || failure != null) return
        loading = true
        started = true
        try {
            val (page, count) = if (album == null) nas.timeline(photos.size) else nas.albumPhotos(album, photos.size)
            photos.addAll(page)
            total = if (page.isEmpty()) photos.size else count ?: if (page.size < Nas.PAGE) photos.size else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w(TAG, "nas: photos not read", e)
            failure = (e as? NasException)?.failure ?: if (e is java.io.IOException) NasFailure.UNREACHABLE else NasFailure.OTHER
        } finally { loading = false }
    }

    fun retry() { failure = null }
}

/** What the picker says when the NAS couldn't be read. */
private fun nasReason(failure: NasFailure) = when (failure) {
    NasFailure.NOT_FOUND -> R.string.nas_failed_not_found
    NasFailure.UNREACHABLE -> R.string.nas_failed_unreachable
    NasFailure.SIGN_IN -> R.string.nas_failed_sign_in
    NasFailure.OTHER -> R.string.nas_failed_other
}
