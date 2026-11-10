package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.data.local.MotionPhotos
import cc.hyoban.moments.ui.viewer.LiveMotion
import cc.hyoban.moments.ui.viewer.LiveMotionState
import cc.hyoban.moments.ui.viewer.VideoPlayback
import cc.hyoban.moments.ui.viewer.VideoState
import cc.hyoban.moments.ui.viewer.VideoWaiting
import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.R
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
import cc.hyoban.moments.ui.viewer.ZoomablePhoto
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * The phone's gallery, asked for once, with where its photos were taken; refused, [onFallback]
 * opens the system's documents picker instead. Its photos go in as they are, and go to its bin
 * with the system's own prompt.
 */
@Composable
internal fun rememberGallery(deletion: Deletion, onFallback: () -> Unit): GallerySource {
    val context = LocalContext.current
    val access = remember { mutableStateOf(LocalMedia.access(context)) }
    val ask = rememberPermissionAsk(
        LocalMedia.permissions + Manifest.permission.ACCESS_MEDIA_LOCATION,
        PermissionWords(
            AppIcons.Photos,
            stringResource(R.string.permission_gallery_title),
            stringResource(R.string.permission_gallery_body),
            stringResource(R.string.permission_gallery_secondary),
            listOf(
                PermissionFact(AppIcons.Image, stringResource(R.string.permission_gallery_local)),
                PermissionFact(AppIcons.Shield, stringResource(R.string.permission_photo_candidate)),
            ),
        ),
        isGranted = { LocalMedia.access(it) != GalleryAccess.NONE },
        onDecline = onFallback,
    ) { granted ->
        access.value = LocalMedia.access(context)
        if (!granted) onFallback()
    }
    LaunchedEffect(Unit) { if (access.value == GalleryAccess.NONE) ask() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access.value = LocalMedia.access(context) }
    // 更改: the system's choice of photos again, on Android 14 and later, or all of them.
    val reselect = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access.value = LocalMedia.access(context) }
    val items = remember(access.value) { if (access.value == GalleryAccess.NONE) flowOf(emptyList()) else LocalMedia.items(context) }
        .collectAsStateWithLifecycle(emptyList())

    // Moving to the bin: the system asks, so the app doesn't too.
    var trashed by remember { mutableStateOf<List<LocalItem>>(emptyList()) }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val gone = trashed.map { it.key }.toSet()
        trashed = emptyList()
        if (result.resultCode == Activity.RESULT_OK) deletion.gone(gone)
    }
    // One request for them all, so the system asks once.
    val moveToBin: (List<LocalItem>) -> Unit = { these ->
        runCatching {
            val request = MediaStore.createTrashRequest(context.contentResolver, these.map { it.uri }, true)
            trashed = these
            trash.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { Toast.makeText(context, R.string.picker_trash_failed, Toast.LENGTH_SHORT).show() }
    }
    // Kept for the page's life: the states it reads stay the same objects as their values change.
    return remember {
        GallerySource(context, items, access, reselect = { reselect.launch(LocalMedia.permissions) }, moveToBin = moveToBin)
    }
}

internal class GallerySource(
    private val context: Context,
    private val itemsState: State<List<LocalItem>>,
    private val accessState: State<GalleryAccess>,
    val reselect: () -> Unit,
    private val moveToBin: (List<LocalItem>) -> Unit,
) : PhotoSource {
    val items get() = itemsState.value
    val access get() = accessState.value
    val all = GalleryListing(this, null, null)

    @Composable
    override fun choices(): List<Choice> {
        val items = items
        val albums = remember(items) { LocalMedia.albums(items) }
        return listOf(Choice(stringResource(R.string.picker_all), items.size, AppIcons.Photos, "picker_all", all)) +
            albums.map { Choice(it.name, it.count, AppIcons.Folder, "picker_album_${it.id}", GalleryListing(this, it.id, it.name)) }
    }

    override val fetches = false

    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<Uri> =
        picks.map { LocalMedia.forMoment(context, (it as GalleryPick).item.uri) }

    override fun delete(picks: List<Pick>, fromViewer: Boolean) = moveToBin(picks.map { (it as GalleryPick).item })

    override val deleteTag = "picker_trash_chosen"
    override val deleteWords = R.string.picker_trash

    @Composable
    override fun Dialogs() {}
}

