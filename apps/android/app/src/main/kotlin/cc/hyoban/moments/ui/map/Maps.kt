package cc.hyoban.moments.ui.map

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.hyoban.moments.data.local.currentLocation
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.gt
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.raw
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.sum
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toString
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleSortKey
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.circleTranslate
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.sin
import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

// A vector basemap drawn on the phone by MapLibre, the styles the settings name for light
// and dark (OpenFreeMap's Positron and its dark twin unless changed), every name in Chinese
// where the map has one, the Chinese drawn with the phone's own fonts. The dots and the
// route are layers over it in the app's colours.

/** Where China's middle is, roughly: where the map opens before it knows better. */
private val CHINA = CameraPosition.Builder().target(LatLng(32.0, 110.0)).zoom(3.0).build()

/** No closer than a town's streets when framing a place or a short trip. */
private const val CLOSEST = 12.0

private const val PLACES = "places"
private const val PLACES_SHADOW = "places-shadow"
private const val PLACES_COUNT = "places-count"
private const val PLACES_NAMES = "places-names"
private const val PLACES_BLOCK = "places-block"
private const val DOT_BLOCK = "dot-block"
private const val DOT_BLOCK_SIZE = 32

/** How much of [DOT_BLOCK]'s square covers a dot of [radius] and its ring. */
private fun dotBlock(radius: Float) = (radius + 2f) * 2 / DOT_BLOCK_SIZE
private const val ROUTE = "route"
private const val FOOTPRINT_LINES = "footprint-lines"
private const val FOOTPRINT_DOTS = "footprint-dots"
private const val ROUTES_DASHED = "routes-dashed"
private const val STOPS = "stops"
private const val FOG = "fog"
private const val FOG_MAX_ZOOM = 10
private const val FOG_WATER = "fog-water"

/**
 * The fog, as the site's: the page's ink, as thick as [FOG_THICK] over everywhere not been to, and
 * over a city been to thinner by the log of its Moments plus one, to [FOG_FIRST] of that for one
 * and none from about twenty ([FOG_CLEAR]), on a fixed scale so a city keeps its fog as others fill
 * up. Light, so it can be left on; on the dark map the ink is light, a haze over it.
 */
private const val FOG_THICK = 0.22
private const val FOG_FIRST = 0.6
private val FOG_CLEAR = ln(21.0)

/**
 * The rings the fog sweeps in by, out from the screen's middle: each a layer of its own whose
 * regions are set once a sweep starts, so each frame only sets a ring's opacity, which MapLibre
 * draws at once, rather than an opacity worked out region by region, which it lays the regions
 * out again for, a frame or more late.
 */
private const val FOG_RINGS = 24
private fun fogRing(index: Int) = "fog-ring-$index"

/** How long the fog's sweep takes, closing in and clearing, in milliseconds, as the site's. */
private const val FOG_SWEEP_IN = 1400L
private const val FOG_SWEEP_OUT = 900L

/**
 * How wide the sweep's soft edge is, as a share of the way across what's in sight: narrow enough
 * that the fog is seen taking a city at a time, with rings enough that it doesn't step.
 */
private const val FOG_EDGE = 0.35

/** How long a sweep waits for its rings to be laid out before it starts anyway. */
private const val FOG_RINGS_READY = 500L

/**
 * The fog on the map as [fog] has it, none while it's off: its layers added the first time, and
 * swept in or out when [sweep], else set.
 */
private suspend fun showFog(
    map: Pair<MapLibreMap, Style>?,
    fog: FogOfWar?,
    regions: List<FogRegion>,
    ink: Int,
    sweep: Boolean,
) {
    val (ready, style) = map?.live ?: return
    // Read when used: on a new style they're only there once added below.
    fun rings() = List(FOG_RINGS) { style.getLayerAs<FillLayer>(fogRing(it)) }
    // Set rather than swept, a sweep cut off is over.
    if (!sweep) {
        FogRings.waiting = false
        FogRings.midway = false
    }
    if (fog == null) {
        if (style.getSource(FOG) == null) return
        if (sweep) ready.sweepFog(style, regions, entering = false)
        // Clear, and left laid out for the next sweep.
        for (ring in rings()) ring?.setProperties(fillOpacity(0f))
        return
    }
    // Read from the asset the first time the fog shows, under the routes and the dots: the fog in
    // rings, which at rest are the fog itself, so it's drawn once, and sweep it. Without
    // antialiasing, the cities and the world's tiles meet without a seam. The basemap's water again
    // over it keeps the sea, lakes and rivers clear, and the fog's coast the map's; drawn just as
    // the basemap's, it stays on the map whether the fog shows or not.
    if (style.getSource(FOG) == null) {
        // A new style, after the theme changed, say: its rings are set out afresh, nothing of the
        // last one's sweep carried over.
        FogRings.key = null
        FogRings.color = null
        FogRings.waiting = false
        FogRings.midway = false
        // Cut into tiles no closer than a city's streets: the boundaries are simplified past that,
        // so closer tiles would only take memory to hold the same lines.
        style.addSource(GeoJsonSource(FOG, URI(FOG_REGIONS_URI), GeoJsonOptions().withMaxZoom(FOG_MAX_ZOOM)))
        for (id in List(FOG_RINGS, ::fogRing)) {
            val layer = FillLayer(id, FOG).withProperties(fillAntialias(false), fillOpacity(0f), visibility(Property.NONE))
            // Set at once: the sweep sets each frame's opacity itself, and the style's own easing
            // would lag it.
            layer.fillOpacityTransition = TransitionOptions(0, 0, false)
            style.addLayerBelow(layer, ROUTE)
        }
        style.layers.filterIsInstance<FillLayer>().firstOrNull { it.sourceLayer == "water" }?.let { water ->
            val copy = FillLayer(FOG_WATER, water.sourceId).withSourceLayer(water.sourceLayer)
                .withProperties(water.fillColor, water.fillOpacity, water.fillPattern)
            water.filter?.let(copy::setFilter)
            style.addLayerBelow(copy, ROUTE)
        }
    }
    // How thick the fog is over each region, worked out with the regions when they're laid out
    // rather than each frame, which only sets the rings' opacity; set again only for new counts.
    val color = fogColor(fog, ink)
    if (FogRings.color != color) {
        FogRings.color = color
        for (ring in rings()) ring?.setProperties(fillColor(raw(color)))
    }
    // Turning a sweep around goes on by the rings it had, wherever the map is now.
    if (!(sweep && FogRings.turnsTo(entering = true))) ready.prepareFogRings(style, regions)
    if (sweep) ready.sweepFog(style, regions, entering = true)
    for (ring in rings()) ring?.setProperties(fillOpacity(1f))
}

