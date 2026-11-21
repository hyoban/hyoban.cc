package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.ui.components.BadgeKind

import cc.hyoban.moments.data.local.videoStart
import cc.hyoban.moments.res.*
import cc.hyoban.moments.data.local.MotionPhotos
import cc.hyoban.moments.ui.viewer.VideoPlayback
import cc.hyoban.moments.ui.viewer.VideoWaiting
import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.components.PermissionWords
import cc.hyoban.moments.ui.components.rememberPermissionAsk
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.PermissionFact
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.TextButton
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.viewer.PhotoDetails
import cc.hyoban.moments.ui.viewer.readLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The phone's [gallery] on a page: asked for once, with where its photos were taken; refused,
 * [onFallback] opens the system's documents picker instead. The gallery is one for the app (相册,
 * 选照片 and 地图's photos read the same list); each page showing it lends it the system's prompts,
 * to choose its photos again and to move some to its bin, [deletion] told of what went.
 */
@Composable
internal fun rememberGallery(gallery: GallerySource, deletion: Deletion, onFallback: () -> Unit): GallerySource {
    val ask = rememberPermissionAsk(
        LocalMedia.permissions + Manifest.permission.ACCESS_MEDIA_LOCATION,
        PermissionWords(
            AppIcons.Photos,
            stringResource(Res.string.permission_gallery_title),
            stringResource(Res.string.permission_gallery_body),
            stringResource(Res.string.permission_gallery_secondary),
            listOf(
                PermissionFact(AppIcons.Image, stringResource(Res.string.permission_gallery_local)),
                PermissionFact(AppIcons.Shield, stringResource(Res.string.permission_photo_candidate)),
            ),
        ),
        isGranted = { LocalMedia.access(it) != GalleryAccess.NONE },
        onDecline = onFallback,
    ) { granted ->
        gallery.checkAccess()
        if (!granted) onFallback()
    }
    LaunchedEffect(Unit) { if (gallery.access == GalleryAccess.NONE) ask() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gallery.checkAccess() }
    // 更改: the system's choice of photos again, on Android 14 and later, or all of them.
    val reselect = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { gallery.checkAccess() }

    // Moving to the bin: the system asks, so the app doesn't too.
    var trashed by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val gone = trashed.map { it.key }.toSet()
        trashed = emptyList()
        if (result.resultCode == Activity.RESULT_OK) deletion.gone(gone)
    }
    val context = LocalContext.current
    // One request for them all, so the system asks once.
    val moveToBin: (List<LocalItem>) -> Unit = { these ->
        runCatching {
            val request = MediaStore.createTrashRequest(context.contentResolver, these.map { it.uri }, true)
            trashed = these
            trash.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { Toast.makeText(context, textOf(Res.string.picker_trash_failed), Toast.LENGTH_SHORT).show() }
    }
    DisposableEffect(gallery) {
        val leave = gallery.attach(GallerySource.Page(reselect = { reselect.launch(LocalMedia.permissions) }, moveToBin = moveToBin))
        onDispose(leave)
    }
    return gallery
}

/**
 * The phone's gallery, one for the app: its photos read once and followed as they change while any
 * page shows them, without the albums hidden in 相簿, and its motion photos listed once for them all.
 */
internal class GallerySource(val context: Context, private val settings: cc.hyoban.moments.Settings) : PhotoSource {
    /** What a page showing the gallery lends it: the system's prompts, opened from that page. */
    class Page(val reselect: () -> Unit, val moveToBin: (List<LocalItem>) -> Unit)

    private var accessState: GalleryAccess by mutableStateOf(LocalMedia.access(context))
    val access: GalleryAccess get() = accessState

    /** Reads again what the gallery may be seen of, after the system has been asked. */
    fun checkAccess() {
        accessState = LocalMedia.access(context)
    }

    private var read: List<LocalItem> by mutableStateOf(emptyList())
    private val shown: State<List<LocalItem>> = derivedStateOf { read.filterNot { hiddenAlbum(settings, it) } }

