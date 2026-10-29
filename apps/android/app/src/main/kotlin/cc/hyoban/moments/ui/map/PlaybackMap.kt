package cc.hyoban.moments.ui.map

import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.ContainedSpinner
import android.graphics.Bitmap
import android.util.DisplayMetrics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import cc.hyoban.moments.ui.design.AppTheme
import cc.hyoban.moments.ui.design.Surface
import cc.hyoban.moments.ui.design.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.R
import cc.hyoban.moments.data.trips.PlaybackClock
import cc.hyoban.moments.data.trips.Travel
import cc.hyoban.moments.ui.format.formatDay
import cc.hyoban.moments.ui.trips.CameraShot
import cc.hyoban.moments.ui.trips.PlaybackController
import cc.hyoban.moments.ui.trips.StopState
import cc.hyoban.moments.ui.theme.Elevation
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.SurfaceLevel
import cc.hyoban.moments.ui.theme.fill
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

// A trip played back on the map as the site draws it (packages/trip-map/src/player/
// route-layers.ts and camera.ts): the planned route faint and dashed, the route travelled
// in ink up to a car, ship, plane or train at its tip with the day and time beside it,
// every place a dot, hollow until reached, and the Moment's places named. One calm camera
// follows the tip.

private const val PLANNED = "trip-planned"
private const val DRAWN = "trip-route"
private const val ACTIVE = "trip-route-active"
private const val STOPS = "trip-stops"
private const val NAMES = "trip-names"
private const val HEAD = "trip-head"

private fun Pair<Double, Double>.latLng() = LatLng(second, first)

private fun Pair<Double, Double>.point() = Point.fromLngLat(first, second)

private fun lineOf(line: List<Pair<Double, Double>>) =
    FeatureCollection.fromFeatures(if (line.size < 2) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(line.map { it.point() }))))

/**
 * [controller]'s trip on the map, stepped a frame at a time from here, kept clear of [clear],
 * the screen's own controls over the map, as start, top, end and bottom.
 */
