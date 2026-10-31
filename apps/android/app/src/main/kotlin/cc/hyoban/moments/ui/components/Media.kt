package cc.hyoban.moments.ui.components

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import coil3.compose.AsyncImage
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.BoxScope
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The photos of a Moment meet edge to edge, as one picture across the card.
private val GAP = 0.dp
private val TILE = RectangleShape

/**
 * A media item's still at the size it is drawn: a photo, a live photo's still, a video's
 * poster. With [placeholderUrl], a still already in memory shows until this one loads.
 */
@Composable
fun MediaStill(
    media: MomentMedia,
    urls: MediaUrls,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = tokens.fill,
    placeholderUrl: String? = null,
    /** How many times wider than it is laid out to fetch it: more than 1 while zoomed in. */
    detail: Float = 1f,
    /** Drawn over it when it couldn't be fetched, with a way to try again; none leaves the placeholder. */
    failed: (@Composable BoxScope.(retry: () -> Unit) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    // A new request each time it is tried again, as Coil doesn't keep a failure to give back.
    var attempt by remember(media.uid) { mutableIntStateOf(0) }
    var failing by remember(media.uid) { mutableStateOf(false) }

    // The widest it has been drawn: a still growing or shrinking between screens keeps
    // the larger file it already has rather than fetching one for every size on the way.
    val widest = remember(media.uid) { IntArray(1) }

    // The placeholder is Coil's, not a fill under the still: it fades out as the still fades
    // in, so a loaded photo is painted once rather than over a colour no one sees.
    val fill = remember(placeholder) { ColorPainter(placeholder) }

    BoxWithConstraints(modifier) {
        widest[0] = maxOf(widest[0], with(LocalDensity.current) { (maxWidth * detail).roundToPx() })
        val url = urls.still(media, widest[0])
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else widest[0]
        // A still fades in over its placeholder, unless the phone has animations removed.
        val reduced = LocalReducedMotion.current
        val request = remember(url, placeholderUrl, reduced, attempt) {
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(url)
                // Its size known before it is laid out, a still already in memory draws in
                // the first frame, which a photo opening out of its tile needs.
                .size(widest[0].coerceAtLeast(1), height.coerceAtLeast(1))
                .precision(Precision.INEXACT)
                .crossfade(!reduced)
                // The same file too: the copy in memory was decoded for a smaller size than this, so
                // it is read again, and stands in meanwhile.
                .apply { if (placeholderUrl != null) placeholderMemoryCacheKey(placeholderUrl) }
                .build()
        }
        SideEffect { if (url != null && placeholderUrl == null) DrawnStills.note(media.uid, url) }
        AsyncImage(
            model = request,
            contentDescription = media.alt.ifEmpty { null },
            placeholder = fill,
            error = fill,
            fallback = fill,
            contentScale = contentScale,
            onError = if (failed != null) {
                { failing = true }
            } else {
                null
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (failing && failed != null) failed { failing = false; attempt++ }
    }
}

/**
 * The colours along a still's top and bottom edges drawn out over the space above and
 * below it, where a photo wider than its frame, centred across it, leaves some: the photo
 * seems to run on to the frame's edges rather than sit on a band of the page's colour.
 */
@Composable
fun MediaEdges(media: MomentMedia, urls: MediaUrls, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    var edges by remember(media.uid) { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    val shown by animateFloatAsState(if (edges != null) 1f else 0f, AppTheme.motionScheme.defaultEffectsSpec(), label = "edges")
    BoxWithConstraints(modifier) {
        // The file the still is drawn from, already on disk, read again small: each edge is
        // the band along it averaged into a few colours, blended into one another across the
        // width as they are stretched, so its colours run on without its detail.
        val url = urls.still(media, constraints.maxWidth)
        LaunchedEffect(url) {
            if (url == null || edges != null) return@LaunchedEffect
            val request = ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey("$url#edges")
                .size(EDGE_READ, EDGE_READ)
                .scale(Scale.FIT)
                .allowHardware(false)
                .build()
            val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
            edges = withContext(Dispatchers.Default) { edgeColors(bitmap, top = true) to edgeColors(bitmap, top = false) }
        }
        val shape = shapeOf(media)
        Canvas(Modifier.fillMaxSize()) {
            val (top, bottom) = edges ?: return@Canvas
            val width = size.width.roundToInt()
            val gap = ((size.height - minOf(size.width / shape, size.height)) / 2).roundToInt()
            if (gap <= 0) return@Canvas
            // A pixel into the photo too, so no seam shows where the two meet.
            val band = IntSize(width, gap + 1)
            drawImage(top, srcSize = IntSize(top.width, 1), dstSize = band, alpha = shown, filterQuality = FilterQuality.Low)
            drawImage(bottom, srcSize = IntSize(bottom.width, 1), dstOffset = IntOffset(0, size.height.roundToInt() - gap - 1), dstSize = band, alpha = shown, filterQuality = FilterQuality.Low)
        }
    }
}

/** How many pixels across a still is read at for its edges. */
private const val EDGE_READ = 48

/** How many colours an edge is averaged into across the width. */
private const val EDGE_COLOURS = 6

/** The band along a still's top or bottom edge, a quarter of its height, averaged into a row of [EDGE_COLOURS]. */
private fun edgeColors(bitmap: android.graphics.Bitmap, top: Boolean): ImageBitmap {
    val width = bitmap.width
    val rows = (bitmap.height / 4).coerceAtLeast(1)
    val pixels = IntArray(width * rows)
    bitmap.getPixels(pixels, 0, width, 0, if (top) 0 else bitmap.height - rows, width, rows)
    val row = IntArray(EDGE_COLOURS) { n ->
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (x in (n * width / EDGE_COLOURS) until maxOf((n + 1) * width / EDGE_COLOURS, n * width / EDGE_COLOURS + 1).coerceAtMost(width)) {
            for (y in 0 until rows) {
                val pixel = pixels[y * width + x]
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                count++
            }
        }
        if (count == 0) 0xFF000000.toInt() else (0xFF shl 24) or ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    }
    return android.graphics.Bitmap.createBitmap(row, EDGE_COLOURS, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * A still with what it holds marked: a play button on a video, a live badge on a live
 * photo, in the corner [liveBadgeAlignment] names. Among a Moment's photos in a small
 * mosaic, a video is marked the live photo's way instead, a small label at the bottom
 * start ([videoAsLabel]), as a play button there would sit over the next tile.
 */
@Composable
fun MediaTile(
    media: MomentMedia,
    urls: MediaUrls,
    modifier: Modifier = Modifier,
    liveBadgeAlignment: Alignment = Alignment.TopStart,
    sharedKey: String? = null,
    sharedShape: Shape = RectangleShape,
    /** Where it is among its Moment's media, as an index and a count, for a screen reader. */
    position: Pair<Int, Int>? = null,
    onClick: (() -> Unit)? = null,
    videoAsLabel: Boolean = false,
) {
    // Read as one thing to open: what it is, where it is among the rest, and what it shows,
    // rather than an unnamed button with "play" or "live" somewhere inside it.
    val kind = stringResource(media.kindLabel())
    val named = position?.let { (index, count) -> stringResource(R.string.media_position, kind, index + 1, count) } ?: kind
    val description = listOfNotNull(named, media.alt.ifEmpty { null }).joinToString(stringResource(R.string.separator_clause))
    val view = stringResource(R.string.action_view)
    Box(
        modifier.then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick).clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Image
                    onClick(label = view) { onClick(); true }
                }
            } else {
                Modifier
            },
        ),
    ) {
        MediaStill(media, urls, Modifier.fillMaxSize().sharedMedia(sharedKey, sharedShape))
        when (media.type) {
            MediaType.VIDEO -> if (videoAsLabel) VideoBadge(Modifier.align(Alignment.BottomStart).padding(Space.s)) else PlayBadge(Modifier.align(Alignment.Center))
            MediaType.LIVE_PHOTO -> LiveBadge(Modifier.align(liveBadgeAlignment).padding(Space.s))
            MediaType.IMAGE -> Unit
        }
    }
}

@Composable
fun PlayBadge(modifier: Modifier = Modifier) {
    Icon(
        AppIcons.Play,
        contentDescription = stringResource(R.string.action_play),
        tint = MediaOverlay.content,
        modifier = modifier.size(44.dp).clip(CircleShape).background(MediaOverlay.control).padding(Space.s),
    )
}

/** A video among photos: the live label's look, a play icon and the word. */
@Composable
fun VideoBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.height(20.dp).clip(CircleShape).background(MediaOverlay.control).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(AppIcons.Play, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.media_video), color = MediaOverlay.content, style = AppTheme.typography.caption)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        // The design's small label on a photo: 20 high, its icon 12.
        modifier.height(20.dp).clip(CircleShape).background(MediaOverlay.control).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(AppIcons.Live, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.label_live), color = MediaOverlay.content, style = AppTheme.typography.caption)
    }
}