/** The fog's colour: the [ink], as thick over each region as it is unfamiliar, as an expression. */
private fun fogColor(fog: FogOfWar, ink: Int): String {
    val counts = fog.weights.ifEmpty { fog.lit.associateWith { 1 } }
    val count = if (counts.isEmpty()) "0" else "[\"match\", [\"get\", \"id\"], " +
        counts.entries.joinToString(", ") { (id, weight) -> "\"$id\", $weight" } + ", 0]"
    val thickness = "[\"interpolate\", [\"linear\"], [\"ln\", [\"+\", 1, $count]], " +
        "0, $FOG_THICK, ${ln(2.0)}, ${FOG_THICK * FOG_FIRST}, $FOG_CLEAR, 0]"
    return "[\"rgba\", ${(ink shr 16) and 0xFF}, ${(ink shr 8) and 0xFF}, ${ink and 0xFF}, $thickness]"
}

/** A value from 0 to 1, eased in and out: 3v² − 2v³. */
private fun smoothstep(value: Double): Double {
    val v = value.coerceIn(0.0, 1.0)
    return v * v * (3 - 2 * v)
}

/**
 * The rings as last set: from where on screen, how near and far the regions in sight were, for
 * which regions, in which colour, and whether the map has drawn them since; and the sweep going by
 * them, or cut off midway by a toggle, for the next to turn it around from where it was.
 */
private object FogRings {
    var key: String? = null
    var near = 0.0
    var far = 0.0
    var ready = false
    var sweeping = false
    var color: String? = null
    /** Waiting for the rings to be laid out, nothing on the map moved yet. */
    var waiting = false
    /** Under way or cut off, not through: which way, and how far its edge had come. */
    var midway = false
    var entering = false
    var reach = 0.0

    /** Whether a sweep [entering] or not would turn one cut off midway around. */
    fun turnsTo(entering: Boolean) = midway && this.entering != entering
}

/**
 * Sets the rings out from the screen's middle as it is now, for the next sweep to start at once:
 * on each toggle, and each time the map comes to rest, so they're ready before the tap. Only which
 * regions each holds changes, so the fog at rest, drawn by them, stays as it is. Ready once the map
 * has laid them out, which takes it a few hundred milliseconds.
 */
private fun MapLibreMap.prepareFogRings(style: Style, regions: List<FogRegion>) {
    val center = cameraPosition.target ?: return
    val key = "${center.latitude},${center.longitude},${cameraPosition.zoom},${regions.size}"
    if (key == FogRings.key) return
    val squash = cos(Math.toRadians(center.latitude))
    fun distanceTo(x: Double, y: Double) = hypot((x - center.longitude) * squash, y - center.latitude)
    // How far from the screen's middle the regions in sight are, nearest and farthest: those
    // whose middles are on screen, as those whose middles are off it mostly are too.
    val bounds = projection.visibleRegion.latLngBounds
    val inSight = regions.filter { bounds.contains(LatLng(it.cy, it.cx)) }.map { distanceTo(it.cx, it.cy) }
    // Zoomed in on a region or two whose middles are off screen, there's no way across to sweep:
    // every region then goes in the first ring, and the fog comes and goes evenly over the whole
    // screen, over the same time.
    val even = inSight.size < 2 || inSight.max() - inSight.min() < 1e-6
    val near = if (even) 0.0 else inSight.min()
    val far = if (even) 0.0 else inSight.max()
    val width = (far - near) / FOG_RINGS
    // Held within what's in sight, so a region whose middle is off screen goes with the ring
    // farthest in sight rather than ahead of the sweep or after it. By each region's own middle,
    // as the world's regions share an id.
    val (x, y) = center.longitude to center.latitude
    val distance = "[\"max\", $near, [\"min\", $far, [\"sqrt\", [\"+\", " +
        "[\"^\", [\"*\", [\"-\", [\"coalesce\", [\"get\", \"cx\"], $x], $x], $squash], 2], " +
        "[\"^\", [\"-\", [\"coalesce\", [\"get\", \"cy\"], $y], $y], 2]]]]]"
    for (index in 0 until FOG_RINGS) {
        val from = if (index == 0) near - 1 else near + index * width
        val to = if (index == FOG_RINGS - 1) far + 1 else near + (index + 1) * width
        val inRing = when {
            even -> Expression.literal(index == 0)
            else -> raw("[\"all\", [\">=\", $distance, $from], [\"<\", $distance, $to]]")
        }
        style.getLayerAs<FillLayer>(fogRing(index))?.apply {
            // Only which regions it holds: at rest the rings are the fog.
            setFilter(inRing)
            setProperties(visibility(Property.VISIBLE))
        }
    }
    FogRings.key = key
    FogRings.near = near
    FogRings.far = far
    FogRings.ready = false
    val view = MainMap.view ?: return
    lateinit var listener: MapView.OnDidBecomeIdleListener
    listener = MapView.OnDidBecomeIdleListener {
        view.removeOnDidBecomeIdleListener(listener)
        if (FogRings.key == key) FogRings.ready = true
    }
    view.addOnDidBecomeIdleListener(listener)
}

/**
 * Sweeps the fog in, from the screen's edges to its middle, or out, from the middle to the edges,
 * as the site's. A region is reached when the sweep is as far from the screen's middle as its
 * middle; the sweep starts at the farthest region in sight and ends at the nearest, so all of it is
 * seen. The regions go into rings by how far they are, set ahead of the tap where the map allows,
 * and each frame sets each ring's opacity.
 *
 * A toggle cancels the sweep under way, and the next, going the other way, turns it around: the
 * soft edge is the same either way, so it goes on from where the edge was with each ring just as
 * thick as it was.
 */
private suspend fun MapLibreMap.sweepFog(style: Style, regions: List<FogRegion>, entering: Boolean) {
    // Cut off while it waited for its rings, the last sweep moved nothing: the fog is as this one
    // would leave it.
    if (FogRings.waiting && FogRings.entering != entering) {
        FogRings.waiting = false
        return
    }
    val turning = FogRings.turnsTo(entering)
    if (!turning) {
        prepareFogRings(style, regions)
        if (FogRings.key == null) return
    }
    FogRings.sweeping = true
    FogRings.entering = entering
    if (!turning) {
        FogRings.waiting = true
        withTimeoutOrNull(FOG_RINGS_READY) { while (!FogRings.ready) withFrameNanos { } }
        FogRings.waiting = false
    }
    val (near, far) = FogRings.near to FogRings.far
    val edge = if (far > near) (far - near) * FOG_EDGE else 1.0
    val width = (far - near) / FOG_RINGS
    val rings = List(FOG_RINGS) { style.getLayerAs<FillLayer>(fogRing(it)) }
    // Closing in, the farthest region in sight is reached at once; clearing, the nearest.
    val end = far - near + edge
    // A ring is as thick with the edge come some way one way as with it come (far − near) + edge
    // less that the other.
    val from = if (turning) (end - FogRings.reach).coerceAtLeast(0.0) else 0.0
    FogRings.midway = true
    FogRings.reach = from
    try {
        val duration = if (entering) FOG_SWEEP_IN else FOG_SWEEP_OUT
        val share = (from / end).coerceIn(0.0, 1.0)
        val first = withFrameNanos { it }
        val started = first - (duration * 1_000_000.0 * if (entering) asin(share) / (PI / 2) else share).toLong()
        while (true) {
            val now = withFrameNanos { it }
            val progress = ((now - started) / 1_000_000.0 / duration).coerceIn(0.0, 1.0)
            // Closing in, moving from the first frame and slowing as it lands, the edges it starts at
            // being thin; clearing, from the screen's broad middle, at an even pace, which clears the
            // screen at about the rate the fog closes in, where easing out burst it open.
            val reach = end * if (entering) sin(progress * PI / 2) else progress
            FogRings.reach = reach
            for (index in 0 until FOG_RINGS) {
                val ringDistance = near + (index + 0.5) * width
                // How far past the ring the sweep is, in edges: 0 as it arrives, 1 once it's through.
                val past = if (entering) (reach - (far - ringDistance)) / edge else (reach - (ringDistance - near)) / edge
                val through = smoothstep(past)
                rings[index]?.setProperties(fillOpacity((if (entering) through else 1 - through).toFloat()))
            }
            if (progress >= 1.0) break
        }
        FogRings.midway = false
    } finally {
        // Through or cut off, the rings left as the last frame set them: the fog at rest or none,
        // or midway for the next sweep to turn around.
        FogRings.sweeping = false
    }
}

