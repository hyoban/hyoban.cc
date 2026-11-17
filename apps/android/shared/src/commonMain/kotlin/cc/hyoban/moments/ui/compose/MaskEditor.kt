package cc.hyoban.moments.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.data.local.Faces
import cc.hyoban.moments.data.local.Mask
import cc.hyoban.moments.data.local.PhotoRef
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.design.Sticker
import cc.hyoban.moments.ui.design.rememberHaptics
import cc.hyoban.moments.ui.theme.MediaOverlay
import org.jetbrains.compose.resources.stringResource

/**
 * 人脸打码's patches over [photo], picked for a Moment, laid on it in the viewer at its size, each
 * as saving will write it: the mosaic, ringed in white (dark until it is drawn), or its sticker.
 * A tap on a patch chooses it, [selected], for the bar to change its style or take it away; a
 * tap elsewhere lets go of the one chosen, or with none chosen lays a new one there, in the style
 * [style] gives beside those laid.
 */
@Composable
fun MaskEditor(
    photo: PhotoRef,
    masks: List<Mask>,
    selected: Mask?,
    style: (List<Mask>) -> String?,
    onSelect: (Mask?) -> Unit,
    onChange: (List<Mask>) -> Unit,
) {
    val haptics = rememberHaptics()
    // The mosaics drawn so far, kept while new ones are drawn so the others don't flicker.
    var tiles by remember(photo) { mutableStateOf(emptyMap<Mask, ImageBitmap>()) }
    LaunchedEffect(photo, masks) {
        val missing = masks.filter { it.sticker == null && it !in tiles }
        val drawn = if (missing.isEmpty()) emptyMap() else Faces.preview(photo, missing).orEmpty()
        tiles = tiles.filterKeys { it in masks } + drawn
    }
    val label = stringResource(Res.string.label_mask)
    val current by rememberUpdatedState(masks)
    val chosen by rememberUpdatedState(selected)
    val newStyle by rememberUpdatedState(style)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("viewer_masks").pointerInput(Unit) {
            detectTapGestures { at ->
                val x = at.x / size.width
                val y = at.y / size.height
                val hit = current.lastOrNull { it.contains(x, y) }
                when {
                    hit != null -> {
                        select(hit.takeIf { it != chosen })
                        haptics.tick()
                    }
                    chosen != null -> select(null)
                    else -> {
                        val laid = Mask.around(x, y, size.width.toFloat() / size.height, current).copy(sticker = newStyle(current))
                        change(current + laid)
                        haptics.toggle(true)
                    }
                }
            }
        },
    ) {
        for (mask in masks) {
            val width = maxWidth * (mask.right - mask.left)
            val height = maxHeight * (mask.bottom - mask.top)
            val on = mask == selected
            Box(
                Modifier
                    .offset(maxWidth * mask.left, maxHeight * mask.top)
                    .size(width, height)
                    .semantics {
                        contentDescription = label
                        this.selected = on
                        onClick {
                            select(mask.takeIf { it != chosen })
                            true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val sticker = Sticker.of(mask.sticker)
                if (sticker != null) {
                    // Larger than the patch, as saving draws it, so it reaches past the box.
                    val side = (if (width > height) width else height) * Mask.STICKER_SCALE
                    StickerImage(sticker, side, Modifier.requiredSize(side))
                    if (on) Box(Modifier.fillMaxSize().border(3.dp, MediaOverlay.content, PATCH))
                } else {
                    Box(Modifier.fillMaxSize().background(MediaOverlay.control, PATCH))
                    tiles[mask]?.let { tile ->
                        Image(tile, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().clip(PATCH))
                    }
                    Box(Modifier.fillMaxSize().border(if (on) 3.dp else 2.dp, MediaOverlay.content, PATCH))
                }
            }
        }
    }
}

/** [sticker] drawn [size] across, as the design system's Stickers card draws it. */
@Composable
fun StickerImage(sticker: Sticker, size: Dp, modifier: Modifier = Modifier.size(size)) {
    val paths = remember(sticker) { sticker.layers.map { PathParser().parsePathString(it.path).toPath() } }
    Canvas(modifier) { drawSticker(sticker, paths) }
}

private fun DrawScope.drawSticker(sticker: Sticker, paths: List<Path>) {
    val unit = size.minDimension / Sticker.GRID
    translate((size.width - size.minDimension) / 2, (size.height - size.minDimension) / 2) {
        scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            translate(-Sticker.GRID_ORIGIN, -Sticker.GRID_ORIGIN) {
                sticker.layers.forEachIndexed { index, layer ->
                    layer.fill?.let { drawPath(paths[index], Color(it)) }
                    layer.stroke?.let { drawPath(paths[index], Color(it), style = Stroke(layer.width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                }
            }
        }
    }
}

private val PATCH = RoundedCornerShape(4.dp)
