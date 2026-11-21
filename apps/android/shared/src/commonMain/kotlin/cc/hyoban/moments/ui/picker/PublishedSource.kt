package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.Device
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.components.toImageBitmap
import cc.hyoban.moments.ui.components.kindLabel
import cc.hyoban.moments.ui.components.readable
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.viewer.PhotoDetails
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import cc.hyoban.moments.ui.viewer.NasLinkedStill
import cc.hyoban.moments.ui.viewer.linkedAhead
import cc.hyoban.moments.ui.viewer.viewedClip
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/**
 * 已发布 in 相册's title: every photo and video of the Moments, the private ones too and, in 演示模式,
 * only what is shown, newest Moment first, each under its Moment's day. They are the site's (or
 * the phone's, not published yet): looked at, shared and taken back to their Moment, never chosen
 * or deleted here.
 */
@Composable
fun rememberPublished(container: AppContainer): PublishedSource {
    val moments by container.shownMoments.collectAsStateWithLifecycle()
    val context = LocalPlatformContext.current
    val device = LocalDevice.current
    val scope = rememberCoroutineScope()
    val source = remember { PublishedSource(container, context, device, scope) }
    source.moments = moments
    return source
}

class PublishedSource(val container: AppContainer, val context: PlatformContext, val device: Device, private val scope: CoroutineScope) : PhotoSource {
    var moments by mutableStateOf<List<MomentRecord>>(emptyList())

    override val home = PublishedListing(this)
    val lesserListing = LesserListing(this)

    @Composable
    override fun choices(): List<Choice> {
        val picks = home.picks()
        val published = Choice(stringResource(Res.string.album_published), picks.size, AppIcons.Cloud, "picker_published", home)
        if (!container.nas.configured) return listOf(published)
        return listOf(published, Choice(stringResource(Res.string.album_lesser), lesser?.size, AppIcons.Nas, "picker_lesser", lesserListing))
    }

    // 可换原图: the published photos whose copy on the site is less than the NAS's original ------

    /** The photos found less than the NAS's original, by their uid; null before they are first looked at. */
    var lesser by mutableStateOf<Set<String>?>(null)
        private set

    /** How many photos linked to the NAS have been looked at, of how many, while they are. */
    var looking by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var lookingJob: kotlinx.coroutines.Job? = null

    /**
     * Looks at every published photo linked to the NAS ([lesserThanNas]), [AT_ONCE] at a time: one
     * the site optimized is less at once; one kept as uploaded is read from the NAS's original's
     * EXIF, which is kept on the device, so looking again asks the NAS for nothing new.
     */
    fun lookForLesser() {
        if (lookingJob?.isActive == true) return
        val linked = moments.flatMap { record -> record.media.filter { it.nas != null && it.published != null } }
        lookingJob = scope.launch {
            val found = mutableSetOf<String>()
            var done = 0
            looking = 0 to linked.size
            val gate = kotlinx.coroutines.sync.Semaphore(AT_ONCE)
            kotlinx.coroutines.coroutineScope {
                linked.forEach { media ->
                    launch {
                        val less = gate.withPermit { container.lesserThanNas(media) }
                        if (less) found += media.uid
                        looking = ++done to linked.size
                        lesser = found.toSet()
                    }
                }
            }
            lesser = found.toSet()
            looking = null
        }
    }

    override val fetches = false
    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<PhotoRef> = emptyList()
    override fun delete(picks: List<Pick>, fromViewer: Boolean) {}
    override val deleteTag = "published_delete"
    override val deleteWords = Res.string.camera_delete

    @Composable
    override fun Dialogs() {}

    /**
     * The stills read lately, by their address, so turning back or on to one shows it at once: many
     * of the grid's small ones, a few of the viewer's large.
     */
    private val stills = Lru(STILLS)
    private val largeStills = Lru(LARGE_STILLS)
    private fun kept(size: Int) = if (size > SMALL) largeStills else stills