/**
 * The map and its style while the style is still the map's: a new view, after the theme changed or
 * the activity came back, loads its own, and the old one, still held until the new is ready,
 * throws on use.
 */
private val Pair<MapLibreMap, Style>.live get() = takeIf { it.second.isFullyLoaded }

private val Place.latLng get() = LatLng(latitude, longitude)
private val LatLng.point get() = Point.fromLngLat(longitude, latitude)

/** Frames [points] inside [padding] pixels (left, top, right, bottom), no closer than [CLOSEST]. */
private fun MapLibreMap.frame(points: List<LatLng>, padding: IntArray) {
    if (points.size == 1) {
        moveCamera(CameraUpdateFactory.newLatLngZoom(points.single(), CLOSEST))
        return
    }
    val bounds = LatLngBounds.Builder().includes(points).build()
    moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3]))
    if (cameraPosition.zoom > CLOSEST) moveCamera(CameraUpdateFactory.zoomTo(CLOSEST))
}

/** Flies to [points] framed as [frame] frames them, turned back to north. */
/** Moves the camera, gliding there over [ms] unless [instant], when the phone has animations removed. */
private fun MapLibreMap.glide(update: CameraUpdate, ms: Int, instant: Boolean) {
    if (instant) moveCamera(update) else animateCamera(update, ms)
}

private fun MapLibreMap.flyToFrame(points: List<LatLng>, padding: IntArray, instant: Boolean = false, closest: Double = CLOSEST) {
    val camera = if (points.size == 1) {
        CameraPosition.Builder().target(points.single()).zoom(closest).bearing(0.0).build()
    } else {
        val fitted = getCameraForLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding, 0.0, 0.0) ?: return
        CameraPosition.Builder(fitted).zoom(minOf(fitted.zoom, closest)).build()
    }
    glide(CameraUpdateFactory.newCameraPosition(camera), 600, instant)
}

/**
 * Where to look from for this to show [dp] above the middle at [zoom]: as far south as that
 * is on the map, its world 512 at zoom 0 and twice as wide at each zoom beyond.
 */
private fun LatLng.southBy(dp: Double, zoom: Double): LatLng {
    val world = 512.0 * 2.0.pow(zoom)
    val y = (1 - ln(tan(PI / 4 + latitude * PI / 360)) / PI) / 2 * world + dp
    return LatLng(atan(sinh(PI * (1 - 2 * y / world))) * 180 / PI, longitude)
}

/**
 * A name in Chinese where the map has one, else the local one; of a Chinese name given as
 * several, `老挝;寮國`, the first.
 */
private val CHINESE_NAME = raw(
    """
    ["let", "name", ["coalesce", ["get", "name:zh"], ["get", "name:nonlatin"], ["get", "name"]],
      ["let", "cut", ["index-of", ";", ["var", "name"]],
        ["case", [">", ["var", "cut"], 0], ["slice", ["var", "name"], 0, ["var", "cut"]], ["var", "name"]]]]
    """,
)

/** Every label that shows a name shows its Chinese one. */
private fun chineseLabels(style: Style) {
    style.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        if (layer.textField.expression?.toString()?.contains("name") == true) {
            layer.setProperties(textField(CHINESE_NAME))
        }
    }
}

/** How much of the basemap's main roads and railways show: enough to place things by, not enough to pass for a route. */
private const val ROADS_OPACITY = 0.25f

/** The basemap's lesser lines, by their layers' names: streets, paths, piers, sidings and the dashes over rail lines. */
private val LESSER_ROADS = Regex("minor|path|pier|service|transit|dashline|taxiway|subtle")

/**
 * The basemap's streets and lesser lines left out, its main roads and railways faded,
 * and their names, shields and arrows left out, so the trips' routes are the lines that
 * read; the towns, the water and the borders stay.
 */
private fun quietRoads(style: Style) {
    for (layer in style.layers) {
        val roads = when (layer) {
            is LineLayer -> layer.sourceLayer in setOf("transportation", "aeroway")
            is FillLayer -> layer.sourceLayer in setOf("transportation", "aeroway")
            is SymbolLayer -> layer.sourceLayer in setOf("transportation", "transportation_name")
            else -> false
        }
        when {
            !roads -> {}
            layer is SymbolLayer || LESSER_ROADS.containsMatchIn(layer.id) -> layer.setProperties(visibility(Property.NONE))
            layer is LineLayer -> layer.setProperties(lineOpacity(ROADS_OPACITY))
            layer is FillLayer -> layer.setProperties(fillOpacity(ROADS_OPACITY))
        }
    }
}

/**
 * A MapLibre map for the theme, following the screen's lifecycle and rebuilt when the
 * theme or the style set for it changes; [onReady] gets it once its style has loaded and it has its size. Unless
 * [interactive], it takes no touches, so a list around it still scrolls.
 */
