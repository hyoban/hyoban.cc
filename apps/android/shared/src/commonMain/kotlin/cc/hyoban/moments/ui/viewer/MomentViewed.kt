package cc.hyoban.moments.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalWindowInfo
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.NasLink
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.nas.NasProtocol
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.components.DrawnStills
import cc.hyoban.moments.ui.components.MediaStill
import cc.hyoban.moments.ui.components.shapeOf
import cc.hyoban.moments.ui.components.toImageBitmap
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.math.max

/**
 * A Moment's photo or video, or one picked for a Moment being written, as the viewer shows it: the
 * site's copy, or the file on the phone not yet published, drawn first from what the card already
 * has in memory, then at the screen's size and wider once zoomed into. One linked to the NAS, with
 * [nas] given, is looked at as the NAS keeps it, its big thumbnail and then its original in tiles,
 * live if the NAS's is: the site's may be smaller, 打码'd or a still. Only what is looked at
 * changes; what is shared or saved stays the site's. [motion] says where its clip or video is read
 * otherwise; [occurredAt] is its Moment's time, for a photo that doesn't say its own.
 */
class MomentViewed(
    val media: MomentMedia,
    private val container: AppContainer,
    private val nas: Nas? = null,
    private val motion: (MomentMedia) -> MotionSource? = { container.mediaUrls.motion(it)?.let(::MotionSource) },
    private val occurredAt: String? = null,
    override val key: String = media.uid,
) : ViewedMedia {
    /** The NAS's photo this is looked at as; a video stays the site's. */
    private val link: NasLink? = media.nas?.takeIf { nas != null && media.type != MediaType.VIDEO }

    override val type get() = if (link != null) MediaType.LIVE_PHOTO else media.type

    @Composable override fun description() = media.alt.ifEmpty { null }

    @Composable
    override fun rememberStill(active: Boolean, deep: Boolean): ViewedStill {
        val shape = shapeOf(media)
        val site = rememberSiteStill(shape, deep)
        val link = link ?: return site
        val nas = nas ?: return site
        val photo = remember(link) { nas.photo(link) }
        val big by produceState<ImageBitmap?>(null, link) { value = nas.thumbnail(photo, NasProtocol.BIG) }
        val original by produceState(nas.cached(photo), link, deep) {
            if (deep && value == null) value = nas.fetchOriginal(photo)
        }
        // The NAS not reached: the site's stands in, in the same place, so nothing starts over.
        if (big == null && original == null) return site
        return ViewedStill(shape, thumb = big ?: site.thumb, model = original ?: big)
    }

    /**
     * The site's still, or the file on the phone: the one the card drew, already in memory, at once,
     * so opening the viewer grows the photo out of its tile; then the screen's size, and three times
     * that once zoomed into, which the site serves from its largest.
     */
    @Composable
    private fun rememberSiteStill(shape: Float, deep: Boolean): ViewedStill {
        val context = LocalPlatformContext.current
        val drawn = remember(media.uid) {
            DrawnStills.of(media.uid)?.let { url -> SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(url))?.image?.toImageBitmap() }
        }
        val screen = LocalWindowInfo.current.containerSize.let { max(it.width, it.height) }.coerceAtLeast(1)
        val url = container.mediaUrls.still(media, if (deep) screen * DEEP else screen)
        var attempt by remember(media.uid) { mutableIntStateOf(0) }
        var failing by remember(media.uid) { mutableStateOf(false) }
        val request = remember(url, attempt) {
            url?.let {
                ImageRequest.Builder(context).data(it).memoryCacheKey(it)
                    .listener(onError = { _, _ -> failing = true }, onSuccess = { _, _ -> failing = false })
                    .build()
            }
        }
        return ViewedStill(
            shape,
            thumb = drawn,
            model = request,
            failed = if (failing) {
                {
                    failing = false
                    attempt++
                }
            } else {
                null
            },
        )
    }

    @Composable
    override fun rememberClip(active: Boolean): MotionSource? {
        val link = link
        val nas = nas
        if (link == null || nas == null) return remember(media) { if (media.type == MediaType.IMAGE) null else motion(media) }
        // The NAS's clip once it has said (the site's where it couldn't); none until then.
        return produceState<MotionSource?>(null, link, active) {
            if (active && value == null) value = nas.viewedClip(link, motion(media))
        }.value
    }

    @Composable override fun Thumb(modifier: Modifier) = MediaStill(media, container.mediaUrls, modifier)

    @Composable override fun rememberDetails() = container.mediaDetails.remember(media, occurredAt)

    override fun infoNote(details: PhotoDetails?): StringResource? = if (link != null) Res.string.viewer_nas_original else null

    override fun ahead(from: ViewedMedia, reading: ReadAhead) {
        container.mediaDetails.ahead(reading.scope, media, occurredAt)
        val link = link
        if (link != null) {
            reading.scope.launch { nas?.linkedAhead(link) }
        } else {
            container.mediaUrls.still(media, reading.screen)?.let { url ->
                SingletonImageLoader.get(reading.context).enqueue(ImageRequest.Builder(reading.context).data(url).memoryCacheKey(url).build())
            }
        }
    }

    private companion object {
        /** How much wider than the screen a still zoomed into is asked for. */
        const val DEEP = 3
    }
}
