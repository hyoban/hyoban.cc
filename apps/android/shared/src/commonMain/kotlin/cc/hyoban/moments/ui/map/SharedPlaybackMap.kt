package cc.hyoban.moments.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.data.trips.PlaybackClock
import cc.hyoban.moments.data.trips.Travel
import cc.hyoban.moments.res.Res
import cc.hyoban.moments.res.playback_loading_map
import cc.hyoban.moments.res.trip_day
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.ContainedSpinner
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.ShadowElevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.ui.trips.CameraShot
import cc.hyoban.moments.ui.trips.PlaybackController
import cc.hyoban.moments.ui.trips.StopState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

// A trip played back on the map as the site draws it: the planned route faint and dashed,
// the travelled route in ink up to its moving head, every place a dot, and the current names.

private val PlaybackHeadSize = 26.dp
private const val PlannedOpacity = 0.55f

private fun playbackPosition(point: Pair<Double, Double>) = positionOf(point.second, point.first)

private fun playbackLineData(line: List<Pair<Double, Double>>) = GeoJsonData.Features(
    FeatureCollection(
        if (line.size < 2) emptyList()
        else listOf(Feature(LineString(line.map(::playbackPosition)), null)),
    ),
)

private fun playbackStopsData(controller: PlaybackController): GeoJsonData {
    val states = controller.stopStates()
    return GeoJsonData.Features(
        FeatureCollection(
            controller.playback.points.mapIndexed { index, point ->
                Feature(
                    Point(playbackPosition(point.coordinates)),
                    buildJsonObject {
                        put("state", states[index].name)
                        put("waypoint", point.waypoint)
                    },
                )
            },
        ),
    )
}

private fun playbackNamesData(controller: PlaybackController) = GeoJsonData.Features(
    FeatureCollection(
        controller.labels.map { label ->
            Feature(
                Point(playbackPosition(label.coordinates)),
                buildJsonObject { put("name", label.name) },
            )
        },
    ),
)

private fun playbackHeadData(tip: Pair<Double, Double>?, travel: Travel) = GeoJsonData.Features(
    FeatureCollection(
        listOfNotNull(
            tip?.let {
                Feature(
                    Point(playbackPosition(it)),
                    buildJsonObject { put("travel", travel.name) },
                )
            },
        ),
    ),
)

private class PlaybackHeadPainter(
    private val icon: Painter,
    private val ink: Color,
    private val paper: Color,
) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val diameter = size.minDimension
        drawCircle(paper, radius = diameter / 2)
        drawCircle(ink, radius = diameter / 2 - 2.dp.toPx())
        val iconSize = 16.dp.toPx()
        translate((diameter - iconSize) / 2, (diameter - iconSize) / 2) {
            with(icon) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(paper)) }
        }
    }
}

@Composable
private fun rememberPlaybackHead(icon: ImageVector, ink: Color, paper: Color): Painter {
    val vector = rememberVectorPainter(icon)
    return remember(vector, ink, paper) { PlaybackHeadPainter(vector, ink, paper) }
}

