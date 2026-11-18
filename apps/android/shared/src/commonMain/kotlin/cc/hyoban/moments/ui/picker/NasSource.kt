package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.logWarning
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.nas.NasAlbum
import cc.hyoban.moments.nas.NasDay
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** The NAS's photos, asked for only once they are listed, and its albums then, for the title's menu. */
@Composable
fun rememberNas(container: cc.hyoban.moments.AppContainer, deletion: Deletion): NasSource {
    val device = cc.hyoban.moments.ui.LocalDevice.current
    val scope = rememberCoroutineScope()
    val source = remember { NasSource(container.nas, device::toast, scope, deletion) }
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    source.linked = remember(moments) { nasLinked(moments) }
    return source
}

/** Each NAS photo a Moment's photo or video is linked to, by its id there, and that Moment's uid. */
fun nasLinked(moments: List<cc.hyoban.moments.data.spec.MomentRecord>): Map<Long, String> = buildMap {
    moments.forEach { record -> record.media.forEach { media -> media.nas?.let { put(it.id, record.uid) } } }
}

class NasSource(
    val nas: Nas,
    /** Says a short word of what went wrong, as the device does. */
    val toast: (String) -> Unit,
    private val scope: CoroutineScope,
    private val deletion: Deletion,
) : PhotoSource {
    /** The NAS photos in a Moment, by their id, and that Moment's uid ([nasLinked]). */
    var linked by mutableStateOf<Map<Long, String>>(emptyMap())

    private var albums by mutableStateOf<List<NasAlbum>>(emptyList())
    /** The one listing read, the timeline or an album: another starts again. */
    var list by mutableStateOf<NasList?>(null)
        private set
    override val home = NasListing(this, null)
    /** Where the photos were taken, for the map of them all. */
    val places = NasPlaces(this, scope)

    /** Fetches [photo]'s big thumbnail ahead, for the viewer turning to it. */
    fun prefetch(photo: NasPhoto) {
        scope.launch { nas.thumbnail(photo, NasProtocol.BIG) }
    }

    /** Reads the first page of [album]'s photos, the timeline's without one, and the albums if they aren't yet. */
    suspend fun show(album: NasAlbum?) {
        if (!nas.configured) return
        val shown = list?.takeIf { it.album == album } ?: NasList(nas, album).also { list = it }
        if (!shown.started) shown.start()
        if (albums.isEmpty()) albums = try { nas.albums() } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    fun more(list: NasList) {
        scope.launch { if (list.started) list.more() else list.start() }
    }

    /** Reads the page holding [list]'s photo at [index], on the page's scope, so it isn't lost half read when the tile goes. */
    fun need(list: NasList, index: Int) {
        scope.launch { list.need(index) }
    }

    @Composable
    override fun choices(): List<Choice> {
        if (!nas.configured) return emptyList()
        return listOf(Choice(stringResource(Res.string.section_nas), null, AppIcons.Nas, "picker_nas", home)) +
            albums.map { Choice(it.name, it.count, AppIcons.Nas, "picker_nas_${it.uuid}", NasListing(this, it)) }
    }

    override val fetches = true

    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<PhotoRef> =
        if (picks.isEmpty()) emptyList() else nas.download(picks.map { (it as NasPick).photo }, progress)

    // The NAS says first whether they go to its recycle bin, and the dialog says so.
    private var deleting by mutableStateOf<Pair<List<NasPhoto>, NasDeleteKind>?>(null)

    override fun delete(picks: List<Pick>, fromViewer: Boolean) {
        val these = picks.map { (it as NasPick).photo }
        scope.launch {
            try { deleting = these to nas.deleteKind(these) } catch (e: Exception) {
                if (e is CancellationException) throw e
                toast(textOf(Res.string.camera_delete_failed))
            }
        }
    }

    override val deleteTag = "nas_delete_chosen"
    override val deleteWords = Res.string.camera_delete

    @Composable
    override fun Dialogs() {
        val (these, kind) = deleting ?: return
        val one = these.singleOrNull()
        val bin = kind == NasDeleteKind.BIN
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (one != null) stringResource(Res.string.camera_delete_title) else stringResource(Res.string.camera_delete_many_title, these.size)) },
            text = {
                Text(when {
                    one != null && bin -> stringResource(Res.string.nas_delete_bin, one.name)
                    one != null -> stringResource(Res.string.nas_delete_forever, one.name)
                    bin -> stringResource(Res.string.nas_delete_bin_many)
                    else -> stringResource(Res.string.nas_delete_forever_many)
                })
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.action_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val ids = these.map { it.id }.toSet()
                    deletion.several(these.size) {
                        try {
                            val missed = nas.delete(these)
                            done = these.size
                            if (missed == 0) {
                                list?.removed(ids)
                                places.removed(ids)
                                deletion.gone(these.map { it.key }.toSet())
                            } else toast(textOf(Res.string.camera_delete_missed, missed))
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            toast(textOf(Res.string.camera_delete_failed))
                        }
                    }
                }, modifier = Modifier.testTag("nas_delete_confirm")) { Text(stringResource(Res.string.camera_delete), color = tokens.danger) }
            },
        )
    }
}

