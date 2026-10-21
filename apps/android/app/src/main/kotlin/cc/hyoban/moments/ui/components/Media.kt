package cc.hyoban.moments.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision

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
    placeholder: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    placeholderUrl: String? = null,
    /** How many times wider than it is laid out to fetch it: more than 1 while zoomed in. */
    detail: Float = 1f,
) {
    val context = LocalPlatformContext.current

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
        val request = remember(url, placeholderUrl, reduced) {
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A still with what it holds marked: a play button on a video, a live badge on a live
 * photo, in the corner [liveBadgeAlignment] names.
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
            MediaType.VIDEO -> PlayBadge(Modifier.align(Alignment.Center))
            MediaType.LIVE_PHOTO -> LiveBadge(Modifier.align(liveBadgeAlignment).padding(Space.s))
            MediaType.IMAGE -> Unit
        }
    }
}

@Composable
fun PlayBadge(modifier: Modifier = Modifier) {
    Icon(
        Icons.Rounded.PlayArrow,
        contentDescription = stringResource(R.string.action_play),
        tint = MediaOverlay.content,
        modifier = modifier.size(44.dp).clip(CircleShape).background(MediaOverlay.control).padding(Space.s),
    )
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(MediaOverlay.control).padding(horizontal = Space.s, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(Icons.Rounded.MotionPhotosOn, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.label_live), color = MediaOverlay.content, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A Moment's media laid out to fill a width: one as it is shaped, two side by side,
 * three as one large and two small, four or more as a two by two grid with the rest
 * counted on the last.
 */
@Composable
fun MediaMosaic(
    momentUid: String,
    media: List<MomentMedia>,
    urls: MediaUrls,
    onOpen: (Int) -> Unit,
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
            onClick = { onOpen(index) },
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
        3 -> Row(modifier.fillMaxWidth().aspectAtMost(1.5f, most), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            tile(0, Modifier.weight(2f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                tile(1, Modifier.weight(1f).fillMaxWidth())
                tile(2, Modifier.weight(1f).fillMaxWidth())
            }
        }
        else -> Column(modifier.fillMaxWidth().aspectAtMost(1f, most), verticalArrangement = Arrangement.spacedBy(GAP)) {
            for (row in 0..1) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    for (column in 0..1) {
                        val index = row * 2 + column
                        Box(Modifier.weight(1f).fillMaxHeight().clip(TILE)) {
                            tile(index, Modifier.fillMaxSize())
                            if (index == 3 && media.size > 4) {
                                val more = pluralStringResource(R.plurals.media_more, media.size - 4, media.size - 4)
                                val view = stringResource(R.string.action_view)
                                Box(
                                    Modifier.fillMaxSize().background(MediaOverlay.control)
                                        .clickable(onClickLabel = view) { onOpen(index) }
                                        .semantics { contentDescription = more },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+${media.size - 4}", color = MediaOverlay.content, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
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
    MediaStill(media, urls, modifier.size(size.dp).clip(MaterialTheme.shapes.medium))
}
