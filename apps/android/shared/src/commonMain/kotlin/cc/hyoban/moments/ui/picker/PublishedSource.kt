package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.ui.components.BadgeKind
import cc.hyoban.moments.ui.components.badge
import cc.hyoban.moments.ui.components.readStill
import cc.hyoban.moments.ui.viewer.MomentViewed
import cc.hyoban.moments.ui.viewer.ReadAhead
import cc.hyoban.moments.ui.viewer.ViewedMedia

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

    /** The grid's stills read lately, by their address, so scrolling back to one shows it at once. */
    private val stills = Lru(STILLS)

    private class Lru(val most: Int) : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > most
    }

    /** The still of [media] at [size] pixels wide if it was read lately, without waiting. */
    fun cachedStill(media: MomentMedia, size: Int): ImageBitmap? =
        container.mediaUrls.still(media, size)?.let { url -> synchronized(stills) { stills[url] } }

    /** The still of [media] at [size] pixels wide, as the timeline loads it. */
    suspend fun still(media: MomentMedia, size: Int): ImageBitmap? {
        val url = container.mediaUrls.still(media, size) ?: return null
        synchronized(stills) { stills[url] }?.let { return it }
        return withContext(Dispatchers.IO) { container.mediaUrls.readStill(context, media, size)?.toImageBitmap() }
            ?.also { synchronized(stills) { stills[url] = it } }
    }

    /** Reads [pick]'s tile still ahead; what the viewer shows of it is read ahead as a Moment's is. */
    fun fetchAhead(pick: PublishedPick) {
        scope.launch { still(pick.media, SMALL) }
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

    /** As a Moment's viewer shows it, looked at as the NAS keeps it where it is linked to it. */
    private val viewed by lazy {
        MomentViewed(media, source.container, source.container.nas.takeIf { it.configured }, occurredAt = record.occurredAt, key = key)
    }

    override val type get() = viewed.type

    @Composable override fun rememberStill(active: Boolean, deep: Boolean) = viewed.rememberStill(active, deep)
    @Composable override fun rememberClip(active: Boolean) = viewed.rememberClip(active)
    override fun infoNote(details: PhotoDetails?) = viewed.infoNote(details)
    override fun cachedThumb(size: Int): ImageBitmap? = source.cachedStill(media, size)

    /** Its tile's still and what the viewer turns to, read ahead so a swipe on shows them at once. */
    override fun ahead(from: ViewedMedia, reading: ReadAhead) {
        source.fetchAhead(this)
        viewed.ahead(from, reading)
    }

    override suspend fun shareOwn() = source.device.shareMedia(media, source.container.mediaUrls, "")

    @Composable
    override fun badge(): BadgeKind? = media.badge()

    @Composable
    override fun description() = media.alt.ifEmpty { stringResource(media.kindLabel()) }

    @Composable
    override fun place(): String? = null

    @Composable
    override fun rememberDetails(): PhotoDetails? = viewed.rememberDetails()
}

/** The grid's stills. */
private const val SMALL = 320

/** How many NAS originals are read at once while looking for the lesser. */
private const val AT_ONCE = 6

/** How many small stills are kept at hand. */
private const val STILLS = 300

/** A published photo says what it is, and its Moment where it was. */
private val PUBLISHED_FACETS = setOf(Facet.KIND, Facet.TIME, Facet.PLACE)