@Composable
internal fun MapLibreView(
    modifier: Modifier,
    interactive: Boolean,
    attributionPadding: IntArray? = null,
    /** Whether MapLibre's own compass shows once the map is turned; off where the screen has its own. */
    compass: Boolean = true,
    retained: Boolean = false,
    onReady: (map: MapLibreMap, style: Style, fresh: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val styleUri = LocalSettings.current.mapStyle(LocalDarkTheme.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(onReady)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    val holder = if (retained) MainMap else null
    val mapView = remember(styleUri, context) {
        holder?.view?.takeIf { holder.styleUri == styleUri && it.context === context }
            ?: run {
                MapLibre.getInstance(context)
                val options = MapLibreMapOptions.createFromAttributes(context)
                    .camera(CHINA)
                    // Drawn into a texture, so rounded corners and sheets clip it like any view.
                    .textureMode(true)
                    .localIdeographFontFamily("sans-serif")
                MapView(context, options).apply { onCreate(null) }.also { view ->
                    holder?.let {
                        it.view?.onDestroy()
                        it.view = view
                        it.styleUri = styleUri
                        it.map = null
                        it.style = null
                    }
                }
            }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            // A retained map is only stopped, to be shown again as it was.
            if (holder == null) mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        // Shown again: its style, its layers and where it looked are all still there.
        val kept = holder?.map
        val keptStyle = holder?.style
        if (kept != null && keptStyle != null) {
            map = kept
            mapView.doOnLayout { _ -> ready(kept, keptStyle, false) }
            return@LaunchedEffect
        }
        mapView.getMapAsync {
            it.uiSettings.apply {
                setAllGesturesEnabled(interactive)
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isCompassEnabled = interactive && compass
                attributionGravity = Gravity.BOTTOM or Gravity.START
            }
            map = it
            it.setStyle(Style.Builder().fromUri(styleUri)) { style ->
                chineseLabels(style)
                quietRoads(style)
                holder?.map = it
                holder?.style = style
                mapView.doOnLayout { _ -> ready(it, style, true) }
            }
        }
    }

    // The window's insets can arrive after the map does; the credit moves with them.
    val credit = attributionPadding?.toList()
    LaunchedEffect(map, credit) {
        credit?.let { (left, top, right, bottom) -> map?.uiSettings?.setAttributionMargins(left, top, right, bottom) }
    }

    Box(modifier) {
        // A retained map may still sit in the view it was shown in last.
        AndroidView({ (mapView.parent as? ViewGroup)?.removeView(mapView); mapView }, Modifier.matchParentSize())
        if (!interactive) Box(Modifier.matchParentSize().pointerInput(Unit) {})
    }
}

/**
 * The map tab's map, kept across switching tabs rather than built again with its style,
 * its labels and its tiles each time the tab comes back. Only stopped while away.
 */
private object MainMap {
    var view: MapView? = null
    var styleUri: String? = null
    var map: MapLibreMap? = null
    var style: Style? = null

    /** The screen's shape the places were last framed for: on its side or not, and which side the tabs stood down. */
    var framedFor: Pair<Boolean, Boolean>? = null

    /** Whether the fog has been shown since the app started; left on, it sweeps in the first time. */
    var fogOpened = false
}

/** Pixels from each edge, given as left and right whichever way the text runs. */
@Composable
private fun absoluteEdges(left: Dp, top: Dp, right: Dp, bottom: Dp): IntArray = with(LocalDensity.current) {
    intArrayOf(left.roundToPx(), top.roundToPx(), right.roundToPx(), bottom.roundToPx())
}

/** Pixels from each edge (left, top, right, bottom). */
@Composable
internal fun edges(start: Dp, top: Dp, end: Dp, bottom: Dp): IntArray =
    if (LocalLayoutDirection.current == LayoutDirection.Ltr) absoluteEdges(start, top, end, bottom) else absoluteEdges(end, top, start, bottom)

/** A place on the map: how many Moments it has, and the year of its first, which colours it. */
data class MapPlace(val id: String, val place: Place, val count: Int, val firstYear: Int)

/** A trip's route on the map, in stretches. */
data class MapRoute(val id: String, val segments: List<RouteSegment>)

// The site's dots, in its pixels: a place with one Moment, one with more (its number on
// it), and a crowd of places gathered into one (the number of their Moments on it).
private const val DOT_SINGLE = 7f
private const val DOT_MANY = 9f
private const val DOT_CLUSTER = 12f
/** How much larger the chosen dot is drawn. */
private const val DOT_GROWTH = 0.25f
/** How much of the map the place's sheet covers when it first rises, as the map tab peeks it. */
internal const val PLACE_SHEET_SHARE = 0.55f

/**
 * How far from a dot's edge a tap still reaches it: with the smallest dot's radius and ring,
 * 24dp from its middle, so every dot is a 48dp target.
 */
private val TAP_REACH = 16.dp

/** Of [features], the one drawn nearest [at] on the screen. */
private fun MapLibreMap.nearest(features: List<Feature>, at: PointF): Feature? = features.minByOrNull { feature ->
    val point = feature.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
    val on = projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
    (on.x - at.x) * (on.x - at.x) + (on.y - at.y) * (on.y - at.y)
}
// Places gather where three or more crowd within 20 pixels, up to zoom 9; opening a
// crowd frames its places, no closer than zoom 11.
private const val CLUSTER_RADIUS = 20
private const val CLUSTER_MIN_POINTS = 3
private const val CLUSTER_MAX_ZOOM = 9
private const val CLUSTER_OPEN_MAX_ZOOM = 11.0
// The routes: lines under the places in the site's ink, which read now the basemap's
// streets are gone and its roads faded; a little bolder than the site's for a phone, and
// widening as the map zooms in. The one tapped stands out, the rest fall back.
internal const val ROUTE_OPACITY = 0.6f
private const val ROUTE_WIDTH = 2f
private const val ROUTE_LIFTED_WIDTH = 3.5f
private const val ROUTE_DIMMED_OPACITY = 0.15f

/** How close the map comes to the phone's place: a town and what is around it. */
private const val NEARBY_ZOOM = 10.0


/** A route's [width] at country zoom, growing to twice it at a town's streets. */
private fun byZoom(width: Expression): Expression = interpolate(
    linear(),
    zoom(),
    stop(4, width),
    stop(10, product(width, literal(1.4f))),
    stop(14, product(width, literal(2f))),
)

private val RouteSegment.lineString get() = LineString.fromLngLats(line.map { (longitude, latitude) -> Point.fromLngLat(longitude, latitude) })

/** The colour of a place or a crowd: its first year's. */
private fun yearColor(colors: Map<Int, Color>, unknown: Int): Expression {
    val stops = colors.map { (year, color) -> stop(year, color(color.toArgb())) }.toTypedArray()
    return if (stops.isEmpty()) color(unknown) else match(get("firstYear"), color(unknown), *stops)
}

/** The first layer of the basemap's labels, which the routes go under. */
internal fun Style.labelsLayer(): String? = layers.firstOrNull { it is SymbolLayer }?.id

/**
 * The places the Moments name, coloured by the year of their first Moment, and the
 * routes of the trips. A place reports [onPlace] when tapped; [selected] stands out. A
 * crowd of places opens up when tapped. A route tapped is lifted with [onRoute], and
 * tapped again, [highlighted] by then, opens with [onTrip]; a tap on nothing lets it go.
 */
// Called only once the screen has the location permission.
@SuppressLint("MissingPermission")
@Composable
fun PlacesMap(
    places: List<MapPlace>,
    colors: Map<Int, Color>,
    routes: List<MapRoute>,
    selected: String?,
    highlighted: String?,
    onPlace: (String) -> Unit,
    onRoute: (String?) -> Unit,
    onTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
    locate: Int = 0,
    /** Each time it goes up, the map turns back to north. */
    north: Int = 0,
    /** Which way the map is turned, in degrees from north, as it moves. */
    onBearing: (Double) -> Unit = {},
    onLocated: (Boolean) -> Unit = {},
    /** A tap on the map away from any place or route. */
    onEmptyTap: () -> Unit = {},
    /** The tab the map is on, which, tapped again while shown, brings every place back into view. */
    tab: Any? = null,
    /** A place or a trip to fly to, once the map is ready, then [onFocused]. */
    focus: MapFocus? = null,
    onFocused: () -> Unit = {},
    /** The fog of war over every city not been to; none while it's off. */
    fog: FogOfWar? = null,
    /** Each time it goes up, the fog's next change is swept rather than set. */
    fogSweep: Int = 0,
    /** The regions the fog is drawn over, for how far each is from the cities been to. */
    fogRegions: List<FogRegion> = emptyList(),
    /** The selected local GPS track, over the same map as places and published trip routes. */
    footprints: List<List<cc.hyoban.moments.footprints.TrackPoint>> = emptyList(),
    frameFootprints: Boolean = false,
    onFootprintsFramed: () -> Unit = {},
    onFootprint: () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val tapPlace by rememberUpdatedState(onPlace)
    val located by rememberUpdatedState(onLocated)
    val tapEmpty by rememberUpdatedState(onEmptyTap)
    val context = LocalContext.current
    val me = MaterialTheme.colorScheme.primary.toArgb()
    val tapRoute by rememberUpdatedState(onRoute)
    val openTrip by rememberUpdatedState(onTrip)
    val lifted by rememberUpdatedState(highlighted)
    val fogRegionsNow by rememberUpdatedState(fogRegions)
    val tapFootprint by rememberUpdatedState(onFootprint)
    val footprintLocations = remember(footprints) { footprints.flatten().map { LatLng(it.latitude, it.longitude) } }
    val allLocations = remember(places, footprintLocations) { places.map { it.place.latLng } + footprintLocations }
    var map by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var framed by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    // The ring round every dot but the one chosen, the page's surface; the number on a dot white, as
    // on the site's map, its year colours being the site's rather than Material 3's (MapScreen).
    val ring = MaterialTheme.colorScheme.surface.toArgb()
    val onDot = Color.White.toArgb()
    val shade = MaterialTheme.colorScheme.scrim.toArgb()
    val unknown = MaterialTheme.colorScheme.outline.toArgb()
    val density = LocalDensity.current
    // Far enough round a place that the dot takes the 48dp Material 3 has anything tapped take.
    val reach = with(density) { TAP_REACH.toPx() }
    val routeReach = with(density) { 22.dp.toPx() }
    val clusterPadding = with(density) { 80.dp.roundToPx() }

    // The map runs under the system bars and the tabs; its credit stays clear of them.
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val bars = with(density) {
        listOf(insets.getLeft(this, direction), insets.getTop(this), insets.getRight(this, direction), insets.getBottom(this)).map { it.toDp() }
    }
    val clearance = LocalBottomClearance.current
    val rail = LocalRailSpace.current
    // In past the tabs and their buttons when they stand down a side.
    val credit = absoluteEdges(bars[0] + rail.left + 8.dp, 0.dp, bars[2] + rail.right + 8.dp, bars[3] + clearance + 8.dp)
    // The places fill the screen but for the legend over the top and the tabs, below or,
    // on a phone on its side, down a side.
    val landscape = isLandscape()
    val frame = if (landscape) {
        absoluteEdges(bars[0] + rail.left + 24.dp, bars[1] + 56.dp, bars[2] + rail.right + 24.dp, bars[3] + clearance + 16.dp)
    } else {
        edges(bars[0] + 32.dp, bars[1] + 96.dp, bars[2] + 32.dp, bars[3] + clearance + 16.dp)
    }

    MapLibreView(modifier, interactive = true, attributionPadding = credit, compass = false, retained = true) { ready, style, fresh ->
        // Shown again, it keeps its layers and where it was looking.
        framed = !fresh
        map = ready to style
        if (!fresh) return@MapLibreView
        val below = style.labelsLayer()
        style.addSource(GeoJsonSource(ROUTE))
        val solid = LineLayer(ROUTE, ROUTE).withFilter(not(get("straight")))
            .withProperties(lineColor(ink), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
        val dashed = LineLayer(ROUTES_DASHED, ROUTE).withFilter(get("straight"))
            .withProperties(lineColor(ink), lineDasharray(arrayOf(2f, 2f)))
        for (layer in listOf(solid, dashed)) {
            if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
        }

        style.addSource(
            GeoJsonSource(
                PLACES,
                GeoJsonOptions()
                    .withCluster(true)
                    .withClusterRadius(CLUSTER_RADIUS)
                    .withClusterMinPoints(CLUSTER_MIN_POINTS)
                    .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
                    .withClusterProperty("firstYear", literal("min"), get("firstYear"))
                    .withClusterProperty("momentCount", literal("+"), get("count")),
            ),
        )
        style.addLayer(
            CircleLayer(PLACES_SHADOW, PLACES).withProperties(
                circleColor(shade),
                circleOpacity(0.18f),
                circleBlur(0.6f),
                circleTranslate(arrayOf(0f, 1.5f)),
            ),
        )
        style.addLayer(
            CircleLayer(PLACES, PLACES).withProperties(
                circleColor(yearColor(colors, unknown)),
                circleStrokeColor(ring),
            ),
        )
        style.addLayer(
            SymbolLayer(PLACES_COUNT, PLACES).withProperties(
                textField(
                    switchCase(
                        has("point_count"), toString(get("momentCount")),
                        gt(get("count"), literal(1)), toString(get("count")),
                        literal(""),
                    ),
                ),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(switchCase(has("point_count"), literal(11f), literal(10f))),
                textColor(onDot),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
        // Each place's name under its dot, at any zoom, in the ink and halo of the place picker's
        // names; a crowd's dot has none, and a name that would cover another, or the basemap's,
        // is left out until there is room for it.
        style.addLayer(
            SymbolLayer(PLACES_NAMES, PLACES).withFilter(not(has("point_count"))).withProperties(
                textField(get("name")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 1.3f)),
                textColor(ink),
                textHaloColor(ring),
                textHaloWidth(1.5f),
            ),
        )
        // Every dot, crowds too, as a box the names keep clear of, unseen: above the names, so
        // placed before them, as the map places the top of its labels first.
        style.addImage(DOT_BLOCK, Bitmap.createBitmap(DOT_BLOCK_SIZE, DOT_BLOCK_SIZE, Bitmap.Config.ARGB_8888).apply { setDensity(DisplayMetrics.DENSITY_DEFAULT) })
        style.addLayer(
            SymbolLayer(PLACES_BLOCK, PLACES).withProperties(
                iconImage(DOT_BLOCK),
                iconSize(
                    switchCase(
                        has("point_count"), literal(dotBlock(DOT_CLUSTER)),
                        gt(get("count"), literal(1)), literal(dotBlock(DOT_MANY)),
                        literal(dotBlock(DOT_SINGLE)),
                    ),
                ),
                iconOpacity(0f),
                iconAllowOverlap(true),
                iconIgnorePlacement(false),
            ),
        )
    }

    // Taps are listened for while this map is shown, with this showing's callbacks.
    DisposableEffect(map) {
        val (ready, style) = map?.live ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            val at = ready.projection.toScreenLocation(point)
            fun around(r: Float) = RectF(at.x - r, at.y - r, at.x + r, at.y + r)
            // The nearest of those in reach, as reaching that far takes in neighbours.
            val place = ready.nearest(ready.queryRenderedFeatures(around(reach), PLACES), at)
            if (place != null) {
                if (place.hasProperty("point_count")) {
                    val leaves = style.getSourceAs<GeoJsonSource>(PLACES)?.getClusterLeaves(place, Long.MAX_VALUE, 0)
                    val points = leaves?.features()?.mapNotNull { (it.geometry() as? Point)?.let { p -> LatLng(p.latitude(), p.longitude()) } }
                    if (!points.isNullOrEmpty()) {
                        val bounds = LatLngBounds.Builder().includes(points).build()
                        val camera = ready.getCameraForLatLngBounds(bounds, IntArray(4) { clusterPadding })
                        if (camera != null) {
                            val zoom = minOf(camera.zoom, CLUSTER_OPEN_MAX_ZOOM)
                            ready.glide(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(camera).zoom(zoom).build()), 300, reduced)
                        }
                    }
                } else {
                    place.getStringProperty("id")?.let(tapPlace)
                }
                return@OnMapClickListener true
            }
            if (style.getLayer(FOOTPRINT_LINES) != null &&
                ready.queryRenderedFeatures(around(routeReach), FOOTPRINT_LINES, FOOTPRINT_DOTS).isNotEmpty()
            ) {
                tapFootprint()
                return@OnMapClickListener true
            }
            val trip = ready.queryRenderedFeatures(around(routeReach), ROUTE, ROUTES_DASHED).firstOrNull()?.getStringProperty("trip")
            when {
                trip != null && trip == lifted -> openTrip(trip)
                trip != null -> tapRoute(trip)
                lifted != null -> tapRoute(null)
            }
            if (trip == null) tapEmpty()
            trip != null
        }
        ready.addOnMapClickListener(listener)
        // The fog's rings set out from wherever the map comes to rest, ready for the next sweep.
        val idle = MapLibreMap.OnCameraIdleListener {
            if (style.getSource(FOG) != null && !FogRings.sweeping && !FogRings.midway) ready.prepareFogRings(style, fogRegionsNow)
        }
        ready.addOnCameraIdleListener(idle)
        onDispose {
            ready.removeOnMapClickListener(listener)
            ready.removeOnCameraIdleListener(idle)
        }
    }

    OnTabReselect(tab) {
        val (ready, _) = map ?: return@OnTabReselect
        if (allLocations.isNotEmpty()) ready.flyToFrame(allLocations, frame, reduced)
    }

    val shape = landscape to (rail.left > 0.dp)
    LaunchedEffect(map, places, shape, allLocations) {
        val (ready, style) = map?.live ?: return@LaunchedEffect
        val features = places.map { place ->
            Feature.fromGeometry(place.place.latLng.point).apply {
                addStringProperty("id", place.id)
                addNumberProperty("count", place.count)
                addNumberProperty("firstYear", place.firstYear)
                addStringProperty("name", place.place.name)
            }
        }
        style.getSourceAs<GeoJsonSource>(PLACES)?.setGeoJson(FeatureCollection.fromFeatures(features))
        // Opens on every place once there are places, and stays where it's moved after
        // that, until the phone turns: then every place again, to fill the screen's new shape.
        if ((!framed || MainMap.framedFor != shape) && allLocations.isNotEmpty()) {
            MainMap.framedFor = shape
            // Once the map has been laid out at the screen's new shape; only then framed, so a
            // place or a trip to fly to waits for it rather than being framed over.
            withFrameNanos { }
            ready.frame(allLocations, frame)
            framed = true
        }
    }

    // A dedicated pair of sources on the retained main map. Empty data hides the layer;
    // updates preserve the camera, the place markers and the existing route interactions.
    LaunchedEffect(map, footprints, me, ring) {
        val (_, style) = map?.live ?: return@LaunchedEffect
        if (style.getSource(FOOTPRINT_LINES) == null) {
            style.addSource(GeoJsonSource(FOOTPRINT_LINES))
            style.addSource(GeoJsonSource(FOOTPRINT_DOTS))
            style.addLayerBelow(LineLayer(FOOTPRINT_LINES, FOOTPRINT_LINES).withProperties(
                lineWidth(3f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            ), PLACES_SHADOW)
            style.addLayerBelow(CircleLayer(FOOTPRINT_DOTS, FOOTPRINT_DOTS).withProperties(
                circleRadius(4f), circleStrokeWidth(1.5f),
            ), PLACES_SHADOW)
        }
        val lines = footprints.filter { it.size > 1 }.map { segment ->
            Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }
        val dots = footprints.flatMap { listOf(it.first(), it.last()).distinct() }
            .map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        style.getSourceAs<GeoJsonSource>(FOOTPRINT_LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines))
        style.getSourceAs<GeoJsonSource>(FOOTPRINT_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dots))
        style.getLayerAs<LineLayer>(FOOTPRINT_LINES)?.setProperties(lineColor(me))
        style.getLayerAs<CircleLayer>(FOOTPRINT_DOTS)?.setProperties(circleColor(me), circleStrokeColor(ring))
    }

    LaunchedEffect(map, frameFootprints, framed, footprintLocations) {
        if (!frameFootprints || !framed || footprintLocations.isEmpty()) return@LaunchedEffect
        val (ready, _) = map?.live ?: return@LaunchedEffect
        ready.flyToFrame(footprintLocations, frame, reduced, closest = 17.0)
        onFootprintsFramed()
    }

    // The site's fog, in the ink: turned on, it closes in from the screen's edges to its middle;
    // turned off, it clears from the middle outwards. A toggle while it sweeps turns it around.
    val sweepAsked by rememberUpdatedState(fogSweep)
    val swept = remember { intArrayOf(fogSweep) }
    LaunchedEffect(map, fog, ink, framed) {
        // Left on, it sweeps in as the map first opens, once the places are framed.
        val opening = fog != null && !MainMap.fogOpened
        if (opening && !framed) return@LaunchedEffect
        if (fog != null) MainMap.fogOpened = true
        // Taken as it starts rather than once it's through, so a toggle cutting it off is swept too.
        val sweep = (sweepAsked != swept[0] || opening) && !reduced
        swept[0] = sweepAsked
        showFog(map, fog, fogRegionsNow, ink, sweep)
    }

    LaunchedEffect(map, routes) {
        val (_, style) = map?.live ?: return@LaunchedEffect
        // Oldest first, so the latest trip's route is drawn on top where routes cross.
        val features = routes.reversed().flatMap { route ->
            route.segments.map { segment ->
                Feature.fromGeometry(segment.lineString).apply {
                    addStringProperty("trip", route.id)
                    addBooleanProperty("straight", segment.straight)
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // The dot chosen grows and the one let go shrinks back, and a lifted route stands out as the
    // rest fall back, on the motion scheme's fast spatial spring a frame at a time: the map's own
    // transitions don't reach a property that differs from dot to dot.
    val change = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val before = remember { arrayOfNulls<String>(2) }
    // Where the place's sheet leaves the map in sight: under the legend and above the sheet's half.
    val clearTop = with(density) { (bars[1] + 72.dp).toPx() }
    val sheetMargin = with(density) { 48.dp.toPx() }
    // A place or a trip shown from its page: the place flown to the middle of what its sheet
    // leaves in sight, the trip's route framed above its card. The place's own nudge into
    // sight below then leaves it be.
    val focused by rememberUpdatedState(onFocused)
    val flownTo = remember { arrayOfNulls<String>(1) }
    val cardRoom = with(density) { 112.dp.roundToPx() }
    LaunchedEffect(map, focus, framed, places, routes) {
        val target = focus ?: return@LaunchedEffect
        if (target is MapFocus.Place) flownTo[0] = target.id
        val (ready, _) = map ?: return@LaunchedEffect
        if (!framed) return@LaunchedEffect
        when (target) {
            is MapFocus.Place -> {
                val at = places.firstOrNull { it.id == target.id }?.place?.latLng ?: return@LaunchedEffect
                val zoom = maxOf(ready.cameraPosition.zoom, NEARBY_ZOOM)
                val clearBottom = ready.height * (1f - PLACE_SHEET_SHARE) - sheetMargin
                val above = (ready.height / 2f - (clearTop + clearBottom) / 2f) / density.density
                val camera = CameraPosition.Builder().target(at.southBy(above.toDouble(), zoom)).zoom(zoom).bearing(0.0).build()
                ready.glide(CameraUpdateFactory.newCameraPosition(camera), 600, reduced)
            }
            is MapFocus.Trip -> {
                val route = routes.firstOrNull { it.id == target.id } ?: return@LaunchedEffect
                val points = route.segments.flatMap { segment -> segment.line.map { (longitude, latitude) -> LatLng(latitude, longitude) } }
                if (points.isNotEmpty()) ready.flyToFrame(points, frame.copyOf().also { it[3] += cardRoom }, reduced)
            }
            MapFocus.Footprints -> Unit // MapScreen opens the controls without moving the camera.
        }
        focused()
    }

    LaunchedEffect(map, selected) {
        val (ready, style) = map?.live ?: return@LaunchedEffect
        val previous = before[0]
        before[0] = selected
        val chosen = eq(get("id"), literal(selected ?: ""))
        val letGo = eq(get("id"), literal(previous ?: ""))
        val radius = switchCase(
            has("point_count"), literal(DOT_CLUSTER),
            gt(get("count"), literal(1)), literal(DOT_MANY),
            literal(DOT_SINGLE),
        )
        fun draw(t: Float) {
            val grown = switchCase(chosen, product(radius, literal(1f + DOT_GROWTH * t)), letGo, product(radius, literal(1f + DOT_GROWTH * (1f - t))), radius)
            style.getLayerAs<CircleLayer>(PLACES)?.setProperties(
                circleRadius(grown),
                circleStrokeWidth(switchCase(chosen, literal(3f), literal(2f))),
                circleStrokeColor(switchCase(chosen, color(ink), color(ring))),
                circleSortKey(switchCase(chosen, literal(1f), literal(0f))),
            )
            style.getLayerAs<CircleLayer>(PLACES_SHADOW)?.setProperties(circleRadius(sum(grown, literal(3f))))
        }
        val place = places.firstOrNull { it.id == selected }?.place?.latLng
        val flown = flownTo[0] == selected
        flownTo[0] = null
        if (place != null && !flown) {
            // Brought up into sight when its sheet would cover it, gliding with the sheet as it rises.
            val at = ready.projection.toScreenLocation(place)
            val clearBottom = ready.height * (1f - PLACE_SHEET_SHARE) - sheetMargin
            if (at.y > clearBottom || at.y < clearTop) {
                val center = PointF(ready.width / 2f, ready.height / 2f + at.y - (clearTop + clearBottom) / 2f)
                ready.glide(CameraUpdateFactory.newLatLng(ready.projection.fromScreenLocation(center)), 400, reduced)
            }
        }
        if (reduced || previous == selected) draw(1f) else animate(0f, 1f, animationSpec = change) { t, _ -> draw(t) }
    }

    // Which way the map is turned, told as it moves and once it is first shown.
    val bearing by rememberUpdatedState(onBearing)
    DisposableEffect(map) {
        val ready = map?.first ?: return@DisposableEffect onDispose {}
        val moved = MapLibreMap.OnCameraMoveListener { bearing(ready.cameraPosition.bearing) }
        bearing(ready.cameraPosition.bearing)
        ready.addOnCameraMoveListener(moved)
        onDispose { ready.removeOnCameraMoveListener(moved) }
    }
    LaunchedEffect(map, north) {
        val (ready, _) = map ?: return@LaunchedEffect
        if (north > 0) ready.glide(CameraUpdateFactory.bearingTo(0.0), 400, reduced)
    }

    // Each time [locate] goes up, with the permission granted by then: the phone's place
    // as a dot, and the map brought to it at a town's scale, its nearby places around it.
    LaunchedEffect(map, locate) {
        val (ready, style) = map?.live ?: return@LaunchedEffect
        if (locate == 0) return@LaunchedEffect
        val component = ready.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.Builder(context, style)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(
                        LocationComponentOptions.builder(context)
                            .foregroundTintColor(me)
                            .bearingTintColor(me)
                            .accuracyColor(me)
                            .accuracyAlpha(0.12f)
                            .pulseEnabled(true)
                            .build(),
                    )
                    .build(),
            )
        }
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.NORMAL
        val fix = currentLocation(context)
        if (fix == null) {
            located(false)
            return@LaunchedEffect
        }
        component.forceLocationUpdate(fix)
        val zoom = maxOf(ready.cameraPosition.zoom, NEARBY_ZOOM)
        ready.glide(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), zoom), 800, reduced)
        located(true)
    }

    LaunchedEffect(map, highlighted) {
        val (_, style) = map?.live ?: return@LaunchedEffect
        val previous = before[1]
        before[1] = highlighted
        // Each route's opacity and width with [lifted] standing out, or all alike with none.
        fun opacity(lifted: String?) =
            if (lifted == null) literal(ROUTE_OPACITY) else switchCase(eq(get("trip"), literal(lifted)), literal(1f), literal(ROUTE_DIMMED_OPACITY))
        fun width(lifted: String?) =
            if (lifted == null) literal(ROUTE_WIDTH) else switchCase(eq(get("trip"), literal(lifted)), literal(ROUTE_LIFTED_WIDTH), literal(ROUTE_WIDTH))
        fun between(from: Expression, to: Expression, t: Float) = sum(product(from, literal(1f - t)), product(to, literal(t)))
        fun draw(t: Float) {
            val opacity = between(opacity(previous), opacity(highlighted), t)
            val width = between(width(previous), width(highlighted), t)
            for (id in listOf(ROUTE, ROUTES_DASHED)) {
                style.getLayerAs<LineLayer>(id)?.setProperties(lineOpacity(opacity), lineWidth(byZoom(width)))
            }
        }
        if (reduced || previous == highlighted) draw(1f) else animate(0f, 1f, animationSpec = change) { t, _ -> draw(t) }
    }
}

/**
 * A trip's route along the roads where [segments] has it, else its stops joined straight,
 * framed to fit; a still picture unless [interactive], so a list around it still scrolls.
 */
@Composable
fun TripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier = Modifier, interactive: Boolean = false) {
    val points = remember(stops) { stops.map { it.place.latLng } }
    val lines = remember(points, segments) {
        segments.ifEmpty { if (points.size > 1) listOf(RouteSegment(points.map { it.longitude to it.latitude }, straight = false)) else emptyList() }
    }
    PointsMap(points, lines, dot = 5f, padding = 40.dp, modifier = modifier, interactive = interactive)
}