/** The shared playback map used by both the phone and the desktop. */
@Composable
fun SharedPlaybackMap(
    controller: PlaybackController,
    clear: List<Dp>,
    modifier: Modifier = Modifier,
    credit: IntArray? = null,
) {
    require(clear.size == 4) { "Playback map padding needs start, top, end and bottom" }
    val dark = LocalDarkTheme.current
    val ink = tokens.tx
    val paper = tokens.bg
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val physicalPadding = remember(clear, direction) {
        DpPadding(
            left = if (direction == LayoutDirection.Ltr) clear[0] else clear[2],
            top = clear[1],
            right = if (direction == LayoutDirection.Ltr) clear[2] else clear[0],
            bottom = clear[3],
        )
    }
    val creditValues = credit?.toList()
    val attributionPadding = remember(creditValues, direction, density.density) {
        if (creditValues == null) {
            PaddingValues(8.dp)
        } else {
            with(density) {
                val left = creditValues[0].toDp()
                val right = creditValues[2].toDp()
                PaddingValues(
                    start = if (direction == LayoutDirection.Ltr) left else right,
                    top = creditValues[1].toDp(),
                    end = if (direction == LayoutDirection.Ltr) right else left,
                    bottom = creditValues[3].toDp(),
                )
            }
        }
    }

    val headPainters = mapOf(
        Travel.CAR to rememberPlaybackHead(AppIcons.Car, ink, paper),
        Travel.FERRY to rememberPlaybackHead(AppIcons.Boat, ink, paper),
        Travel.PLANE to rememberPlaybackHead(AppIcons.Plane, ink, paper),
        Travel.TRAIN to rememberPlaybackHead(AppIcons.Train, ink, paper),
    )
    val route = controller.playback.route
    val plannedData = remember(route) { playbackLineData(route.points) }
    var drawnData by remember(controller) {
        mutableStateOf(playbackLineData(if (controller.drawnPointCount > 1) route.line(0.0, controller.reachedTo) else emptyList()))
    }
    var activeData by remember(controller) {
        mutableStateOf(playbackLineData(controller.tip?.let { route.line(controller.reachedTo, controller.drawnTo) }.orEmpty()))
    }
    var stopsData by remember(controller) { mutableStateOf(playbackStopsData(controller)) }
    var namesData by remember(controller) { mutableStateOf(playbackNamesData(controller)) }
    var headData by remember(controller) { mutableStateOf(playbackHeadData(controller.tip, controller.travel)) }
    val playbackCamera = remember(controller) { SharedPlaybackCamera() }

    val interactions = remember(playbackCamera) {
        MapInteractions(MapInteractions.Standard) {
            camera {
                pan { onStart(playbackCamera::release) }
                zoom { onStart(playbackCamera::release) }
                rotate { enabled = false }
                pitch { enabled = false }
            }
        }
    }
    val state = rememberMapState(
        baseStyle = mapStyle(dark),
        initialCameraPosition = CameraPosition(center = Position(longitude = 110.0, latitude = 32.0), zoom = 3.0),
    ) {
        val planned = rememberGeoJsonSource(plannedData)
        val drawn = rememberGeoJsonSource(drawnData)
        val active = rememberGeoJsonSource(activeData)
        val stops = rememberGeoJsonSource(stopsData)
        val names = rememberGeoJsonSource(namesData)
        val head = rememberGeoJsonSource(headData)

        Anchor.Below({ it.type == "symbol" }) {
            LineLayer(
                id = "shared-playback-planned",
                source = planned,
                color = const(ink),
                dasharray = const(listOf(1, 2)),
                opacity = const(PlannedOpacity),
                width = const(2.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            LineLayer(
                id = "shared-playback-drawn",
                source = drawn,
                color = const(ink),
                width = const(3.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            LineLayer(
                id = "shared-playback-active",
                source = active,
                color = const(ink),
                width = const(3.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
        }
        val upcoming = feature["state"] eq const(StopState.UPCOMING.name)
        val current = feature["state"] eq const(StopState.CURRENT.name)
        CircleLayer(
            id = "shared-playback-stops",
            source = stops,
            color = switch(condition(upcoming, const(paper)), fallback = const(ink)),
            radius = switch(
                condition(current, const(7.dp)),
                condition(feature["waypoint"].asBoolean(), const(3.dp)),
                fallback = const(4.5.dp),
            ),
            strokeColor = switch(condition(upcoming, const(ink)), fallback = const(paper)),
            strokeWidth = const(2.dp),
        )
        SymbolLayer(
            id = "shared-playback-names",
            source = names,
            textField = feature["name"].asString(),
            textFont = const(listOf("Noto Sans Bold")),
            textSize = const(14.sp),
            textAnchor = const(SymbolAnchor.Bottom),
            textOffset = textOffset(0.em, (-0.9).em),
            textColor = const(ink),
            textHaloColor = const(paper),
            textHaloWidth = const(2.dp),
            textAllowOverlap = const(true),
        )
        headPainters.forEach { (travel, painter) ->
            SymbolLayer(
                id = "shared-playback-head-${travel.name.lowercase()}",
                source = head,
                filter = feature["travel"] eq const(travel.name),
                iconImage = image(painter, DpSize(PlaybackHeadSize, PlaybackHeadSize)),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )
        }
    }

    var tipOnScreen by remember { mutableStateOf<Offset?>(null) }
    val clock = controller.clock
    val shown by remember { derivedStateOf { tipOnScreen != null } }
    var keptClock by remember { mutableStateOf(clock) }
    if (clock != null) keptClock = clock
    val motion = AppTheme.motionScheme
    val ready = state.style.loadState == StyleLoadState.Ready && state.viewport != null

    Box(modifier) {
        SharedMap(
            state = state,
            interactive = true,
            modifier = Modifier.fillMaxSize(),
            interactions = interactions,
            attributionPadding = attributionPadding,
        )
        AnimatedVisibility(clock != null && shown, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
            val beside = with(density) { (PlaybackHeadSize / 2 + 8.dp).roundToPx() }
            PlaybackHeadClock(
                keptClock ?: return@AnimatedVisibility,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(0, 0) {
                        val tip = tipOnScreen ?: return@layout
                        placeable.place(IntOffset(tip.x.roundToInt() + beside, tip.y.roundToInt() - placeable.height / 2))
                    }
                },
            )
        }
        AnimatedVisibility(!ready, enter = fadeIn(), exit = fadeOut(motion.fastEffectsSpec())) {
            Box(Modifier.fillMaxSize().padding(top = clear[1], bottom = clear[3]), contentAlignment = Alignment.Center) {
                val loading = stringResource(Res.string.playback_loading_map)
                ContainedSpinner(Modifier.semantics { contentDescription = loading })
            }
        }
    }

    val currentPadding by rememberUpdatedState(physicalPadding)
    LaunchedEffect(state, controller) {
        state.awaitViewport()
        var drawnCount = -1
        var activeShown = false
        var stopsShown = -1
        var shotSerial = -1
        var last = withFrameNanos { it }
        var carry = 0L
        while (true) {
            val now = withFrameNanos { it }
            carry += now - last
            val delta = (carry / 1_000_000).coerceAtMost(100)
            carry %= 1_000_000
            val seconds = (now - last) / 1e9
            last = now
            controller.tick(delta)

            if (controller.drawnPointCount != drawnCount) {
                drawnCount = controller.drawnPointCount
                drawnData = playbackLineData(if (drawnCount > 1) route.line(0.0, controller.reachedTo) else emptyList())
            }
            val tip = controller.tip
            if (tip != null || activeShown) {
                activeData = playbackLineData(if (tip != null) route.line(controller.reachedTo, controller.drawnTo) else emptyList())
                headData = playbackHeadData(tip, controller.travel)
                activeShown = tip != null
            }
            if (controller.stopsVersion != stopsShown) {
                stopsShown = controller.stopsVersion
                stopsData = playbackStopsData(controller)
                namesData = playbackNamesData(controller)
            }
            controller.shot?.let { shot ->
                if (shot.serial != shotSerial) {
                    shotSerial = shot.serial
                    playbackCamera.setShot(shot)
                }
            }
            if (!controller.held) playbackCamera.update(state, seconds, controller.focus, currentPadding)
            val viewport = state.viewport
            tipOnScreen = tip?.let(::playbackPosition)?.let { viewport?.screenLocationFromPosition(it) }?.let { at ->
                with(density) { Offset(at.x.toPx(), at.y.toPx()) }
            }
        }
    }
}

@Composable
private fun PlaybackHeadClock(clock: PlaybackClock, modifier: Modifier) {
    Surface(
        shape = AppTheme.shapes.medium,
        color = SurfaceLevel.Floating.fill(),
        shadowElevation = ShadowElevation,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = Space.s + Space.xs, vertical = Space.xs)) {
            Text(formatDay(clock.dateKey), style = AppTheme.typography.caption, color = tokens.tx2)
            Text(
                listOfNotNull(stringResource(Res.string.trip_day, clock.day), clock.time).joinToString(" "),
                style = AppTheme.typography.label.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", fontSize = 13.sp),
                textAlign = TextAlign.Start,
            )
        }
    }
}

/** The critically damped camera shared by playback on every platform. */
private class SharedPlaybackCamera {
    private var shot: CameraShot? = null
    private var target: Triple<String, Double, Pair<Double, Double>?>? = null
    private var center: Pair<Double, Double>? = null
    private var centerVelocity = 0.0 to 0.0
    private var zoom: Double? = null
    private var zoomVelocity = 0.0
    private var focusVelocity = 0.0 to 0.0
    private var lastFocus: Pair<Double, Double>? = null
    private var snap = false
    private var released = false
    private var applied: DpPadding? = null

    fun release() {
        released = true
    }

    fun setShot(next: CameraShot) {
        shot = next
        target = null
        if (released) {
            center = null
            released = false
        }
        snap = next.instant
    }

    suspend fun update(
        state: org.maplibre.compose.map.MapState,
        seconds: Double,
        focusAt: Pair<Double, Double>?,
        padding: DpPadding,
    ) {
        val shot = shot ?: return
        if (released) return
        val step = seconds.coerceIn(0.0, 0.1)
        val centerOmega = 4 / CenterSettle
        val zoomOmega = 4 / ZoomSettle
        val goal = target(state, shot, padding) ?: return
        val focus = trackFocus(step, focusAt?.let(::toPlaybackMercator), state.cameraPosition.zoom)
        val goalCenter = goal.third ?: focus?.let { (x, y) ->
            x + focusVelocity.first * 2 / centerOmega to y + focusVelocity.second * 2 / centerOmega
        } ?: return

        if (center == null || zoom == null || snap) {
            center = if (snap) goalCenter else toPlaybackMercator(state.cameraPosition.center.longitude to state.cameraPosition.center.latitude)
            zoom = if (snap) goal.second else state.cameraPosition.zoom
            centerVelocity = 0.0 to 0.0
            zoomVelocity = 0.0
        }
        if (snap) {
            snap = false
            apply(state, center!!, zoom!!, padding)
            return
        }
        val current = center!!
        val x = playbackSpring(current.first, centerVelocity.first, goalCenter.first, centerOmega, step)
        val y = playbackSpring(current.second, centerVelocity.second, goalCenter.second, centerOmega, step)
        val z = playbackSpring(zoom!!, zoomVelocity, goal.second, zoomOmega, step)
        val pixel = 0.25 / (512 * 2.0.pow(z.first))
        center = x.first to y.first
        centerVelocity = x.second to y.second
        zoom = z.first
        zoomVelocity = z.second
        if (abs(x.first - goalCenter.first) < pixel && abs(y.first - goalCenter.second) < pixel && abs(z.first - goal.second) < 0.002 &&
            hypot(x.second, y.second) * step < pixel && abs(z.second) * step < 0.002
        ) {
            if (abs(state.cameraPosition.zoom - goal.second) < 0.002 && padding == applied) return
            center = goalCenter
            zoom = goal.second
        }
        apply(state, center!!, zoom!!, padding)
    }

    private fun trackFocus(seconds: Double, focus: Pair<Double, Double>?, currentZoom: Double): Pair<Double, Double>? {
        val before = lastFocus
        lastFocus = focus
        if (focus == null || before == null || seconds == 0.0) {
            focusVelocity = 0.0 to 0.0
            return focus
        }
        val moved = hypot(focus.first - before.first, focus.second - before.second) * 512 * 2.0.pow(currentZoom)
        val smoothing = 1 - exp(-seconds / 0.15)
        val velocity = if (moved > JumpPixels) {
            0.0 to 0.0
        } else {
            (focus.first - before.first) / seconds to (focus.second - before.second) / seconds
        }
        focusVelocity = focusVelocity.first + (velocity.first - focusVelocity.first) * smoothing to
            focusVelocity.second + (velocity.second - focusVelocity.second) * smoothing
        return focus
    }

    private suspend fun target(
        state: org.maplibre.compose.map.MapState,
        shot: CameraShot,
        padding: DpPadding,
    ): Triple<String, Double, Pair<Double, Double>?>? {
        val viewport = state.viewport ?: return null
        val key = "${viewport.size}:${padding}"
        target?.takeIf { it.first == key }?.let { return it }
        val start = shot.points.firstOrNull()
            ?: return Triple(key, state.cameraPosition.zoom, null).also { target = it }
        val corners = shot.points + if (shot.follow) {
            shot.points.map { (2 * start.first - it.first) to (2 * start.second - it.second) }
        } else {
            emptyList()
        }
        val targetZoom = if (corners.toSet().size < 2) {
            shot.maxZoom
        } else {
            val fitted = state.cameraForCoordinates(
                corners.map(::playbackPosition),
                CameraFit(bearing = 0.0, pitch = 0.0, cameraPadding = padding),
            )
            minOf(fitted?.zoom ?: shot.maxZoom, shot.maxZoom)
        }
        val targetCenter = if (shot.follow) {
            null
        } else {
            val projected = shot.points.map(::toPlaybackMercator)
            (projected.minOf { it.first } + projected.maxOf { it.first }) / 2 to
                (projected.minOf { it.second } + projected.maxOf { it.second }) / 2
        }
        return Triple(key, targetZoom, targetCenter).also { target = it }
    }

    private fun apply(
        state: org.maplibre.compose.map.MapState,
        center: Pair<Double, Double>,
        zoom: Double,
        padding: DpPadding,
    ) {
        applied = padding
        state.setCameraPosition(
            CameraPosition(
                center = playbackPosition(fromPlaybackMercator(center)),
                zoom = zoom,
                bearing = 0.0,
                pitch = 0.0,
                padding = padding,
            ),
        )
    }

    private companion object {
        const val CenterSettle = 0.8
        const val ZoomSettle = 2.6
        const val JumpPixels = 160.0
    }
}

internal fun playbackSpring(
    value: Double,
    velocity: Double,
    goal: Double,
    omega: Double,
    seconds: Double,
): Pair<Double, Double> {
    val offset = value - goal
    val decay = exp(-omega * seconds)
    val drift = (velocity + omega * offset) * seconds
    return goal + (offset + drift) * decay to (velocity - omega * drift) * decay
}

private fun toPlaybackMercator(point: Pair<Double, Double>): Pair<Double, Double> {
    val latitude = point.second.coerceIn(-85.051129, 85.051129) * PI / 180
    return (point.first + 180) / 360 to (1 - ln(tan(latitude) + 1 / cos(latitude)) / PI) / 2
}

private fun fromPlaybackMercator(point: Pair<Double, Double>): Pair<Double, Double> =
    point.first * 360 - 180 to atan(sinh(PI * (1 - 2 * point.second))) * 180 / PI