/**
 * A Moment's media laid out to fill a width: one as it is shaped, two side by side,
 * three or more as one large and two small, past three with the rest counted on the
 * last. Without [onOpen] the tiles aren't tapped on their own, and a tap
 * goes to what holds them.
 */
@Composable
fun MediaMosaic(
    momentUid: String,
    media: List<MomentMedia>,
    urls: MediaUrls,
    onOpen: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tile = @Composable { index: Int, tileModifier: Modifier ->
        MediaTile(
            media[index],
            urls,
            tileModifier.clip(TILE).testTag("media_${momentUid}_$index"),
            sharedKey = mediaKey(momentUid, media[index].uid),
            sharedShape = TILE,
            position = index to media.size,
            onClick = onOpen?.let { open -> { open(index) } },
            videoAsLabel = true,
        )
    }

    // No taller than most of the screen on a phone on its side.
    val most = LocalMediaMaxHeight.current
    when (media.size) {
        0 -> Unit
        1 -> tile(0, modifier.fillMaxWidth().aspectAtMost(shapeOf(media[0]).coerceIn(0.8f, 1.8f), most))
        2 -> Row(modifier.fillMaxWidth().aspectAtMost(2f, most), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            tile(0, Modifier.weight(1f).fillMaxHeight())
            tile(1, Modifier.weight(1f).fillMaxHeight())
        }
        // Three and more: one large and two beside it; past three, the last says how many more.
        else -> Row(modifier.fillMaxWidth().aspectAtMost(1.5f, most), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            tile(0, Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                tile(1, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.weight(1f).fillMaxWidth().clip(TILE)) {
                    tile(2, Modifier.fillMaxSize())
                    if (media.size > 3) {
                        val more = pluralStringResource(R.plurals.media_more, media.size - 3, media.size - 3)
                        val view = stringResource(R.string.action_view)
                        Box(
                            Modifier.fillMaxSize().background(MediaOverlay.control)
                                .then(if (onOpen != null) Modifier.clickable(onClickLabel = view) { onOpen(2) } else Modifier)
                                .semantics { contentDescription = more },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+${media.size - 3}", color = MediaOverlay.content, style = AppTheme.typography.title)
                        }
                    }
                }
            }
        }
    }
}

/** What the media is called: a photo, a live photo or a video. */
fun MomentMedia.kindLabel(): Int = when (type) {
    MediaType.IMAGE -> R.string.media_photo
    MediaType.LIVE_PHOTO -> R.string.media_live
    MediaType.VIDEO -> R.string.media_video
}

/** Width over height, 4:3 when the record doesn't say. */
fun shapeOf(media: MomentMedia): Float = (media.width ?: 4).toFloat() / (media.height ?: 3)

/** A small rounded still, for rows and covers. */
@Composable
fun Thumbnail(media: MomentMedia, urls: MediaUrls, size: Int, modifier: Modifier = Modifier) {
    MediaStill(media, urls, modifier.size(size.dp).clip(AppTheme.shapes.medium))
}