/** A still map of [places], each a dot: one place close up, several framed together. */
@Composable
fun PlacesStill(places: List<Place>, modifier: Modifier = Modifier) {
    val points = remember(places) { places.map { it.latLng } }
    PointsMap(points, emptyList(), dot = 6f, padding = 32.dp, modifier = modifier, interactive = false)
}

@Composable
private fun PointsMap(points: List<LatLng>, lines: List<RouteSegment>, dot: Float, padding: Dp, modifier: Modifier, interactive: Boolean) {
    val line = MaterialTheme.colorScheme.primary.toArgb()
    val ring = MaterialTheme.colorScheme.surface.toArgb()
    val inset = edges(padding, padding, padding, padding)

    MapLibreView(modifier, interactive) { map, style, _ ->
        if (lines.isNotEmpty()) {
            style.addSource(
                GeoJsonSource(
                    ROUTE,
                    FeatureCollection.fromFeatures(
                        lines.map { segment -> Feature.fromGeometry(segment.lineString).apply { addBooleanProperty("straight", segment.straight) } },
                    ),
                ),
            )
            style.addLayer(
                LineLayer(ROUTE, ROUTE).withFilter(not(get("straight"))).withProperties(
                    lineColor(line),
                    lineWidth(3f),
                    lineOpacity(0.8f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(ROUTES_DASHED, ROUTE).withFilter(get("straight")).withProperties(
                    lineColor(line),
                    lineWidth(2.5f),
                    lineOpacity(0.8f),
                    lineDasharray(arrayOf(2f, 2f)),
                ),
            )
        }
        style.addSource(GeoJsonSource(STOPS, FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(it.point) })))
        style.addLayer(
            CircleLayer(STOPS, STOPS).withProperties(
                circleRadius(dot),
                circleColor(line),
                circleStrokeWidth(2f),
                circleStrokeColor(ring),
            ),
        )
        val framed = points + lines.flatMap { segment -> segment.line.map { (longitude, latitude) -> LatLng(latitude, longitude) } }
        if (framed.isNotEmpty()) map.frame(framed, inset)
    }
}