    private class Lru(val most: Int) : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > most
    }

    /** The still of [media] at [size] pixels wide if it was read lately, without waiting. */
    fun cachedStill(media: MomentMedia, size: Int): ImageBitmap? =
        container.mediaUrls.still(media, size)?.let { url -> kept(size).let { synchronized(it) { it[url] } } }

    /** The still of [media] at [size] pixels wide, as the timeline loads it. */
    suspend fun still(media: MomentMedia, size: Int): ImageBitmap? {
        val url = container.mediaUrls.still(media, size) ?: return null
        val kept = kept(size)
        synchronized(kept) { kept[url] }?.let { return it }
        val request = ImageRequest.Builder(context).data(url).size(Size(size, size)).readable().build()
        return withContext(Dispatchers.IO) {
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap()
        }?.also { synchronized(kept) { kept[url] = it } }
    }

    /** [pick]'s details if they were read already, without waiting. */
    fun cachedDetails(pick: PublishedPick): PhotoDetails? = container.mediaDetails.cached(pick.media)

    /** What [pick]'s record says of it, shown while its EXIF is read. */
    fun baseDetails(pick: PublishedPick): PhotoDetails = container.mediaDetails.base(pick.media, pick.record.occurredAt)

    /** [pick]'s details whole, read once for every viewer ([cc.hyoban.moments.ui.viewer.MediaDetails]). */
    suspend fun details(pick: PublishedPick): PhotoDetails = container.mediaDetails.read(pick.media, pick.record.occurredAt)

    /** Reads [pick]'s stills ahead, the viewer's two and the full one it zooms into, and its details, for a swipe on to show them at once. */
    fun fetchAhead(pick: PublishedPick) {
        val media = pick.media
        container.mediaDetails.ahead(scope, media, pick.record.occurredAt)
        scope.launch {
            still(media, SMALL)
            // One linked to the NAS is looked at as the NAS keeps it: its big thumbnail, not the site's large stills.
            val link = media.nas?.takeIf { container.nas.configured && media.type != MediaType.VIDEO }
            if (link != null) return@launch container.nas.linkedAhead(link)
            still(media, LARGE)
            container.mediaUrls.still(media, FULL)?.let { url -> SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(url).build()) }
        }
    }
}

class PublishedListing(override val source: PublishedSource) : Listing {
    override val facets get() = PUBLISHED_FACETS

    @Composable
    override fun title() = stringResource(Res.string.album_published)

    @Composable
    override fun picks(): List<Pick> {
        val moments = source.moments
        return remember(moments) {
            moments.sortedByDescending { it.occurredAt }.flatMap { moment ->
                val day = runCatching { LocalDate.parse(moment.dateKey) }.getOrNull()
                moment.media.map { PublishedPick(it, moment, day, source) }
            }
        }
    }

    @Composable
    override fun count(shown: List<Pick>) = shown.size

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) = grid(null, null)
}

/** 可换原图 in 相册's title: the published photos whose copy on the site is less than the NAS's original, each to be put back from its Moment's editor. */
class LesserListing(override val source: PublishedSource) : Listing {
    override val facets get() = PUBLISHED_FACETS

    @Composable
    override fun title() = stringResource(Res.string.album_lesser)

    @Composable
    override fun picks(): List<Pick> {
        val all = source.home.picks()
        val lesser = source.lesser.orEmpty()
        return remember(all, lesser) { all.filter { (it as PublishedPick).media.uid in lesser } }
    }

    override fun opened() = source.lookForLesser()

    @Composable
    override fun count(shown: List<Pick>) = shown.size

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) {
        LaunchedEffect(Unit) { source.lookForLesser() }
        val looking = source.looking
        grid(looking?.let { (done, total) ->
            {
                cc.hyoban.moments.ui.design.Text(
                    stringResource(Res.string.album_lesser_looking, done, total),
                    style = cc.hyoban.moments.ui.design.AppTheme.typography.caption,
                    color = cc.hyoban.moments.ui.theme.tokens.tx2,
                    modifier = Modifier.padding(horizontal = cc.hyoban.moments.ui.theme.Space.gutter, vertical = cc.hyoban.moments.ui.theme.Space.s),
                )
            }
        }, null)
    }
}

/**
 * Whether [media]'s published copy is less than the NAS's original it is linked to: smaller, by its
 * long side, than the NAS's original says it is (read from its start, kept on the device once read),
 * whether the site kept it as uploaded or optimized it. Where the NAS's says nothing, one the site
 * optimized is taken to be less and one kept as uploaded not.
 */
suspend fun AppContainer.lesserThanNas(media: MomentMedia): Boolean {
    val published = media.published ?: return false
    val link = media.nas?.takeIf { nas.configured } ?: return false
    val unknown = published.original != true
    if (media.type == MediaType.VIDEO) return unknown
    val head = nas.head(nas.photo(link)) ?: return unknown
    val read = PhotoDetails.read(PhotoDetails()) { head.inputStream() }
    val longest = maxOf(read.width ?: 0, read.height ?: 0)
    val shown = maxOf(published.width ?: media.width ?: 0, published.height ?: media.height ?: 0)
    if (longest <= 0 || shown <= 0) return unknown
    return longest > shown
}