/** The NAS's timeline, or its [album]. */
data class NasListing(val nas: NasSource, val album: NasAlbum?) : Listing {
    override val source get() = nas

    private val list get() = nas.list?.takeIf { it.album == album }

    // The NAS's map is of all its photos, not an album's.
    override val places get() = nas.places.takeIf { album == null }

    @Composable
    override fun title() = album?.name ?: stringResource(Res.string.section_nas)

    @Composable
    override fun picks(): List<Pick> =
        list?.let { list ->
            // Every photo once read; until then a slot in its day, which reads its page once it is shown.
            remember(list, list.version) {
                list.photos.mapIndexed { index, photo ->
                    val day = list.dayAt(index)
                    if (photo != null) NasPick(photo, nas, day) else NasSlot(list, index, day, nas, album)
                }
            }
        }.orEmpty()

    @Composable
    override fun count(shown: List<Pick>) = list?.total ?: shown.size.takeIf { it > 0 }

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) {
        LaunchedEffect(album) { nas.show(album) }
        val list = list
        val failure = list?.failure
        when {
            // Listed only where there is nothing else to list, a computer's, before the NAS is set up.
            !nas.nas.configured -> Unset()
            failure != null -> Failed(AppIcons.Nas, nasReason(failure), Res.string.nas_failed_note) { list.retry(); nas.more(list) }
            list == null || !list.started || shown.isEmpty() && list.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            else -> grid(null) { nas.more(list) }
        }
    }
}

/**
 * A photo of the timeline's not read yet: a grey tile in its day, which reads its page once it has
 * stayed in view a moment, so a fling past it reads nothing. It is neither chosen nor opened.
 */
data class NasSlot(val list: NasList, val index: Int, override val day: LocalDate?, override val source: NasSource, val album: NasAlbum?) : Pick {
    override val key get() = "nas-slot/${album?.uuid}/$index"
    override val choosable get() = false
    override val openable get() = false

    override suspend fun thumbnail(size: Int): ImageBitmap? {
        delay(150)
        source.need(list, index)
        return null
    }

    @Composable override fun badge(): String? = null
    @Composable override fun description() = stringResource(Res.string.section_nas)
    @Composable override fun place(): String? = null
    @Composable override fun rememberDetails(): PhotoDetails? = null
    @Composable override fun Page(active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {}
}

/** A photo of the NAS's; in its timeline, under the day the timeline puts it, [listedDay]. */
data class NasPick(val photo: NasPhoto, override val source: NasSource, val listedDay: LocalDate? = null) : Pick {
    override val key get() = photo.key
    override val day get() = listedDay ?: photo.taken?.toLocalDate()
    override val linkedMoment get() = source.linked[photo.id]

    /** The NAS keeps its own thumbnails, small for the grid and big for the viewer, and its client caches them. */
    override suspend fun thumbnail(size: Int): ImageBitmap? =
        source.nas.thumbnail(photo, if (size > 320) NasProtocol.BIG else NasProtocol.SMALL)

    /** The next one's big thumbnail, fetched ahead so turning to it shows it at once. */
    override fun settled(next: Pick?) {
        (next as? NasPick)?.let { source.prefetch(it.photo) }
    }

    @Composable
    override fun badge(): String? = when {
        photo.video -> photo.duration?.trimStart('0')?.let { if (it.startsWith(':')) "0$it" else it }
        photo.live -> stringResource(Res.string.label_live)
        else -> null
    }

    @Composable
    override fun description() =
        stringResource(if (photo.video) Res.string.picker_video_description else Res.string.camera_photo_description, photo.name)

    @Composable
    override fun place() = photo.album?.name ?: stringResource(Res.string.section_nas)

