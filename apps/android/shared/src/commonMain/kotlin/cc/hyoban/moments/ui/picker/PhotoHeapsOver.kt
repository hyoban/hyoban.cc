package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import androidx.compose.ui.graphics.ImageBitmap
import cc.hyoban.moments.ui.components.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.BottomSheetScaffold
import cc.hyoban.moments.ui.design.SheetValue
import cc.hyoban.moments.ui.design.Spinner
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.design.rememberBottomSheetScaffoldState
import cc.hyoban.moments.ui.design.rememberStandardBottomSheetState
import cc.hyoban.moments.ui.design.softShadow
import cc.hyoban.moments.ui.design.strong
import cc.hyoban.moments.ui.map.SharedMap
import cc.hyoban.moments.ui.map.mapStyle
import cc.hyoban.moments.ui.map.positionOf
import cc.hyoban.moments.ui.theme.MediaOverlay
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.VisibleBounds

/** A heap's photo on the map, and how near two stand before they are one heap: the photo and a little air. */
private val HEAP = 48.dp
private const val REACH = 64.0

/** A heap as it stands on the screen now. */
private class Placed(val heap: Heap, val at: DpOffset)

/**
 * [places]' photos over the map [state] draws, those within [REACH] points of each other on screen in
 * one heap that shows one of them and says how many, grouped again each half zoom and laid where
 * the map draws their points every frame it moves; [year] leaves only that year's. A heap tapped is
 * opened, its photos read for the sheet under the map.
 */
@Composable
fun HeapsOver(state: MapState, places: Places, year: Int?) {
    LaunchedEffect(places) { places.load() }
    val all = places.spots ?: return
    val spots = remember(all, year) { if (year == null) all else all.filter { it.year == year } }
    val opened = places.opened
    var placed by remember { mutableStateOf<List<Placed>>(emptyList()) }
    val heapsAt = remember(spots) { HashMap<Double, List<Heap>>() }
    LaunchedEffect(state, spots) {
        snapshotFlow { state.viewport }.collectLatest { viewport ->
            if (viewport == null) return@collectLatest
            val step = PhotoHeaps.step(viewport.cameraPosition.zoom)
            val heaps = heapsAt.getOrPut(step) { PhotoHeaps.at(spots, step, REACH) }
            val seen = viewport.visibleBounds
            placed = heaps.asSequence()
                .filter { seen.near(it.latitude, it.longitude) }
                .mapNotNull { heap ->
                    viewport.screenLocationFromPosition(positionOf(heap.latitude, heap.longitude))?.let { Placed(heap, it) }
                }
                .toList()
        }
    }
    Box(Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape)) {
        placed.forEach { at ->
            key(at.heap.cover) {
                HeapMark(
                    places, at.heap, chosen = opened != null && opened.first() == at.heap.cover,
                    modifier = Modifier.offset(at.at.x - HEAP / 2, at.at.y - HEAP / 2),
                ) { places.open(at.heap.spots) }
            }
        }
    }
}

/** A heap just off an edge still shows the half of it that is on, including across the antimeridian. */
private fun VisibleBounds.near(latitude: Double, longitude: Double): Boolean {
    val latitudeMargin = (north - south) * 0.1
    if (latitude !in (south - latitudeMargin)..(north + latitudeMargin)) return false
    val span = if (east >= west) east - west else east + 360.0 - west
    val margin = span * 0.1
    return (-1..1).any { turn -> longitude + turn * 360.0 in (west - margin)..(west + span + margin) }
}

/** A heap on the map: its first photo, how many it holds at its corner when more than one, the accent around it while it is the one opened. */
@Composable
private fun HeapMark(places: Places, heap: Heap, chosen: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val shape = AppTheme.shapes.medium
    val cover = heap.cover
    val thumb by produceState<ImageBitmap?>(null, cover) { value = places.thumbnail(cover) }
    val label = stringResource(Res.string.picker_heap_description, heap.count)
    Box(modifier.size(HEAP)) {
        Box(
            Modifier.fillMaxSize().softShadow(ShadowElevation, shape).background(tokens.fill, shape).clip(shape)
                .semantics { selected = chosen }
                .clickable(onClickLabel = label, role = Role.Button, onClick = onOpen)
                .testTag("photo_heap_${cover.from}_${cover.id}"),
        ) {
            thumb?.let { Image(it, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Box(Modifier.fillMaxSize().border(if (chosen) 3.dp else 2.dp, if (chosen) tokens.accent else MediaOverlay.content, shape))
        if (heap.count > 1) Box(
            Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .background(tokens.tx, CircleShape).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text("${heap.count}", style = AppTheme.typography.caption.strong, color = tokens.bg) }
    }
}