@Composable
fun PlaybackMap(controller: PlaybackController, clear: List<Dp>, modifier: Modifier = Modifier) {
    var ready by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    val ink = AppTheme.colorScheme.onSurface
    val paper = AppTheme.colorScheme.surface
    val density = LocalDensity.current
    val padding = edges(clear[0], clear[1], clear[2], clear[3])
    val heads = mapOf(
        Travel.CAR to rememberVectorPainter(AppIcons.Car),
        Travel.FERRY to rememberVectorPainter(AppIcons.Boat),
        Travel.PLANE to rememberVectorPainter(AppIcons.Plane),
        Travel.TRAIN to rememberVectorPainter(AppIcons.Train),
    )
    // Where the tip is on the screen, for the clock beside it; read as it is placed.
    var tipOnScreen by remember { mutableStateOf<Offset?>(null) }

    Box(modifier) {
        // Panned and pinched as any map is; the camera lets go then, until the next shot.
        MapLibreView(Modifier.matchParentSize(), interactive = true, compass = false) { map, style, _ ->
            // Turned, it would turn back north at the next shot; it stays north.
            map.uiSettings.isRotateGesturesEnabled = false
            val route = controller.playback.route
            val below = style.labelsLayer()
            fun under(layer: LineLayer) = if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
            style.addSource(GeoJsonSource(PLANNED, lineOf(route.points)))
            under(
                LineLayer(PLANNED, PLANNED).withProperties(
                    lineColor(ink.toArgb()), lineDasharray(arrayOf(1f, 2f)), lineOpacity(PLANNED_OPACITY), lineWidth(2f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            for (id in listOf(DRAWN, ACTIVE)) {
                style.addSource(GeoJsonSource(id))
                under(LineLayer(id, id).withProperties(lineColor(ink.toArgb()), lineWidth(3f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
            }
            style.addSource(GeoJsonSource(STOPS))
            val upcoming = eq(get("state"), literal(StopState.UPCOMING.name))
            style.addLayer(
                CircleLayer(STOPS, STOPS).withProperties(
                    circleColor(switchCase(upcoming, literal(paper.toHex()), literal(ink.toHex()))),
                    circleRadius(switchCase(eq(get("state"), literal(StopState.CURRENT.name)), literal(7f), get("waypoint"), literal(3f), literal(4.5f))),
                    circleStrokeColor(switchCase(upcoming, literal(ink.toHex()), literal(paper.toHex()))),
                    circleStrokeWidth(2f),
                ),
            )
            style.addSource(GeoJsonSource(NAMES))
            style.addLayer(
                SymbolLayer(NAMES, NAMES).withProperties(
                    textField(get("name")),
                    textFont(arrayOf("Noto Sans Bold")),
                    textSize(14f),
                    textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                    textOffset(arrayOf(0f, -0.9f)),
                    textColor(ink.toArgb()),
                    textHaloColor(paper.toArgb()),
                    textHaloWidth(2f),
                    textAllowOverlap(true),
                ),
            )
            heads.forEach { (travel, painter) -> style.addImage("head-${travel.name}", headImage(painter, density, ink, paper)) }
            style.addSource(GeoJsonSource(HEAD))
            style.addLayer(SymbolLayer(HEAD, HEAD).withProperties(iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)))
            ready = map to style
        }

        val clock = controller.clock
        val shown by remember { derivedStateOf { tipOnScreen != null } }
        // Beside the head while it drives, fading in and out with it on the effects spring.
        var kept by remember { mutableStateOf(clock) }
        if (clock != null) kept = clock
        val motion = AppTheme.motionScheme
        AnimatedVisibility(clock != null && shown, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
            // Beside the head, its middle level with it.
            val beside = with(density) { (HEAD_SIZE / 2 + 8.dp).roundToPx() }
            HeadClock(
                kept ?: return@AnimatedVisibility,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(0, 0) {
                        val tip = tipOnScreen ?: return@layout
                        placeable.place(IntOffset(tip.x.roundToInt() + beside, tip.y.roundToInt() - placeable.height / 2))
                    }
                },
            )
        }

        // Until the map is ready, a short wait: a spinner on a small round surface
        // over the map's blank, in the room the card leaves.
        AnimatedVisibility(ready == null, enter = fadeIn(), exit = fadeOut(motion.fastEffectsSpec())) {
            Box(Modifier.fillMaxSize().padding(top = clear[1], bottom = clear[3]), contentAlignment = Alignment.Center) {
                // Named by what it waits for, as a progress bar is.
                val loading = stringResource(R.string.playback_loading_map)
                ContainedSpinner(Modifier.semantics { contentDescription = loading })
            }
        }
    }

    val camera = remember { PlaybackCamera() }
    val inset by rememberUpdatedState(padding)
    // A pan or a pinch lets the camera go until the next shot, which picks up from there.
    DisposableEffect(ready) {
        val map = ready?.first ?: return@DisposableEffect onDispose { }
        val moved = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) camera.release()
        }
        map.addOnCameraMoveStartedListener(moved)
        onDispose { map.removeOnCameraMoveStartedListener(moved) }
    }
    LaunchedEffect(ready) {
        val (map, style) = ready ?: return@LaunchedEffect
        var drawnCount = -1
        var activeShown = false
        var stopsShown = -1
        var shotShown: CameraShot? = null
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

            val route = controller.playback.route
            if (controller.drawnPointCount != drawnCount) {
                drawnCount = controller.drawnPointCount
                style.getSourceAs<GeoJsonSource>(DRAWN)?.setGeoJson(lineOf(if (drawnCount > 1) route.line(0.0, controller.reachedTo) else emptyList()))
            }
            // The stretch being drawn, and the head at its tip.
            val tip = controller.tip
            if (tip != null || activeShown) {
                style.getSourceAs<GeoJsonSource>(ACTIVE)?.setGeoJson(lineOf(if (tip != null) route.line(controller.reachedTo, controller.drawnTo) else emptyList()))
                style.getSourceAs<GeoJsonSource>(HEAD)?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        listOfNotNull(tip?.let { Feature.fromGeometry(it.point()).apply { addStringProperty("icon", "head-${controller.travel.name}") } }),
                    ),
                )
                activeShown = tip != null
            }
            if (controller.stopsVersion != stopsShown) {
                stopsShown = controller.stopsVersion
                val states = controller.stopStates()
                style.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(
                    FeatureCollection.fromFeatures(
                        controller.playback.points.mapIndexed { n, point ->
                            Feature.fromGeometry(point.coordinates.point()).apply {
                                addStringProperty("state", states[n].name)
                                addBooleanProperty("waypoint", point.waypoint)
                            }
                        },
                    ),
                )
                style.getSourceAs<GeoJsonSource>(NAMES)?.setGeoJson(
                    FeatureCollection.fromFeatures(controller.labels.map { Feature.fromGeometry(it.coordinates.point()).apply { addStringProperty("name", it.name) } }),
                )
            }

            val shot = controller.shot
            if (shot != null && shot !== shotShown) {
                shotShown = shot
                camera.setShot(shot)
            }
            // Paused by hand, the camera holds still for the frame and carries on from there.
            if (!controller.held) camera.update(map, seconds, controller.focus, inset)
            tipOnScreen = tip?.let { map.projection.toScreenLocation(it.latLng()) }?.let { Offset(it.x, it.y) }
        }
    }
}