    @Composable
    override fun rememberDetails(): PhotoDetails? {
        val nasName = stringResource(Res.string.section_nas)
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
     * Its big thumbnail over its small one; the original comes down once it is zoomed into, or
     * when it is added. A video plays from the NAS over it, as much of it as is watched.
     */
    @Composable
    override fun Page(active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
        val videos = LocalPickerVideos.current
        val plays = photo.video && photo.mime in PLAYED && videos !== NoVideos
        val stream by produceState<Pair<String, Map<String, String>>?>(null, key, active) {
            if (active && plays) value = runCatching { source.nas.original(photo) }.getOrNull()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val small by rememberThumb(this@NasPick)
            val large by rememberThumb(this@NasPick, size = 1440)
            val shown = large ?: small
            val shape = shown?.let { it.width.toFloat() / it.height }
                ?: if (photo.width != null && photo.height != null && photo.height > 0) photo.width.toFloat() / photo.height else 1f
            if (photo.video) {
                ZoomablePhoto(shape, onZoom, onTap = onTap) {
                    shown?.let { Image(it, contentDescription = photo.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    stream?.let { (url, headers) -> videos.Play(key, url, headers, active, Modifier.fillMaxSize()) }
                }
            } else if (shown != null) {
                // Zoomed in, its original comes down, then is read in tiles as it zooms.
                var deep by remember(key) { mutableStateOf(false) }
                val original by produceState(source.nas.cached(photo), key, deep) {
                    if (deep && value == null) value = source.nas.fetchOriginal(photo)
                }
                ZoomableOriginal(original ?: shown, shown, shape, photo.name, onZoom = { zoomed ->
                    if (zoomed) deep = true
                    onZoom(zoomed)
                }, onTap = onTap)
            }
            if (shown == null) Spinner(color = MediaOverlay.content)
            if (stream != null) videos.Waiting(key) else if (photo.video && !plays) Icon(AppIcons.Play, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(48.dp))
        }
    }
}

/**
 * Where the NAS's photos were taken, as its photo app keeps them, read whole the first time the
 * map is shown; and the photos of the heap opened, read a page at a time.
 */
class NasPlaces(private val source: NasSource, private val scope: CoroutineScope) : Places {
    private val nas get() = source.nas
    private var known by mutableStateOf<Map<Long, NasSpot>?>(null)
    private var failure by mutableStateOf<NasFailure?>(null)

    private var read by mutableStateOf<List<Spot>?>(null)

    /** Its places but those of photos in a Moment, which 地图 shows as that Moment's place already. */
    override val spots: List<Spot>? by derivedStateOf {
        val linked = source.linked
        read?.let { all -> if (linked.isEmpty()) all else all.filter { it.id !in linked } }
    }

    override val failed: (@Composable () -> Unit)?
        get() = failure?.let { why -> { Failed(AppIcons.Nas, nasReason(why), Res.string.nas_failed_note) { failure = null; scope.launch { load() } } } }

    override suspend fun load() {
        if (read != null || failure != null || !nas.configured) return
        // What was read last time shows at once; the NAS is asked again behind it.
        nas.keptSpots()?.let { kept ->
            known = kept.associateBy { it.id }
            this.read = kept.map { Spot(it.id, it.latitude, it.longitude) }
        }
        try {
            val read = nas.spots()
            known = read.associateBy { it.id }
            val spots = read.map { Spot(it.id, it.latitude, it.longitude) }
            // The same as kept, the heaps stay as they are.
            if (spots != this.read) this.read = spots
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logWarning("Moments", "nas: places not read", e)
            // Away from the NAS, what was kept stays without a word.
            if (this.read == null) failure = (e as? NasException)?.failure ?: if (e is java.io.IOException) NasFailure.UNREACHABLE else NasFailure.OTHER
        }
    }

    override suspend fun thumbnail(spot: Spot): ImageBitmap? = known?.get(spot.id)?.let { nas.thumbnail(it) }

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
                logWarning("Moments", "nas: a heap's photos not read", e)
                ended = true
                source.toast(textOf(nasReason((e as? NasException)?.failure ?: NasFailure.UNREACHABLE)))
            } finally { reading = false }
        }
    }

    @Composable
    override fun picks(): List<Pick> = remember(opened, photos.size) { photos.map { NasPick(it, source) } }