    /** Its photos and videos, without the albums hidden in 相簿, everywhere the gallery is shown. */
    val items: List<LocalItem> get() = shown.value

    private val pages = mutableListOf<Page>()
    private val scope = kotlinx.coroutines.MainScope()
    private var following: kotlinx.coroutines.Job? = null

    /**
     * [page] showing the gallery, until the returned call: the photos are read and followed while
     * any page shows them, and the prompts are the last page's, the one in front.
     */
    fun attach(page: Page): () -> Unit {
        pages += page
        if (following == null) following = scope.launch {
            androidx.compose.runtime.snapshotFlow { access }.collectLatest { access ->
                if (access == GalleryAccess.NONE) read = emptyList() else LocalMedia.items(context).collect { read = it }
            }
        }
        return {
            pages -= page
            if (pages.isEmpty()) following?.cancel().also { following = null }
        }
    }

    /** Asks the system for its choice of photos again, from the page in front. */
    fun reselect() {
        pages.lastOrNull()?.reselect?.invoke()
    }

    /** The motion photos among [items], by id, once listed; null until then. */
    var motion by mutableStateOf<Set<Long>?>(null)
        private set
    private var motionOf: List<LocalItem>? = null

    /** Lists the motion photos for the tiles' badges and the filter, in one query, again as the gallery changes. */
    suspend fun readMotion() {
        val now = items
        if (motionOf === now) return
        motionOf = now
        motion = withContext(Dispatchers.IO) { LocalMedia.motionIds(context) }
    }
    val all = GalleryListing(this, null, null)
    override val home get() = all

    @Composable
    override fun choices(): List<Choice> {
        val items = items
        val albums = remember(items) { LocalMedia.albums(items) }
        return listOf(Choice(stringResource(Res.string.picker_all), items.size, AppIcons.Photos, "picker_all", all)) +
            albums.map { Choice(it.name, it.count, AppIcons.Folder, "picker_album_${it.id}", GalleryListing(this, it.id, it.name)) }
    }

    override val fetches = false

    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<Uri> =
        picks.map { LocalMedia.forMoment(context, (it as GalleryPick).item.uri) }

    override fun delete(picks: List<Pick>, fromViewer: Boolean) {
        pages.lastOrNull()?.moveToBin?.invoke(picks.map { (it as GalleryPick).item })
    }

    override val deleteTag = "picker_trash_chosen"
    override val deleteWords = Res.string.picker_trash

    @Composable
    override fun Dialogs() {}
}

/** The whole gallery, or the album [albumId]. */
internal data class GalleryListing(val gallery: GallerySource, val albumId: Long?, val name: String?) : Listing {
    override val source get() = gallery

    @Composable
    override fun title() = name ?: stringResource(Res.string.picker_all)

    @Composable
    override fun picks(): List<Pick> {
        val items = gallery.items
        return remember(items, albumId) {
            (if (albumId == null) items else items.filter { it.albumId == albumId }).map { GalleryPick(it, gallery) }
        }
    }

    override val facets get() = GALLERY_FACETS

    /** On the device, the motion photos listed among them, as their XMP says, for the badges and the filter. */
    @Composable
    override fun shown(filter: PhotoFilter): List<Pick> {
        val items = gallery.items
        LaunchedEffect(items) { gallery.readMotion() }
        val all = picks()
        val motion = gallery.motion
        return remember(all, filter, motion) { if (filter.active) all.filter(filter::matches) else all }
    }

    @Composable
    override fun count(shown: List<Pick>) = shown.size

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) =
        grid(if (gallery.access == GalleryAccess.CHOSEN) ({ Limited(gallery.items.size, gallery::reselect) }) else null, null)
}

/** The phone's gallery says what each photo is, and when it was taken; where, only once each is read, which it isn't for this. */
private val GALLERY_FACETS = setOf(Facet.KIND, Facet.TIME)

/** The gallery's thumbnails, by the media store's id, shared by the grid and the viewer. */
private val localThumbs = LruCache<String, ImageBitmap>(300)

