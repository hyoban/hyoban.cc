package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.plugins.nas
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.ui.components.BadgeKind
import cc.hyoban.moments.ui.format.parseClock
import cc.hyoban.moments.ui.viewer.clipOf

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // While 地点 is drawn, a photo in a Moment is on the map as that Moment's place already.
    source.hideLinked = cc.hyoban.moments.ui.LocalSettings.current.mapPlaces
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

    /** Whether the map's photos leave out those [linked], as the places show them. */
    var hideLinked by mutableStateOf(true)

    private var albums by mutableStateOf<List<NasAlbum>>(emptyList())
    /** The one listing read, the timeline or an album under a filter: another, or another filter, starts again. */
    var list by mutableStateOf<NasList?>(null)
        private set

    /** The listing shown before [list], shown still until [list] has something to show. */
    var previous by mutableStateOf<NasList?>(null)
        private set

    /**
     * The timeline's days under each of the NAS's own conditions, while the page is open: another
     * time chosen lays the timeline out from them at once, rather than asking again.
     */
    private val timelineDays = mutableMapOf<cc.hyoban.moments.nas.NasQuery, List<NasDay>>()

    /** Each album's days seen so far, whatever the time chosen, for the filter's years: an album has no days to ask for. */
    val albumDays = mutableMapOf<String, MutableSet<LocalDate>>()
    override val home = NasListing(this, null)

    /** An album last picked opens the whole NAS when the menu no longer lists it. */
    override fun rememberedListing(tag: String): Listing? = home.takeIf { tag.startsWith("picker_nas_") && nas.configured }
    /** Where the photos were taken, for the map of them all. */
    val places = NasPlaces(this, scope)

    /** Fetches [photo]'s big thumbnail ahead, for the viewer turning to it. */
    fun prefetch(photo: NasPhoto) {
        scope.launch { nas.thumbnail(photo, NasProtocol.BIG) }
        if (!photo.video) scope.launch { nas.head(photo) }
    }

    /**
     * Reads the first page of [album]'s photos under [filter], the timeline's without one, and the
     * albums if they aren't yet. Asked whether they have a place, it reads where they were taken first.
     */
    suspend fun show(album: NasAlbum?, filter: PhotoFilter) {
        if (!nas.configured) return
        val shown = list?.takeIf { it.album == album && it.filter == filter } ?: run {
            previous = list?.takeIf { it.ready }
            if (filter.place != null) places.load()
            val known = if (album == null) timelineDays[filter.nasQuery] else null
            NasList(nas, album, filter, keeps(album, filter), known).also { list = it }
        }
        if (!shown.started) shown.start()
        if (album == null && shown.allDays.isNotEmpty()) timelineDays[filter.nasQuery] = shown.allDays
        if (albums.isEmpty()) albums = try { nas.albums() } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    /**
     * What [filter] asks of a photo that the NAS can't tell: whether it has a place (as the NAS's
     * map knows) and is in a Moment; and, for an album, which has no days to start from, its time.
     */
    private fun keeps(album: NasAlbum?, filter: PhotoFilter): ((NasPhoto) -> Boolean)? {
        val place = filter.place
        val moment = filter.moment
        if (place == null && moment == null && (album == null || filter.days == null)) return null
        val placed = places.placed
        return { photo ->
            (place == null || placed == null || (photo.id in placed) == place) &&
                (moment == null || (photo.id in linked) == moment)
        }
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

    /** The listing read under [filter], once it is. */
    private fun list(filter: PhotoFilter) = nas.list?.takeIf { it.album == album && it.filter == filter }

    // The NAS's map is of all its photos, not an album's.
    override val places get() = nas.places.takeIf { album == null }

    // The NAS keeps its photos' EXIF for its timeline's conditions alone; an album's list takes only their kinds.
    override val facets get() = if (album == null) TIMELINE_FACETS else ALBUM_FACETS

    @Composable
    override fun title() = album?.name ?: stringResource(Res.string.section_nas)

    @Composable
    override fun picks(): List<Pick> = shown(PhotoFilter.NONE)

    @Composable
    override fun shown(filter: PhotoFilter): List<Pick> {
        LaunchedEffect(album, filter) { nas.show(album, filter) }
        // Another filter's photos stay until this one's are laid out, rather than the page emptying for a frame.
        val list = list(filter)?.takeIf { it.ready } ?: nas.previous?.takeIf { it.album == album } ?: nas.list?.takeIf { it.album == album }
        return list?.let { list ->
            // Every photo once read; until then a slot in its day, which reads its page once it is shown.
            remember(list, list.version) {
                val photos = list.photos.toList()
                val days = list.daysOf(photos.size)
                photos.mapIndexed { index, photo ->
                    val day = days[index]
                    if (photo != null) NasPick(photo, nas, day) else NasSlot(list, index, day, nas, album)
                }.also { picks -> album?.let { nas.albumDays.getOrPut(it.uuid) { mutableSetOf() } += picks.mapNotNull { it.day } } }
            }
        }.orEmpty()
    }

    /** The timeline's days under the NAS's own conditions; an album's as far as they are read. */
    @Composable
    override fun days(filter: PhotoFilter): List<LocalDate> {
        val list = nas.list?.takeIf { it.album == album }
        if (album == null) {
            // The last laid out stay while another's are read, so the years don't go and come back.
            val laid = list?.allDays?.takeIf { it.isNotEmpty() }
            val days = remember(laid) { laid?.map { it.day } }
            val last = remember { arrayOf<List<LocalDate>>(emptyList()) }
            if (days != null) last[0] = days
            return days ?: last[0]
        }
        val shown = shown(filter)
        val seen = nas.albumDays.getOrPut(album.uuid) { mutableSetOf() }
        return remember(shown, seen.size) { seen.sortedDescending() }
    }

    @Composable
    override fun exifOptions(filter: PhotoFilter): Map<String, List<cc.hyoban.moments.nas.NasOption>>? {
        if (album != null) return null
        val query = filter.nasQuery
        return produceState<Map<String, List<cc.hyoban.moments.nas.NasOption>>?>(null, query) {
            value = try { nas.nas.options(query) } catch (e: Exception) {
                if (e is CancellationException) throw e
                logWarning("Moments", "nas: filter options not read", e)
                null
            }
        }.value
    }

    @Composable
    override fun count(shown: List<Pick>) = nas.list?.takeIf { it.album == album }?.total ?: shown.size.takeIf { it > 0 }

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) {
        val list = nas.list?.takeIf { it.album == album }
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

/** The timeline: everything, the NAS narrowing its kinds and EXIF itself. */
private val TIMELINE_FACETS = setOf(Facet.KIND, Facet.TIME, Facet.PLACE, Facet.MOMENT, Facet.EXIF)

/** An album: all but EXIF, which its list doesn't take. */
private val ALBUM_FACETS = setOf(Facet.KIND, Facet.TIME, Facet.PLACE, Facet.MOMENT)

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

    override val type get() = MediaType.IMAGE

    @Composable override fun badge(): BadgeKind? = null
    @Composable override fun description() = stringResource(Res.string.section_nas)
    @Composable override fun place(): String? = null
    @Composable override fun rememberDetails(): PhotoDetails? = null
    @Composable override fun rememberStill(active: Boolean, deep: Boolean) = cc.hyoban.moments.ui.viewer.ViewedStill(1f)
}

/** A photo of the NAS's; in its timeline, under the day the timeline puts it, [listedDay]. */
data class NasPick(val photo: NasPhoto, override val source: NasSource, val listedDay: LocalDate? = null) : Pick {
    override val key get() = photo.key
    override val day get() = listedDay ?: photo.taken?.toLocalDate()
    override val linkedMoment get() = source.linked[photo.id]

    /** A video only where the device can add one; elsewhere it is looked at alone. */
    override val choosable get() = !photo.video || cc.hyoban.moments.data.local.Photos.copiesVideos
    override val kind get() = when {
        photo.video -> PhotoKind.VIDEO
        photo.live -> PhotoKind.LIVE
        else -> PhotoKind.PHOTO
    }
    override val hasPlace get() = source.places.placed?.let { photo.id in it }
    override val inMoment get() = photo.id in source.linked

    override val type get() = when {
        photo.video -> MediaType.VIDEO
        photo.live -> MediaType.LIVE_PHOTO
        else -> MediaType.IMAGE
    }

    override val playable get() = photo.mime in cc.hyoban.moments.ui.viewer.PLAYED_VIDEOS

    /**
     * A video played from the NAS, as much of it as is watched; a live photo's clip once its page is
     * first shown, read as a Moment's linked to it is ([clipOf]).
     */
    @Composable
    override fun rememberClip(active: Boolean): cc.hyoban.moments.ui.viewer.MotionSource? =
        produceState<cc.hyoban.moments.ui.viewer.MotionSource?>(null, key, active) {
            if (!active || value != null) return@produceState
            value = when {
                photo.video -> if (playable) runCatching { source.nas.original(photo) }.getOrNull()
                    ?.let { (url, headers) -> cc.hyoban.moments.ui.viewer.MotionSource(url, headers = headers) } else null
                // Not read at all is tried again next time rather than taken for a still.
                photo.live -> try {
                    source.nas.clipOf(photo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
        }.value

    /** Its big thumbnail over its small one; the original comes down once it is zoomed into, or when it is added, read in tiles as it zooms. */
    @Composable
    override fun rememberStill(active: Boolean, deep: Boolean): cc.hyoban.moments.ui.viewer.ViewedStill {
        val small by rememberThumb(this)
        val large by rememberThumb(this, size = 1440)
        val shown = large ?: small
        val shape = shown?.let { it.width.toFloat() / it.height }
            ?: if (photo.width != null && photo.height != null && photo.height > 0) photo.width.toFloat() / photo.height else 1f
        val original by produceState(if (photo.video) null else source.nas.cached(photo), key, deep) {
            if (!photo.video && deep && value == null) value = source.nas.fetchOriginal(photo)
        }
        return cc.hyoban.moments.ui.viewer.ViewedStill(shape, thumb = shown, model = original)
    }

    /** The NAS keeps its own thumbnails, small for the grid and big for the viewer, and its client caches them. */
    override suspend fun thumbnail(size: Int): ImageBitmap? =
        source.nas.thumbnail(photo, if (size > 320) NasProtocol.BIG else NasProtocol.SMALL)

    /** Its big thumbnail and the start of its original (its EXIF, kept), fetched ahead so turning to it shows it at once. */
    override fun ahead(from: cc.hyoban.moments.ui.viewer.ViewedMedia, reading: cc.hyoban.moments.ui.viewer.ReadAhead) = source.prefetch(photo)

    @Composable
    override fun badge(): BadgeKind? = when {
        photo.video -> BadgeKind.Video(photo.duration?.let(::parseClock))
        photo.live -> BadgeKind.Live
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
}

/**
 * Where the NAS's photos were taken, as its photo app keeps them, read whole the first time the
 * map is shown; and the photos of the heap opened, read a page at a time.
 */
class NasPlaces(private val source: NasSource, private val scope: CoroutineScope) : Places {
    private val nas get() = source.nas
    private var known by mutableStateOf<Map<Long, NasSpot>?>(null)

    /** The photos with a place, all of them, linked to a Moment too, by id; null until read. */
    val placed: Set<Long>? get() = known?.keys
    private var failure by mutableStateOf<NasFailure?>(null)

    private var read by mutableStateOf<List<Spot>?>(null)

    /** Every photo and video of the timeline, by id, once read: what places by time those without a place. */
    private var timeline by mutableStateOf<Map<Long, NasPhoto>?>(null)

    /** When its photos were taken, but those in a Moment while the places show them, as the NAS's clock had it there, read as this device's zone. */
    override val taken: Map<Long, Long>? by derivedStateOf {
        val linked = if (source.hideLinked) source.linked else emptyMap()
        val zone = java.time.ZoneId.systemDefault()
        timeline?.values?.mapNotNull { photo ->
            photo.taken?.takeIf { !photo.video && photo.id !in linked }?.let { photo.id to it.atZone(zone).toInstant().toEpochMilli() }
        }?.toMap()
    }

    /** Its places but those of photos in a Moment, which 地图 shows as that Moment's place already while its places are on. */
    override val spots: List<Spot>? by derivedStateOf {
        val linked = if (source.hideLinked) source.linked else emptyMap()
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
        if (failure == null) try {
            timeline = nas.everyPhoto().associateBy { it.id }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Without it, the photos without a place stay off the map.
            logWarning("Moments", "nas: timeline not read for the map", e)
        }
    }

    override suspend fun thumbnail(spot: Spot): ImageBitmap? =
        known?.get(spot.id)?.let { nas.thumbnail(it) } ?: timeline?.get(spot.id)?.let { nas.thumbnail(it) }

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
        // Read whole from the timeline where it holds them all, newest first; otherwise those the
        // NAS's map places come from it a page at a time, and those placed by time after them.
        val timeline = timeline.orEmpty()
        if (heap.all { it.id in timeline }) {
            photos.clear()
            photos.addAll(heap.mapNotNull { timeline[it.id] }.sortedByDescending { it.taken })
            ended = true
            return
        }
        val (mapped, placed) = heap.partition { known?.containsKey(it.id) == true }
        val own = placed.mapNotNull { timeline[it.id] }.sortedByDescending { it.taken }
        reading = true
        job = scope.launch {
            try {
                val page = nas.spotPhotos(mapped.map { it.id }, photos.size)
                photos.addAll(page)
                ended = page.size < Nas.PAGE || photos.size >= mapped.size
                if (ended) photos.addAll(own)
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
        timeline = timeline?.minus(ids)
        photos.removeAll { it.id in ids }
        opened = opened?.filter { it.id !in ids }?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The NAS's photos for one listing, its timeline or one of its albums, under [filter], by their
 * place in it; null where not read yet. The NAS narrows them to the filter's kinds and EXIF
 * conditions itself. The timeline is laid out whole from its days first, as the NAS's web app does,
 * so the grid holds every day at once and reads only the pages it comes to; a time chosen keeps
 * only its days, read from where they start. What the NAS can't narrow by ([keeps]: a place, a
 * Moment, and an album's time) is read a page after another from the start, as an album is, keeping
 * what passes, as the grid nears the end of what is kept. [failure] says why the first page
 * couldn't be read.
 */
class NasList(
    private val nas: Nas,
    val album: NasAlbum?,
    val filter: PhotoFilter,
    /** What the NAS can't tell of a photo that the filter asks; null when it asks nothing more. */
    private val keeps: ((NasPhoto) -> Boolean)?,
    /** The timeline's days under the same conditions, read already for another time, so they aren't asked for again. */
    private val known: List<NasDay>? = null,
) {
    private val query = filter.nasQuery
    private val range = filter.days
    val photos: SnapshotStateList<NasPhoto?> = mutableStateListOf()
    /** The timeline's days the filter keeps, newest first, with how many each holds; empty for an album, one read a page after another, or before they're read. */
    var days by mutableStateOf<List<NasDay>>(emptyList())
        private set
    /** All the timeline's days under the NAS's own conditions, whatever the time chosen, for the filter's years and months. */
    var allDays by mutableStateOf<List<NasDay>>(emptyList())
        private set
    var total by mutableStateOf<Int?>(null)
    var failure by mutableStateOf<NasFailure?>(null)
    var started by mutableStateOf(false)
    /** Counts each change to [photos], a photo read into its slot included, which its size doesn't show. */
    var version by mutableIntStateOf(0)
        private set
    /** The pages being read, so each is asked for once at a time. */
    private val reading = mutableStateSetOf<Int>()
    /**
     * Whether its days are read and its slots laid out: until then where it starts isn't known, so
     * nothing is read past what [start] reads, or a page from the top would land in its first slots.
     */
    private var laid by mutableStateOf(false)
    val loading get() = reading.isNotEmpty() || started && !laid
    private val ended get() = total?.let { photos.size >= it && null !in photos } ?: false

    /** Whether it has something to show: photos or their slots, the end of none, or why not. */
    val ready get() = laid && (photos.isNotEmpty() || total != null || failure != null)

    /** Read a page after another, keeping what passes, rather than laid out by days. */
    private var sifted = album != null || keeps != null
    /** Where the listing starts in the NAS's own, the newer days a time leaves out before it. */
    private var base = 0
    /** How far into the NAS's own a sifted listing has read, past [base]. */
    private var cursor = 0
    /** How many of the NAS's own the listing spans, where its days say. */
    private var span: Int? = null

    /** The timeline's days, then its first page; an album's first page. */
    suspend fun start() {
        if (started) return
        started = true
        if (album == null) allDays = known ?: try { nas.timelineDays(query) } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Without them it reads on a page at a time, as an album does.
            logWarning("Moments", "nas: timeline days not read", e)
            sifted = true
            emptyList()
        }
        if (allDays.isNotEmpty()) {
            val kept = range?.let { r -> allDays.filter { it.day in r } } ?: allDays
            base = range?.let { r -> allDays.takeWhile { it.day > r.endInclusive }.sumOf { it.count } } ?: 0
            span = kept.sumOf { it.count }
            if (!sifted) {
                days = kept
                photos.addAll(arrayOfNulls<NasPhoto>(span ?: 0))
            }
        }
        version++
        laid = true
        if (sifted) sift() else read(0)
    }

    /** The page after the last photo read, unless all are. */
    suspend fun more() {
        if (!laid || ended || failure != null) return
        if (sifted) sift() else read((photos.indexOfLast { it != null } + 1) / Nas.PAGE)
    }

    /** The page holding the photo at [index], unless it is read or being read. */
    suspend fun need(index: Int) {
        if (laid && !sifted && photos.getOrNull(index) == null) read(index / Nas.PAGE)
    }

    private suspend fun page(offset: Int, limit: Int) =
        if (album == null) nas.timeline(offset, limit, query) else nas.albumPhotos(album, offset, limit, query.kinds)

    private suspend fun read(page: Int) {
        if (page in reading) return
        val offset = page * Nas.PAGE
        reading += page
        try {
            val limit = span?.let { minOf(Nas.PAGE, it - offset) } ?: Nas.PAGE
            if (limit <= 0) return
            val (got, count) = page(base + offset, limit)
            // The page's slots taken out and its photos put in, in two changes: one change a photo,
            // the state list comparing itself up to that photo each time, cost a long list dearly.
            val at = minOf(offset, photos.size)
            if (got.isNotEmpty()) {
                photos.removeRange(at, minOf(offset + got.size, photos.size).coerceAtLeast(at))
                photos.addAll(at, got)
            }
            version++
            total = when {
                span != null -> span!!.coerceAtLeast(photos.size)
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

    /**
     * Reads on a page after another from where it stopped, keeping what the filter lets through,
     * until it has kept a screenful more or there is no more: the NAS's own end, the end of the
     * time's days, or, for an album newest first, a photo from before the time chosen.
     */
    private suspend fun sift() {
        if (SIFTING in reading) return
        reading += SIFTING
        try {
            var kept = 0
            while (kept < SIFT_KEEP && total == null) {
                val limit = span?.let { minOf(Nas.PAGE, it - cursor) } ?: Nas.PAGE
                if (limit <= 0) { total = photos.size; break }
                val (got, _) = page(base + cursor, limit)
                cursor += got.size
                var past = false
                got.forEach { photo ->
                    val day = photo.taken?.toLocalDate()
                    val inTime = range == null || span != null || (day != null && day in range)
                    if (range != null && span == null && day != null && day < range.start) past = true
                    if (inTime && keeps?.invoke(photo) != false) { photos += photo; kept++ }
                }
                version++
                if (got.size < limit || past) total = photos.size
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logWarning("Moments", "nas: photos not read", e)
            if (photos.isEmpty()) failure = (e as? NasException)?.failure ?: if (e is java.io.IOException) NasFailure.UNREACHABLE else NasFailure.OTHER
        } finally { reading -= SIFTING }
    }

    /** The day the timeline puts each of the first [count] photos under, where its days are read: one walk down the days, not one each. */
    fun daysOf(count: Int): List<LocalDate?> {
        val out = ArrayList<LocalDate?>(count)
        for (day in days) {
            repeat(minOf(day.count, count - out.size)) { out += day.day }
            if (out.size == count) return out
        }
        while (out.size < count) out += null
        return out
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
        span = span?.minus(gone.size)
        // Read already, they stood before where a sifted listing reads on.
        if (sifted) cursor -= gone.size
    }

    fun retry() {
        failure = null
        started = false
        laid = false
        days = emptyList()
        allDays = emptyList()
        photos.clear()
        base = 0
        cursor = 0
        span = null
        total = null
        sifted = album != null || keeps != null
        version++
    }

    private companion object {
        /** The page number a sifting read goes under in [reading]. */
        const val SIFTING = -1
        /** How many a sifting read keeps before it stops: a screenful and more. */
        const val SIFT_KEEP = 60
    }
}

/** What the picker says when the NAS couldn't be read. */
private fun nasReason(failure: NasFailure) = when (failure) {
    NasFailure.NOT_FOUND -> Res.string.nas_failed_not_found
    NasFailure.UNREACHABLE -> Res.string.nas_failed_unreachable
    NasFailure.SIGN_IN -> Res.string.nas_failed_sign_in
    NasFailure.OTHER -> Res.string.nas_failed_other
}