private val HEAD_SIZE = 26.dp

/**
 * The planned route's ink, strong enough that the dashes keep 3:1 against the map, as Material 3
 * has a graphic that carries meaning; the site draws it fainter, at 0.3.
 */
private const val PLANNED_OPACITY = 0.55f

/** The head: the leg's car, ship, plane or train in the page's colour on an ink disc ringed in it. */
private fun headImage(painter: VectorPainter, density: Density, ink: Color, paper: Color): Bitmap {
    val size = with(density) { HEAD_SIZE.toPx() }
    val image = ImageBitmap(size.roundToInt(), size.roundToInt())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(size, size)) {
        drawCircle(paper)
        drawCircle(ink, radius = size / 2 - 2.dp.toPx())
        val icon = 16.dp.toPx()
        translate((size - icon) / 2, (size - icon) / 2) {
            with(painter) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(paper)) }
        }
    }
    // Drawn at the screen's density, so the map shows it at its size in dp.
    return image.asAndroidBitmap().apply { this.density = (density.density * DisplayMetrics.DENSITY_DEFAULT).roundToInt() }
}

private fun Color.toHex() = "#%06X".format(toArgb() and 0xFFFFFF)

/** The day and time the route's tip has reached, beside it: `10月2日 周五` over `第 3 天\u200319:43`. */
@Composable
private fun HeadClock(clock: PlaybackClock, modifier: Modifier) {
    // Floating over the map in its container colour, solid so its words keep their contrast over any map.
    Surface(
        shape = AppTheme.shapes.small,
        color = SurfaceLevel.Floating.fill(),
        shadowElevation = Elevation.level2,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = Space.s + Space.xxs, vertical = Space.xs)) {
            Text(formatDay(clock.dateKey), style = AppTheme.typography.micro, color = AppTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(stringResource(R.string.trip_day, clock.day), clock.time).joinToString("\u2003"),
                style = AppTheme.typography.label.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", fontSize = 13.sp),
                textAlign = TextAlign.Start,
            )
        }
    }
}

/**
 * One calm camera for the whole playback, as the site's: while the route is drawn it keeps
 * the tip in the middle, so it glides along with it, easing in and out of every place with
 * it; the zoom is set by the stretch being driven, so a long drive is seen from far enough
 * out, and changes slowly. Both on critically damped springs: starting from rest, never
 * overshooting, a new target mid-move bending the motion rather than jolting it.
 */
private class PlaybackCamera {
    private var shot: CameraShot? = null
    // The zoom, and for an overview the centre, worked out once per shot and layout.
    private var target: Triple<String, Double, Pair<Double, Double>?>? = null
    private var center: Pair<Double, Double>? = null
    private var centerVelocity = 0.0 to 0.0
    private var zoom: Double? = null
    private var zoomVelocity = 0.0
    private var focusVelocity = 0.0 to 0.0
    private var lastFocus: Pair<Double, Double>? = null
    private var snap = false
    // The viewer moved the map themselves; the camera waits until the next shot.
    private var released = false

    fun release() {
        released = true
    }

    fun setShot(next: CameraShot) {
        shot = next
        target = null
        if (released) {
            // Picks up from wherever the viewer left the map.
            center = null
            released = false
        }
        snap = next.instant
    }

    fun update(map: MapLibreMap, seconds: Double, focusAt: Pair<Double, Double>?, padding: IntArray) {
        val shot = shot ?: return
        if (released) return
        val step = seconds.coerceIn(0.0, 0.1)
        val centerOmega = 4 / CENTER_SETTLE
        val zoomOmega = 4 / ZOOM_SETTLE
        val goal = target(map, shot, padding) ?: return
        val focus = trackFocus(step, focusAt?.let(::toMercator), map.cameraPosition.zoom)
        // Aims ahead of the tip by as far as the spring would trail it at its speed, so it stays in the middle.
        val goalCenter = goal.third ?: focus?.let { (x, y) -> x + focusVelocity.first * 2 / centerOmega to y + focusVelocity.second * 2 / centerOmega } ?: return

        if (center == null || zoom == null || snap) {
            center = if (snap) goalCenter else map.cameraPosition.target?.let { toMercator(it.longitude to it.latitude) } ?: goalCenter
            zoom = if (snap) goal.second else map.cameraPosition.zoom
            centerVelocity = 0.0 to 0.0
            zoomVelocity = 0.0
        }
        if (snap) {
            snap = false
            apply(map, center!!, zoom!!, padding)
            return
        }
        val (cx, cy) = center!!
        val x = spring(cx, centerVelocity.first, goalCenter.first, centerOmega, step)
        val y = spring(cy, centerVelocity.second, goalCenter.second, centerOmega, step)
        val z = spring(zoom!!, zoomVelocity, goal.second, zoomOmega, step)
        val pixel = 0.25 / (512 * 2.0.pow(z.first))
        center = x.first to y.first
        centerVelocity = x.second to y.second
        zoom = z.first
        zoomVelocity = z.second
        // Settled to within a fraction of a pixel: the map is left be so it can go idle.
        if (abs(x.first - goalCenter.first) < pixel && abs(y.first - goalCenter.second) < pixel && abs(z.first - goal.second) < 0.002 &&
            hypot(x.second, y.second) * step < pixel && abs(z.second) * step < 0.002
        ) {
            if (abs(map.cameraPosition.zoom - goal.second) < 0.002) return
            center = goalCenter
            zoom = goal.second
        }
        apply(map, center!!, zoom!!, padding)
    }