/** A photo or video of [record]'s, under its Moment's [day]. */
data class PublishedPick(val media: MomentMedia, val record: MomentRecord, override val day: LocalDate?, override val source: PublishedSource) : Pick {
    override val key get() = "published/${record.uid}/${media.uid}"
    override val choosable get() = false
    override val deletable get() = false
    override val moment get() = record.uid
    override val nasLink get() = media.nas?.takeIf { source.container.nas.configured }
    override val kind get() = when (media.type) {
        MediaType.VIDEO -> PhotoKind.VIDEO
        MediaType.LIVE_PHOTO -> PhotoKind.LIVE
        MediaType.IMAGE -> PhotoKind.PHOTO
    }
    // Where its Moment was: a published photo has lost its own.
    override val hasPlace get() = record.places.isNotEmpty()

    override suspend fun thumbnail(size: Int): ImageBitmap? = source.still(media, size)

    /** A photo linked to the NAS is looked at as the NAS keeps it, live if the NAS's is; a video stays the site's. */
    private val viewedLink get() = nasLink?.takeIf { media.type != MediaType.VIDEO }

    /** The site's clip of a live photo, published beside its still. */
    private val publishedClip get() =
        if (media.type == MediaType.LIVE_PHOTO) source.container.mediaUrls.motion(media)?.let { cc.hyoban.moments.ui.viewer.MotionSource(it) } else null

    /** A live photo's clip: the NAS's for one linked to it, once its page is first shown, else the site's. */
    @Composable
    override fun rememberMotion(active: Boolean): cc.hyoban.moments.ui.viewer.MotionSource? {
        val link = viewedLink ?: return publishedClip
        return produceState<cc.hyoban.moments.ui.viewer.MotionSource?>(null, key, active) {
            if (active && value == null) value = source.container.nas.viewedClip(link, publishedClip)
        }.value
    }

    override fun infoNote(details: PhotoDetails?) = if (viewedLink != null) Res.string.viewer_nas_original else null
    override fun cachedThumb(size: Int): ImageBitmap? = source.cachedStill(media, size)

    /** The next one's stills read ahead, so turning to it shows it at once. */
    override fun settled(next: Pick?) {
        (next as? PublishedPick)?.let { source.fetchAhead(it) }
    }

    override suspend fun shareOwn() = source.device.shareMedia(media, source.container.mediaUrls, "")

    @Composable
    override fun badge(): String? = if (media.type == MediaType.LIVE_PHOTO) stringResource(Res.string.label_live) else null

    @Composable
    override fun description() = media.alt.ifEmpty { stringResource(media.kindLabel()) }

    @Composable
    override fun place(): String? = null

    @Composable
    override fun rememberDetails(): PhotoDetails? = produceState(source.cachedDetails(this), key) {
        if (value != null) return@produceState
        value = source.baseDetails(this@PublishedPick)
        value = source.details(this@PublishedPick)
    }.value

    /** Its still large over its small one; a video plays over it, a photo zooms in the whole. */
    @Composable
    override fun Page(active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
        val videos = LocalPickerVideos.current
        val urls = source.container.mediaUrls
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val small by rememberThumb(this@PublishedPick, size = SMALL)
            // One linked to the NAS shows the NAS's own over its small one, not the site's large.
            val large = if (viewedLink == null) rememberThumb(this@PublishedPick, size = LARGE).value else null
            val shown = large ?: small
            val shape = if (media.width != null && media.height != null && media.height > 0) media.width.toFloat() / media.height
                else shown?.let { it.width.toFloat() / it.height } ?: 1f
            val motion = if (media.type == MediaType.VIDEO) urls.motion(media) else null
            if (motion != null) {
                ZoomablePhoto(shape, onZoom, onTap = onTap) {
                    shown?.let { Image(it, contentDescription = media.alt, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    videos.Play(key, motion, emptyMap(), active, Modifier.fillMaxSize())
                }
                videos.Waiting(key)
            } else {
                val live = LocalPageLive.current
                val site: @Composable () -> Unit = {
                    urls.still(media, FULL)?.let { full ->
                        ZoomableOriginal(full, small, shape, media.alt, onZoom, onHold = live?.hold, onTap = onTap) { live?.Over(active) }
                    }
                }
                val link = viewedLink
                if (link != null) {
                    NasLinkedStill(source.container.nas, link, shape, media.alt, onZoom, onTap, live?.hold, { live?.Over(active) }, fallback = site)
                } else {
                    site()
                }
            }
        }
    }
}

/** The viewer's stills: the grid's, the one over it, and the one zoomed into. */
private const val SMALL = 320
private const val LARGE = 1440
private const val FULL = 2560

/** How many NAS originals are read at once while looking for the lesser. */
private const val AT_ONCE = 6

/** How many small stills, and large, are kept at hand. */
private const val STILLS = 300
private const val LARGE_STILLS = 4

/** A published photo says what it is, and its Moment where it was. */
private val PUBLISHED_FACETS = setOf(Facet.KIND, Facet.TIME, Facet.PLACE)