internal data class GalleryPick(val item: LocalItem, override val source: GallerySource) : Pick {
    override val key get() = item.key
    override val day get() = item.day
    override val kind get() = when {
        item.video -> PhotoKind.VIDEO
        source.motion?.contains(item.id) == true -> PhotoKind.LIVE
        else -> PhotoKind.PHOTO
    }

    override fun cachedThumb(size: Int): ImageBitmap? = localThumbs.get("$key@$size")

    override suspend fun thumbnail(size: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { source.context.contentResolver.loadThumbnail(item.uri, Size(size, size), null).asImageBitmap() }.getOrNull()
    }?.also { localThumbs.put("$key@$size", it) }

    /** A video; else a motion photo where the media store says so, or until it has said. */
    override val type get() = when {
        item.video -> MediaType.VIDEO
        source.motion?.contains(item.id) == false -> MediaType.IMAGE
        else -> MediaType.LIVE_PHOTO
    }

    /** Only what the players read: MP4 and its kin; another kind shows a frame. */
    override val playable get() = item.mime in cc.hyoban.moments.ui.viewer.PLAYED_VIDEOS

    /**
     * A video plays from the file, silent until asked; a motion photo's clip is read from the media
     * store where it starts after its still, played once and again with its sound while held.
     */
    @Composable
    override fun rememberClip(active: Boolean): cc.hyoban.moments.ui.viewer.MotionSource? {
        val context = LocalContext.current
        if (item.video) return remember(key) { if (playable) cc.hyoban.moments.ui.viewer.MotionSource(item.uri.toString()) else null }
        return produceState<cc.hyoban.moments.ui.viewer.MotionSource?>(null, key, active) {
            if (!active || value != null) return@produceState
            value = withContext(Dispatchers.IO) { MotionPhotos.videoStart(context, item.uri) }
                ?.let { cc.hyoban.moments.ui.viewer.MotionSource(item.uri.toString(), it) }
        }.value
    }

    /** From the file itself, read in tiles as it zooms, over its thumbnail; a video's frame, large. */
    @Composable
    override fun rememberStill(active: Boolean, deep: Boolean): cc.hyoban.moments.ui.viewer.ViewedStill {
        val small by rememberThumb(this)
        val large = if (item.video) rememberThumb(this, size = 1440).value else null
        val shape = if (item.width != null && item.height != null && item.height > 0) item.width.toFloat() / item.height
            else small?.let { it.width.toFloat() / it.height } ?: 1f
        return cc.hyoban.moments.ui.viewer.ViewedStill(shape, thumb = large ?: small, model = if (item.video) null else item.uri)
    }

    /** A video and its length, or a motion photo as the media store says, listed once for all of them. */
    @Composable
    override fun badge(): BadgeKind? = when {
        item.video -> BadgeKind.Video(item.duration)
        source.motion?.contains(item.id) == true -> BadgeKind.Live
        else -> null
    }

    @Composable
    override fun description() =
        stringResource(if (item.video) Res.string.picker_video_description else Res.string.camera_photo_description, item.name.orEmpty())

    @Composable
    override fun place() = item.album

    @Composable
    override fun rememberDetails(): PhotoDetails? {
        val context = LocalContext.current
        return produceState<PhotoDetails?>(null, key) {
            val base = PhotoDetails(
                takenAt = Instant.ofEpochMilli(item.taken).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                name = item.name, format = PhotoDetails.format(item.mime, item.name),
                width = item.width, height = item.height, bytes = item.bytes, album = item.album,
            )
            value = base
            value = if (item.video) base else PhotoDetails.readLocal(context, base, LocalMedia.forMoment(context, item.uri))
        }.value
    }
}

/** Only some of the gallery may be seen: says so, and [onChange] asks the system again. */
@Composable
private fun Limited(count: Int, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.xs)
            .background(tokens.fill, AppTheme.shapes.medium).padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.picker_limited, count), style = AppTheme.typography.body, color = tokens.tx2, modifier = Modifier.weight(1f))
        TextButton(onClick = onChange, modifier = Modifier.testTag("picker_limited_change")) { Text(stringResource(Res.string.picker_change)) }
    }
}