private const val PICK_PLACES = "pick-places"
private const val PICK_NAMES = "pick-names"

/** Where a [PickerMap] is to go, a new one each time, even to the same place. */
data class MapTarget(val latitude: Double, val longitude: Double, val zoom: Double, val serial: Int = targets++)

private var targets = 0

/** A town's streets: close enough to put a pin on the right one. */
const val PICK_ZOOM = 14.0

/**
 * A map to choose a place on, a pin standing at its middle: the known places as dots
 * with their names, [chosen] in the theme's colour, one tapped told to [onPlace]; where
 * the middle has come to rest told to [onCenter], with whether a finger moved it there.
 * Each new [target] flies it there.
 */
@Composable
fun PickerMap(
    places: Map<String, Place>,
    chosen: String?,
    target: MapTarget?,
    onCenter: (latitude: Double, longitude: Double, byUser: Boolean) -> Unit,
    onPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val tapPlace by rememberUpdatedState(onPlace)
    val centered by rememberUpdatedState(onCenter)
    var map by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val halo = MaterialTheme.colorScheme.surface.toArgb()
    val reach = with(LocalDensity.current) { TAP_REACH.toPx() }
    val first = remember { target }

    MapLibreView(modifier, interactive = true, compass = false) { ready, style, _ ->
        style.addSource(GeoJsonSource(PICK_PLACES))
        style.addLayer(
            CircleLayer(PICK_PLACES, PICK_PLACES).withProperties(
                circleRadius(6f),
                circleColor(dot),
                circleStrokeWidth(2f),
                circleStrokeColor(halo),
            ),
        )
        style.addLayer(
            SymbolLayer(PICK_NAMES, PICK_PLACES).withProperties(
                textField(get("name")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.8f)),
                textColor(ink),
                textHaloColor(halo),
                textHaloWidth(1.5f),
            ),
        )
        first?.let { ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), it.zoom)) }
        map = ready to style
    }

    LaunchedEffect(map, places) {
        val (_, style) = map?.live ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(PICK_PLACES)?.setGeoJson(
            FeatureCollection.fromFeatures(
                places.map { (id, place) ->
                    Feature.fromGeometry(place.latLng.point).apply {
                        addStringProperty("id", id)
                        addStringProperty("name", place.name)
                    }
                },
            ),
        )
    }

    LaunchedEffect(map, chosen, primary, dot) {
        val (_, style) = map?.live ?: return@LaunchedEffect
        val isChosen = eq(get("id"), literal(chosen ?: ""))
        style.getLayerAs<CircleLayer>(PICK_PLACES)?.setProperties(
            circleColor(switchCase(isChosen, color(primary), color(dot))),
            circleRadius(switchCase(isChosen, literal(8f), literal(6f))),
        )
    }

    LaunchedEffect(map, target) {
        val (ready, _) = map ?: return@LaunchedEffect
        if (target == null || target == first) return@LaunchedEffect
        ready.glide(CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), target.zoom), 600, reduced)
    }

    DisposableEffect(map) {
        val (ready, _) = map ?: return@DisposableEffect onDispose { }
        var byUser = false
        val started = MapLibreMap.OnCameraMoveStartedListener { reason ->
            byUser = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
        }
        val idle = MapLibreMap.OnCameraIdleListener {
            val center = ready.cameraPosition.target ?: return@OnCameraIdleListener
            centered(center.latitude, center.longitude, byUser)
            byUser = false
        }
        val click = MapLibreMap.OnMapClickListener { point ->
            val at = ready.projection.toScreenLocation(point)
            val hit = ready.nearest(ready.queryRenderedFeatures(RectF(at.x - reach, at.y - reach, at.x + reach, at.y + reach), PICK_PLACES, PICK_NAMES), at)
                ?.getStringProperty("id")
            hit?.let(tapPlace)
            hit != null
        }
        ready.addOnCameraMoveStartedListener(started)
        ready.addOnCameraIdleListener(idle)
        ready.addOnMapClickListener(click)
        onDispose {
            ready.removeOnCameraMoveStartedListener(started)
            ready.removeOnCameraIdleListener(idle)
            ready.removeOnMapClickListener(click)
        }
    }
}
