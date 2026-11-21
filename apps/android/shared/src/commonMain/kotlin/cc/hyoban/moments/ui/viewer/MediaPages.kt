package cc.hyoban.moments.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.ui.Device
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * What moves of a viewer's items, each its own by key so the bars work the one shown, and the clips
 * their pages have found. One that may turn out live has its motion from the start and plays once
 * its clip is known.
 */
@Stable
class ViewerMotions(private val device: Device, private val inDialog: Boolean) {
    private val motions = mutableMapOf<String, ViewerMotion?>()
    private val clips = mutableStateMapOf<String, MotionSource>()

    /**
     * [item]'s motion, whether or not it has anything to play yet; null for a still. Its own for what
     * it is now: a photo picked turns out live once it is read, and a live one made a still stops.
     */
    fun of(item: ViewedMedia): ViewerMotion? = motions.getOrPut("${item.key}/${item.type}") { device.viewerMotion(item.type, inDialog) }

    /** [item]'s motion once its clip or video is known, for the bars and the keys. */
    fun playing(item: ViewedMedia): ViewerMotion? = of(item)?.takeIf { item.key in clips }

    /** What [item]'s page has found it plays now, or that it has nothing to (made a still). */
    internal fun found(item: ViewedMedia, clip: MotionSource?) {
        if (clip != null) clips[item.key] = clip else clips.remove(item.key)
    }

    /** Lets go of what is kept for items no longer among [keys]. */
    fun keepOnly(keys: Set<String>) {
        motions.keys.retainAll { it.substringBeforeLast('/') in keys }
        clips.keys.retainAll(keys)
    }
}

/**
 * The one viewer ([ViewerFrame]) over [items], whatever they are and wherever they come from: each
 * page drawn alike ([MediaPage]), a live photo's button in the bar over it and a video's controls in
 * the bar under it, Space playing or pausing what the one shown holds, and the info panel. Once it
 * rests on one a moment, that one fetches what it needs and those either side read ahead, so
 * swiping on shows each whole at once while a run of swipes asks nothing of those passed.
 *
 * [caption] and [actions] fill the bar under the one shown, [top] the bar over it beside its live
 * button, and [footer] goes under them; [over] lies on each photo, zooming and panning with it.
 * [inDialog] is a viewer in a dialog of its own, whose videos don't go on in a window.
 */
@Composable
fun MediaViewerFrame(
    container: AppContainer,
    items: List<ViewedMedia>,
    pager: PagerState,
    onClose: () -> Unit,
    caption: @Composable RowScope.(ViewedMedia) -> Unit,
    modifier: Modifier = Modifier,
    inDialog: Boolean = false,
    marked: (Int) -> Boolean = { false },
    thumbLabel: @Composable (ViewedMedia) -> String? = { it.description() },
    top: @Composable RowScope.(ViewedMedia) -> Unit = {},
    actions: @Composable RowScope.(ViewedMedia) -> Unit = {},
    footer: @Composable ColumnScope.(ViewedMedia) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    over: @Composable (ViewedMedia) -> Unit = {},
) {
    if (items.isEmpty()) return
    val device = LocalDevice.current
    val motions = remember(device, inDialog) { ViewerMotions(device, inDialog) }
    LaunchedEffect(items.map { it.key }) { motions.keepOnly(items.mapTo(HashSet()) { it.key }) }
    val shown = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    val context = coil3.compose.LocalPlatformContext.current
    val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.let { maxOf(it.width, it.height) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(shown.key, pager.isScrollInProgress) {
        if (pager.isScrollInProgress) return@LaunchedEffect
        delay(AHEAD_AFTER)
        val reading = ReadAhead(context, screen, scope)
        shown.settled(reading)
        listOfNotNull(items.getOrNull(pager.currentPage + 1), items.getOrNull(pager.currentPage - 1)).forEach { it.ahead(shown, reading) }
    }
    ViewerFrame(
        pager = pager,
        key = { items[it].key },
        onClose = onClose,
        modifier = modifier,
        thumb = { items[it].Thumb(Modifier.fillMaxSize()) },
        thumbLabel = { thumbLabel(items[it]) },
        marked = marked,
        // Its own for each, so turning to one starts from what is known of it, never the last one's.
        info = { panel ->
            key(shown.key) {
                val details = shown.rememberDetails()
                PhotoInfoPanel(container, details, panel, note = shown.infoNote(details)?.let { stringResource(it) })
            }
        },
        onSpace = { motions.playing(shown)?.toggle() },
        top = { at ->
            Row(Modifier.align(at), verticalAlignment = Alignment.CenterVertically) {
                top(shown)
                if (shown.type == MediaType.LIVE_PHOTO) motions.playing(shown)?.LiveButton(Modifier)
            }
        },
        controls = {
            if (shown.type == MediaType.VIDEO) motions.playing(shown)?.Controls(Modifier.padding(horizontal = Space.xs))
        },
        caption = { caption(shown) },
        actions = { actions(shown) },
        footer = { footer(shown) },
        overlay = overlay,
    ) { page, onZoom, onTap ->
        MediaPage(items[page], active = pager.currentPage == page, motions, onZoom, onTap) { over(items[page]) }
    }
}

/**
 * One item's page: its still, zoomed by [ZoomableMedia] and read sharper once zoomed into, with its
 * live clip or video played over it while [active]. Held down, a live photo plays with its sound until
 * let go. A video the device doesn't play here keeps its poster with the play mark over it.
 */
@Composable
private fun MediaPage(
    item: ViewedMedia,
    active: Boolean,
    motions: ViewerMotions,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    over: @Composable () -> Unit,
) {
    val clip = item.rememberClip(active)
    // Only the one shown says it has none: the others let theirs go as they are turned from.
    LaunchedEffect(item.key, clip, active) { if (clip != null || active) motions.found(item, clip) }
    var deep by remember(item.key) { mutableStateOf(false) }
    val still = item.rememberStill(active, deep)
    val motion = motions.of(item)
    val playing = clip?.takeIf { motion != null }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZoomableMedia(
            still,
            item.description(),
            onZoom = { zoomed ->
                if (zoomed) deep = true
                onZoom(zoomed)
            },
            onTap = onTap,
            onHold = motion?.hold?.takeIf { playing != null && item.type == MediaType.LIVE_PHOTO },
            overlay = {
                if (playing != null) motion?.Over(playing, active, Modifier.fillMaxSize())
                over()
            },
        )
        if (playing != null) motion?.Waiting()
        else if (item.type == MediaType.VIDEO && (motion == null || !item.playable)) Icon(AppIcons.Play, contentDescription = null, tint = MediaOverlay.content, modifier = Modifier.size(48.dp))
        item.Decor()
    }
}

/** How long a viewer rests on one before those either side are read ahead, so a run of swipes asks nothing of those swiped past. */
const val AHEAD_AFTER = 300L