/** The whole gallery, or the album [albumId]. */
internal data class GalleryListing(val gallery: GallerySource, val albumId: Long?, val name: String?) : Listing {
    override val source get() = gallery

    @Composable
    override fun title() = name ?: stringResource(R.string.picker_all)

    @Composable
    override fun picks(): List<Pick> {
        val items = gallery.items
        return remember(items, albumId) {
            (if (albumId == null) items else items.filter { it.albumId == albumId }).map { GalleryPick(it, gallery) }
        }
    }

    @Composable
    override fun count(shown: List<Pick>) = shown.size

    @Composable
    override fun Body(shown: List<Pick>, grid: @Composable ((@Composable () -> Unit)?, (() -> Unit)?) -> Unit) =
        grid(if (gallery.access == GalleryAccess.CHOSEN) ({ Limited(gallery.items.size, gallery.reselect) }) else null, null)
}

/** The gallery's thumbnails, by the media store's id, shared by the grid and the viewer. */
private val localThumbs = LruCache<String, Bitmap>(300)

internal data class GalleryPick(val item: LocalItem, override val source: GallerySource) : Pick {
    override val key get() = item.key
    override val day get() = item.day

    override fun cachedThumb(size: Int): Bitmap? = localThumbs.get("$key@$size")

    override suspend fun thumbnail(context: Context, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(item.uri, Size(size, size), null) }.getOrNull()
    }?.also { localThumbs.put("$key@$size", it) }

    @Composable
    override fun badge(): String? {
        if (item.video) return item.duration?.let(::clock)
        val context = LocalContext.current
        val live by produceState(false, key) { value = withContext(Dispatchers.IO) { LocalMedia.isMotion(context, item) } }
        return if (live) stringResource(R.string.label_live) else null
    }

    @Composable
    override fun description() =
        stringResource(if (item.video) R.string.picker_video_description else R.string.camera_photo_description, item.name.orEmpty())

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

    /**
     * From the file itself. A video plays over a frame of it, silent until asked, and a motion
     * photo plays its clip once and again with its sound while held, both read from the media store.
     */
    @Composable
    override fun Page(active: Boolean, video: VideoState, onZoom: (Boolean) -> Unit) {
        val context = LocalContext.current
        // Only what the players read: MP4 and its kin.
        val plays = item.video && item.mime in PLAYED
        val live = remember(key) { LiveMotionState() }
        val clip by produceState<Long?>(null, key, active) {
            if (active && !item.video) value = withContext(Dispatchers.IO) { MotionPhotos.videoStart(context, item.uri) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val small by rememberThumb(this@GalleryPick)
            val large by rememberThumb(this@GalleryPick, size = if (item.video) 1440 else 320)
            val shape = if (item.width != null && item.height != null && item.height > 0) item.width.toFloat() / item.height else null
            ZoomablePhoto(
                shape ?: small?.let { it.width.toFloat() / it.height } ?: 1f, onZoom,
                onHold = if (clip != null) { held -> if (held) live.play(sound = true) else live.stop() } else null,
            ) {
                (if (item.video) large else small)?.let { Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (!item.video) AsyncImage(item.uri, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                clip?.let { LiveMotion(item.uri.toString(), active, live, Modifier.fillMaxSize(), start = it) }
                if (plays) VideoPlayback(item.uri.toString(), active, video, Modifier.fillMaxSize(), window = false)
            }
            if (plays) VideoWaiting(video) else if (item.video) Icon(AppIcons.Play, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(48.dp))
        }
    }
}

/** The videos the viewer plays itself; another kind shows a frame, as the players read MP4 alone. */
private val PLAYED = setOf("video/mp4", "video/quicktime", "video/3gpp")

private fun clock(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Only some of the gallery may be seen: says so, and [onChange] asks the system again. */
@Composable
private fun Limited(count: Int, onChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(bottom = Space.xs)
            .background(tokens.fill, AppTheme.shapes.medium).padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.picker_limited, count), style = AppTheme.typography.body, color = tokens.tx2, modifier = Modifier.weight(1f))
        TextButton(onClick = onChange, modifier = Modifier.testTag("picker_limited_change")) { Text(stringResource(R.string.picker_change)) }
    }
}
