package cc.hyoban.moments.ui.picker

import cc.hyoban.moments.res.*
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.asImageBitmap
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

/** How much of the page the opened heap's photos take at rest, the map keeping the rest. */
private const val SHEET_SHARE = 0.55f

/** How wide the opened heap's photos are beside the map, on a phone on its side. */
private val PANEL = 360.dp

/** A lone place is framed no closer than its streets. */
private const val CLOSEST = 14.0

/**
 * 选照片's map (39): the photos of what is listed where they were taken, those near each other on
 * screen in one heap that shows one of them and says how many, parting as the map comes closer. A
 * heap tapped is opened: its photos come up from the foot in [grid], the map staying above, and
 * go again dragged down, with a tap on the map or by going back. On its side they are a panel
 * beside the map.
 */
@Composable
internal fun PlacesMap(places: Places, sideways: Boolean, grid: @Composable (onNearEnd: () -> Unit) -> Unit) {
    LaunchedEffect(places) { places.load() }
    val spots = places.spots
    val failed = places.failed
    when {
        failed != null && spots == null -> { failed(); return }
        spots == null -> { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }; return }
        spots.isEmpty() -> {
            Text(stringResource(Res.string.picker_map_empty), style = AppTheme.typography.body, color = tokens.tx3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(top = LocalTopClearance.current + Space.xl * 3).padding(horizontal = Space.xl))
            return
        }
    }
    val opened = places.opened
    BackHandler(enabled = opened != null) { places.open(null) }
    val photos: @Composable () -> Unit = {
        // Under the sheet's own top, not the page's bar.
        CompositionLocalProvider(LocalTopClearance provides 0.dp) {
            Box(Modifier.fillMaxSize()) {
                grid { places.more() }
                if (places.reading) Spinner(Modifier.align(Alignment.TopCenter).padding(top = Space.xl))
            }
        }
    }
    if (sideways) {
        Row(Modifier.fillMaxSize()) {
            HeapsMap(places, spots, opened, Modifier.weight(1f).fillMaxHeight())
            if (opened != null) Box(Modifier.width(PANEL).fillMaxHeight().background(AppColors.sheet).padding(top = LocalTopClearance.current)) { photos() }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        HeapsMap(places, spots, opened, Modifier.fillMaxSize())
        if (opened != null) key(opened.first().id) {
            val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false)
            LaunchedEffect(sheet) {
                snapshotFlow { sheet.currentValue }.drop(1).collect { if (it == SheetValue.Hidden) places.open(null) }
            }
            BottomSheetScaffold(
                scaffoldState = rememberBottomSheetScaffoldState(sheet),
                // Drawn up to the bar's circles at most.
                modifier = Modifier.padding(top = LocalTopClearance.current).testTag("picker_heap_sheet"),
                sheetPeekHeight = maxHeight * SHEET_SHARE,
                sheetContainerColor = AppColors.sheet,
                containerColor = Color.Transparent,
                sheetContent = { photos() },
            ) { }
        }
    }
}

/** A heap as it stands on the screen now. */
private class Placed(val heap: Heap, val at: DpOffset)

/**
 * The map and its heaps over it. The heaps are grouped again each half zoom, and laid over the
 * map where it draws their points, every frame the map moves.
 */
@Composable
private fun HeapsMap(places: Places, spots: List<Spot>, opened: List<Spot>?, modifier: Modifier) {
    var placed by remember { mutableStateOf<List<Placed>>(emptyList()) }
    var framed by remember { mutableStateOf(false) }
    val dark = LocalDarkTheme.current
    val top = LocalTopClearance.current
    val heapsAt = remember(spots) { HashMap<Double, List<Heap>>() }
    val close = remember(places) { { places.open(null) } }
    val interactions = remember(close) {
        MapInteractions(MapInteractions.Standard) {
            camera {
                rotate { enabled = false }
                pitch { enabled = false }
            }
            callbacks {
                click {
                    onUnhandled {
                        close()
                        ClickResult.Consume
                    }
                }
            }
        }
    }
    val state = rememberMapState(
        baseStyle = mapStyle(dark),
        initialCameraPosition = CameraPosition(center = positionOf(latitude = 32.0, longitude = 110.0), zoom = 3.0),
    )

    LaunchedEffect(state, spots, framed) {
        state.awaitViewport()
        if (!framed) {
            frame(state, spots, HEAP + Space.xl, top + HEAP + Space.xl)
            framed = true
        }
    }
    // Deleted photos leave their heaps; camera and photo changes both rebuild what is over the map.
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

    Box(modifier.clip(androidx.compose.ui.graphics.RectangleShape)) {
        SharedMap(
            state = state,
            interactive = true,
            modifier = Modifier.fillMaxSize().testTag("picker_map"),
            interactions = interactions,
            attributionPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Space.m,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + Space.m,
            ),
        )
        placed.forEach { at ->
            key(at.heap.cover.id) {
                HeapMark(
                    places, at.heap, chosen = opened != null && opened.first().id == at.heap.cover.id,
                    modifier = Modifier.offset(at.at.x - HEAP / 2, at.at.y - HEAP / 2),
                ) { places.open(at.heap.spots) }
            }
        }
    }
}

/** All of [spots] in view, clear of [padding] at each edge; a lone place no closer than its streets. */
private suspend fun frame(map: MapState, spots: List<Spot>, padding: androidx.compose.ui.unit.Dp, topPadding: androidx.compose.ui.unit.Dp) {
    val points = spots.map { positionOf(it.latitude, it.longitude) }
    val first = points.firstOrNull() ?: return
    if (points.all { it.latitude == first.latitude && it.longitude == first.longitude }) {
        map.setCameraPosition(CameraPosition(center = first, zoom = CLOSEST))
        return
    }
    val camera = map.cameraForCoordinates(
        points,
        CameraFit(fitPadding = DpPadding(left = padding, top = topPadding, right = padding, bottom = padding)),
    )
    if (camera != null) map.setCameraPosition(camera.copy(zoom = minOf(camera.zoom, CLOSEST)))
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
    val thumb by produceState<Bitmap?>(null, cover.id) { value = places.thumbnail(cover) }
    val label = stringResource(Res.string.picker_heap_description, heap.count)
    Box(modifier.size(HEAP)) {
        Box(
            Modifier.fillMaxSize().softShadow(ShadowElevation, shape).background(tokens.fill, shape).clip(shape)
                .semantics { selected = chosen }
                .clickable(onClickLabel = label, role = Role.Button, onClick = onOpen)
                .testTag("picker_heap_${cover.id}"),
        ) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Box(Modifier.fillMaxSize().border(if (chosen) 3.dp else 2.dp, if (chosen) tokens.accent else MediaOverlay.content, shape))
        if (heap.count > 1) Box(
            Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .background(tokens.tx, CircleShape).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) { Text("${heap.count}", style = AppTheme.typography.caption.strong, color = tokens.bg) }
    }
}
