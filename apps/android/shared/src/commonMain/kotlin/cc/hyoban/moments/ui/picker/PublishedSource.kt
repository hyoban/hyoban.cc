package cc.hyoban.moments.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.OffsetDateTime

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
    val source = remember { PublishedSource(container, context, device) }
    source.moments = moments
    return source
}

class PublishedSource(val container: AppContainer, val context: PlatformContext, val device: Device) : PhotoSource {
    var moments by mutableStateOf<List<MomentRecord>>(emptyList())

    override val home = PublishedListing(this)

    @Composable
    override fun choices(): List<Choice> {
        val picks = home.picks()
        return listOf(Choice(stringResource(Res.string.album_published), picks.size, AppIcons.Cloud, "picker_published", home))
    }

    override val fetches = false
    override suspend fun fetch(picks: List<Pick>, progress: (Int, Float) -> Unit): List<PhotoRef> = emptyList()
    override fun delete(picks: List<Pick>, fromViewer: Boolean) {}
    override val deleteTag = "published_delete"
    override val deleteWords = Res.string.camera_delete

    @Composable
    override fun Dialogs() {}

    /** The still of [media] at [size] pixels wide, as the timeline loads it. */
    suspend fun still(media: MomentMedia, size: Int): ImageBitmap? {
        val url = container.mediaUrls.still(media, size) ?: return null
        val request = ImageRequest.Builder(context).data(url).size(Size(size, size)).readable().build()
        return withContext(Dispatchers.IO) {
            (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap()
        }
    }
}

class PublishedListing(override val source: PublishedSource) : Listing {
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

/** A photo or video of [record]'s, under its Moment's [day]. */
data class PublishedPick(val media: MomentMedia, val record: MomentRecord, override val day: LocalDate?, override val source: PublishedSource) : Pick {
    override val key get() = "published/${record.uid}/${media.uid}"
    override val choosable get() = false
    override val deletable get() = false
    override val moment get() = record.uid
    override val nasLink get() = media.nas?.takeIf { source.container.nas.configured }

    override suspend fun thumbnail(size: Int): ImageBitmap? = source.still(media, size)

    override suspend fun shareOwn() = source.device.shareMedia(media, source.container.mediaUrls, "")

    @Composable
    override fun badge(): String? = if (media.type == MediaType.LIVE_PHOTO) stringResource(Res.string.label_live) else null

    @Composable
    override fun description() = media.alt.ifEmpty { stringResource(media.kindLabel()) }

    @Composable
    override fun place(): String? = null

    @Composable
    override fun rememberDetails(): PhotoDetails? = produceState<PhotoDetails?>(null, key) {
        val published = media.published
        val base = PhotoDetails(
            takenAt = (media.takenAt ?: record.occurredAt).let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
            name = published?.key?.substringAfterLast('/') ?: media.local?.uri?.substringAfterLast('/'),
            format = PhotoDetails.format(published?.contentType, published?.key),
            width = media.width, height = media.height, bytes = published?.bytes,
        )
        value = base
        // The NAS's original keeps all its EXIF, where it was taken too; a published original
        // keeps its EXIF but never that.
        val nas = source.container.nas
        val head = nasLink?.takeIf { media.type != MediaType.VIDEO }?.let { nas.head(nas.photo(it)) }
        value = when {
            head != null -> PhotoDetails.read(base.copy(name = nasLink?.name ?: base.name)) { head.inputStream() }
            media.type != MediaType.VIDEO && published?.original == true -> PhotoDetails.readRemote(base, source.container.mediaUrls.asset(published.key))
            else -> base
        }
    }.value

    /** Its still large over its small one; a video plays over it, a photo zooms in the whole. */
    @Composable
    override fun Page(active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
        val videos = LocalPickerVideos.current
        val urls = source.container.mediaUrls
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val small by rememberThumb(this@PublishedPick)
            val large by rememberThumb(this@PublishedPick, size = 1440)
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
            } else urls.still(media, 2560)?.let { full ->
                ZoomableOriginal(full, small, shape, media.alt, onZoom, onTap = onTap)
            }
        }
    }
}