    /** The tip in Mercator, and a smoothed estimate of how fast it moves. */
    private fun trackFocus(seconds: Double, focus: Pair<Double, Double>?, currentZoom: Double): Pair<Double, Double>? {
        val before = lastFocus
        lastFocus = focus
        if (focus == null || before == null || seconds == 0.0) {
            focusVelocity = 0.0 to 0.0
            return focus
        }
        val moved = hypot(focus.first - before.first, focus.second - before.second) * 512 * 2.0.pow(currentZoom)
        val smoothing = 1 - exp(-seconds / 0.15)
        // A tip moving further than this in one frame jumped, a skip, rather than drove.
        val velocity = if (moved > JUMP_PIXELS) 0.0 to 0.0 else (focus.first - before.first) / seconds to (focus.second - before.second) / seconds
        focusVelocity = focusVelocity.first + (velocity.first - focusVelocity.first) * smoothing to
            focusVelocity.second + (velocity.second - focusVelocity.second) * smoothing
        return focus
    }

    private fun target(map: MapLibreMap, shot: CameraShot, padding: IntArray): Triple<String, Double, Pair<Double, Double>?>? {
        val key = "${map.width}x${map.height}:${padding.toList()}"
        target?.takeIf { it.first == key }?.let { return it }
        val start = shot.points.firstOrNull() ?: return Triple(key, map.cameraPosition.zoom, null).also { target = it }
        // Following starts centred on the stretch's start, so it has to see as far the other way too.
        val corners = shot.points + if (shot.follow) shot.points.map { (2 * start.first - it.first) to (2 * start.second - it.second) } else emptyList()
        val zoom = if (corners.toSet().size < 2) {
            shot.maxZoom
        } else {
            val bounds = LatLngBounds.Builder().includes(corners.map { it.latLng() }).build()
            minOf(map.getCameraForLatLngBounds(bounds, padding)?.zoom ?: shot.maxZoom, shot.maxZoom)
        }
        val center = if (shot.follow) null else {
            val projected = shot.points.map(::toMercator)
            (projected.minOf { it.first } + projected.maxOf { it.first }) / 2 to (projected.minOf { it.second } + projected.maxOf { it.second }) / 2
        }
        return Triple(key, zoom, center).also { target = it }
    }

    private fun apply(map: MapLibreMap, center: Pair<Double, Double>, zoom: Double, padding: IntArray) {
        val position = CameraPosition.Builder().target(fromMercator(center).latLng()).zoom(zoom).bearing(0.0).tilt(0.0)
            .padding(padding[0].toDouble(), padding[1].toDouble(), padding[2].toDouble(), padding[3].toDouble()).build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    private companion object {
        // Seconds to settle: the centre keeps up closely, the zoom drifts.
        const val CENTER_SETTLE = 0.8
        const val ZOOM_SETTLE = 2.6
        const val JUMP_PIXELS = 160.0
    }
}

/** One exact step of a critically damped spring toward [goal], stable at any frame rate: the value and its velocity. */
private fun spring(value: Double, velocity: Double, goal: Double, omega: Double, seconds: Double): Pair<Double, Double> {
    val offset = value - goal
    val decay = exp(-omega * seconds)
    val drift = (velocity + omega * offset) * seconds
    return goal + (offset + drift) * decay to (velocity - omega * drift) * decay
}

/** Web Mercator, the world 0 to 1 across and down. */
private fun toMercator(point: Pair<Double, Double>): Pair<Double, Double> {
    val latitude = point.second.coerceIn(-85.051129, 85.051129) * PI / 180
    return (point.first + 180) / 360 to (1 - ln(tan(latitude) + 1 / kotlin.math.cos(latitude)) / PI) / 2
}

private fun fromMercator(point: Pair<Double, Double>): Pair<Double, Double> =
    point.first * 360 - 180 to atan(sinh(PI * (1 - 2 * point.second))) * 180 / PI