    /** What was deleted leaves the map and the heap opened. */
    fun removed(ids: Set<Long>) {
        read = read?.filter { it.id !in ids }
        photos.removeAll { it.id in ids }
        opened = opened?.filter { it.id !in ids }?.takeIf { it.isNotEmpty() }
    }
}

/** The videos the viewer plays itself, as the players read MP4 alone. */
private val PLAYED = setOf("video/mp4", "video/quicktime")

/**
 * The NAS's photos for one listing, its timeline or one of its albums, by their place in it; null
 * where not read yet. The timeline is laid out whole from its days first, as the NAS's web app
 * does, so the grid holds every day at once and reads only the pages it comes to; an album reads
 * a page at a time as the grid nears the end of what is read. [failure] says why the first page
 * couldn't be read.
 */
class NasList(private val nas: Nas, val album: NasAlbum?) {
    val photos: SnapshotStateList<NasPhoto?> = mutableStateListOf()
    /** The timeline's days, newest first, with how many each holds; empty for an album or before they're read. */
    var days by mutableStateOf<List<NasDay>>(emptyList())
        private set
    var total by mutableStateOf<Int?>(null)
    var failure by mutableStateOf<NasFailure?>(null)
    var started by mutableStateOf(false)
    /** Counts each change to [photos], a photo read into its slot included, which its size doesn't show. */
    var version by mutableIntStateOf(0)
        private set
    /** The pages being read, so each is asked for once at a time. */
    private val reading = mutableStateSetOf<Int>()
    val loading get() = reading.isNotEmpty()
    private val ended get() = total?.let { photos.size >= it && null !in photos } ?: false

    /** The timeline's days, then its first page; an album's first page. */
    suspend fun start() {
        if (started) return
        started = true
        if (album == null) days = try { nas.timelineDays() } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Without them it reads on a page at a time, as an album does.
            logWarning("Moments", "nas: timeline days not read", e)
            emptyList()
        }
        val laid = days.sumOf { it.count }
        if (laid > 0) photos.addAll(arrayOfNulls<NasPhoto>(laid))
        version++
        read(0)
    }

    /** The page after the last photo read, unless all are. */
    suspend fun more() {
        if (ended || failure != null) return
        read((photos.indexOfLast { it != null } + 1) / Nas.PAGE)
    }

    /** The page holding the photo at [index], unless it is read or being read. */
    suspend fun need(index: Int) {
        if (photos.getOrNull(index) == null) read(index / Nas.PAGE)
    }

    private suspend fun read(page: Int) {
        if (page in reading) return
        val offset = page * Nas.PAGE
        reading += page
        try {
            val (got, count) = if (album == null) nas.timeline(offset) else nas.albumPhotos(album, offset)
            got.forEachIndexed { i, photo -> if (offset + i < photos.size) photos[offset + i] = photo else photos += photo }
            version++
            total = when {
                count != null -> count.coerceAtLeast(photos.size)
                got.size < Nas.PAGE -> photos.size
                else -> null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logWarning("Moments", "nas: photos not read", e)
            // Only a listing with nothing read says so; a page further on is asked for again when it is come to.
            if (photos.none { it != null }) failure = (e as? NasException)?.failure ?: if (e is java.io.IOException) NasFailure.UNREACHABLE else NasFailure.OTHER
        } finally { reading -= page }
    }

    /** The day the timeline puts the photo at [index] under, where its days are read. */
    fun dayAt(index: Int): LocalDate? {
        var at = 0
        for (day in days) {
            at += day.count
            if (index < at) return day.day
        }
        return null
    }

    /** Takes out the photos of [ids], deleted, and their places in their days. */
    fun removed(ids: Set<Long>) {
        val gone = photos.withIndex().filter { (_, photo) -> photo != null && photo.id in ids }.map { it.index }
        if (gone.isEmpty()) return
        val counts = days.map { it.count }.toMutableList()
        gone.forEach { index ->
            var at = 0
            val day = counts.indexOfFirst { at += it; index < at }
            if (day >= 0) counts[day] -= 1
        }
        days = days.zip(counts) { day, count -> day.copy(count = count) }.filter { it.count > 0 }
        gone.asReversed().forEach { photos.removeAt(it) }
        version++
        total = total?.minus(gone.size)
    }

    fun retry() {
        failure = null
        started = false
        days = emptyList()
        photos.clear()
        version++
    }
}

/** What the picker says when the NAS couldn't be read. */
private fun nasReason(failure: NasFailure) = when (failure) {
    NasFailure.NOT_FOUND -> Res.string.nas_failed_not_found
    NasFailure.UNREACHABLE -> Res.string.nas_failed_unreachable
    NasFailure.SIGN_IN -> Res.string.nas_failed_sign_in
    NasFailure.OTHER -> Res.string.nas_failed_other
}
