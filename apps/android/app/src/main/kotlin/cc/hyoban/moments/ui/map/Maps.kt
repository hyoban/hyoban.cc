package cc.hyoban.moments.ui.map

import android.graphics.RectF
import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
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
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.data.trips.RouteSegment
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.gt
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.sum
import org.maplibre.android.style.expressions.Expression.toString
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleTranslate
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonOptions
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.components.isLandscape
import androidx.compose.runtime.withFrameNanos
import org.maplibre.android.MapLibre
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.LocationComponentActivationOptions
import android.annotation.SuppressLint
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import android.os.Looper
import android.location.LocationManager
import android.location.LocationListener
import android.location.Location
import android.content.Context
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.raw
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleSortKey
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// A vector basemap drawn on the phone by MapLibre, the styles the settings name for light
// and dark (OpenFreeMap's Positron and its dark twin unless changed), every name in Chinese
// where the map has one, the Chinese drawn with the phone's own fonts. The dots and the
// route are layers over it in the app's colours.

/** Where China's middle is, roughly: where the map opens before it knows better. */
private val CHINA = CameraPosition.Builder().target(LatLng(32.0, 110.0)).zoom(3.0).build()

/** No closer than a town's streets when framing a place or a short trip. */
private const val CLOSEST = 12.0

private const val PLACES = "places"
private const val ROUTE = "route"
private const val STOPS = "stops"

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
private fun MapLibreView(
    modifier: Modifier,
    interactive: Boolean,
    attributionPadding: IntArray? = null,
    /** Whether MapLibre's own compass shows once the map is turned; off where the screen has its own. */
    compass: Boolean = true,
    retained: Boolean = false,
    onReady: (map: MapLibreMap, style: Style, fresh: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val styleUri = LocalSettings.current.mapStyle(isSystemInDarkTheme())
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
}

/** Pixels from each edge, given as left and right whichever way the text runs. */
@Composable
private fun absoluteEdges(left: Dp, top: Dp, right: Dp, bottom: Dp): IntArray = with(LocalDensity.current) {
    intArrayOf(left.roundToPx(), top.roundToPx(), right.roundToPx(), bottom.roundToPx())
}

/** Pixels from each edge (left, top, right, bottom). */
@Composable
private fun edges(start: Dp, top: Dp, end: Dp, bottom: Dp): IntArray = with(LocalDensity.current) {
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val left = if (ltr) start else end
    val right = if (ltr) end else start
    intArrayOf(left.roundToPx(), top.roundToPx(), right.roundToPx(), bottom.roundToPx())
}

/** A place on the map: how many Moments it has, and the year of its first, which colours it. */
data class MapPlace(val id: String, val place: Place, val count: Int, val firstYear: Int)

/** A trip's route on the map, in stretches. */
data class MapRoute(val id: String, val segments: List<RouteSegment>)

// The site's dots, in its pixels: a place with one Moment, one with more (its number on
// it), and a crowd of places gathered into one (the number of their Moments on it).
private const val DOT_SINGLE = 7f
private const val DOT_MANY = 9f
private const val DOT_CLUSTER = 12f
// Places gather where three or more crowd within 20 pixels, up to zoom 9; opening a
// crowd frames its places, no closer than zoom 11.
private const val CLUSTER_RADIUS = 20
private const val CLUSTER_MIN_POINTS = 3
private const val CLUSTER_MAX_ZOOM = 9
private const val CLUSTER_OPEN_MAX_ZOOM = 11.0
// The routes: lines under the places in the site's ink, which read now the basemap's
// streets are gone and its roads faded; a little bolder than the site's for a phone, and
// widening as the map zooms in. The one tapped stands out, the rest fall back.
private const val ROUTE_OPACITY = 0.6f
private const val ROUTE_WIDTH = 2f
private const val ROUTE_LIFTED_WIDTH = 3.5f
private const val ROUTE_DIMMED_OPACITY = 0.15f

/**
 * Where the phone is: a fix from the last few minutes if any provider has one, else a
 * fresh one from the best provider there is, or null if none comes within the timeout.
 */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = manager.getProviders(true)
    val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < RECENT_FIX_MS }
        .minByOrNull { it.accuracy }
    if (recent != null) return recent

    val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
        ?: return null
    return withTimeoutOrNull(LOCATE_TIMEOUT_MS.toLong()) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }
}

/** How old a fix may be and still count as where the phone is now. */
private const val RECENT_FIX_MS = 2 * 60 * 1000L

/** How close the map comes to the phone's place: a town and what is around it. */
private const val NEARBY_ZOOM = 10.0
/** How long to wait for a first fix before giving up. */
private const val LOCATE_TIMEOUT_MS = 10_000

private const val PLACES_SHADOW = "places-shadow"
private const val PLACES_COUNT = "places-count"
private const val ROUTES_DASHED = "routes-dashed"

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
private fun yearColor(colors: Map<Int, Color>): Expression {
    val stops = colors.map { (year, color) -> stop(year, color(color.toArgb())) }.toTypedArray()
    return if (stops.isEmpty()) color(Color.Gray.toArgb()) else match(get("firstYear"), color(Color.Gray.toArgb()), *stops)
}

/** The first layer of the basemap's labels, which the routes go under. */
private fun Style.labelsLayer(): String? = layers.firstOrNull { it is SymbolLayer }?.id

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
) {
    val tapPlace by rememberUpdatedState(onPlace)
    val located by rememberUpdatedState(onLocated)
    val tapEmpty by rememberUpdatedState(onEmptyTap)
    val context = LocalContext.current
    val me = MaterialTheme.colorScheme.primary.toArgb()
    val tapRoute by rememberUpdatedState(onRoute)
    val openTrip by rememberUpdatedState(onTrip)
    val lifted by rememberUpdatedState(highlighted)
    var map by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var framed by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val density = LocalDensity.current
    val reach = with(density) { 8.dp.toPx() }
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
                circleColor(Color.Black.toArgb()),
                circleOpacity(0.18f),
                circleBlur(0.6f),
                circleTranslate(arrayOf(0f, 1.5f)),
            ),
        )
        style.addLayer(
            CircleLayer(PLACES, PLACES).withProperties(
                circleColor(yearColor(colors)),
                circleStrokeColor(Color.White.copy(alpha = 0.95f).toArgb()),
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
                textColor(Color.White.toArgb()),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )

    }

    // Taps are listened for while this map is shown, with this showing's callbacks.
    DisposableEffect(map) {
        val (ready, style) = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { point ->
            val at = ready.projection.toScreenLocation(point)
            fun around(r: Float) = RectF(at.x - r, at.y - r, at.x + r, at.y + r)
            val place = ready.queryRenderedFeatures(around(reach), PLACES).firstOrNull()
            if (place != null) {
                if (place.hasProperty("point_count")) {
                    val leaves = style.getSourceAs<GeoJsonSource>(PLACES)?.getClusterLeaves(place, Long.MAX_VALUE, 0)
                    val points = leaves?.features()?.mapNotNull { (it.geometry() as? Point)?.let { p -> LatLng(p.latitude(), p.longitude()) } }
                    if (!points.isNullOrEmpty()) {
                        val bounds = LatLngBounds.Builder().includes(points).build()
                        val camera = ready.getCameraForLatLngBounds(bounds, IntArray(4) { clusterPadding })
                        if (camera != null) {
                            val zoom = minOf(camera.zoom, CLUSTER_OPEN_MAX_ZOOM)
                            ready.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(camera).zoom(zoom).build()))
                        }
                    }
                } else {
                    place.getStringProperty("id")?.let(tapPlace)
                }
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
        onDispose { ready.removeOnMapClickListener(listener) }
    }

    val shape = landscape to (rail.left > 0.dp)
    LaunchedEffect(map, places, shape) {
        val (ready, style) = map ?: return@LaunchedEffect
        val features = places.map { place ->
            Feature.fromGeometry(place.place.latLng.point).apply {
                addStringProperty("id", place.id)
                addNumberProperty("count", place.count)
                addNumberProperty("firstYear", place.firstYear)
            }
        }
        style.getSourceAs<GeoJsonSource>(PLACES)?.setGeoJson(FeatureCollection.fromFeatures(features))
        // Opens on every place once there are places, and stays where it's moved after
        // that, until the phone turns: then every place again, to fill the screen's new shape.
        if ((!framed || MainMap.framedFor != shape) && places.isNotEmpty()) {
            framed = true
            MainMap.framedFor = shape
            // Once the map has been laid out at the screen's new shape.
            withFrameNanos { }
            ready.frame(places.map { it.place.latLng }, frame)
        }
    }

    LaunchedEffect(map, routes) {
        val (_, style) = map ?: return@LaunchedEffect
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

    LaunchedEffect(map, selected) {
        val (_, style) = map ?: return@LaunchedEffect
        val chosen = eq(get("id"), literal(selected ?: ""))
        val radius = switchCase(
            has("point_count"), literal(DOT_CLUSTER),
            gt(get("count"), literal(1)), literal(DOT_MANY),
            literal(DOT_SINGLE),
        )
        val grown = switchCase(chosen, product(radius, literal(1.25f)), radius)
        style.getLayerAs<CircleLayer>(PLACES)?.setProperties(
            circleRadius(grown),
            circleStrokeWidth(switchCase(chosen, literal(3f), literal(2f))),
            circleStrokeColor(switchCase(chosen, color(ink), color(Color.White.copy(alpha = 0.95f).toArgb()))),
            circleSortKey(switchCase(chosen, literal(1f), literal(0f))),
        )
        style.getLayerAs<CircleLayer>(PLACES_SHADOW)?.setProperties(circleRadius(sum(grown, literal(3f))))
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
        if (north > 0) ready.animateCamera(CameraUpdateFactory.bearingTo(0.0), 400)
    }

    // Each time [locate] goes up, with the permission granted by then: the phone's place
    // as a dot, and the map brought to it at a town's scale, its nearby places around it.
    LaunchedEffect(map, locate) {
        val (ready, style) = map ?: return@LaunchedEffect
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
        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), zoom), 800)
        located(true)
    }

    LaunchedEffect(map, highlighted) {
        val (_, style) = map ?: return@LaunchedEffect
        val mine = eq(get("trip"), literal(highlighted ?: ""))
        val opacity = if (highlighted == null) literal(ROUTE_OPACITY) else switchCase(mine, literal(1f), literal(ROUTE_DIMMED_OPACITY))
        val width = if (highlighted == null) literal(ROUTE_WIDTH) else switchCase(mine, literal(ROUTE_LIFTED_WIDTH), literal(ROUTE_WIDTH))
        for (id in listOf(ROUTE, ROUTES_DASHED)) {
            style.getLayerAs<LineLayer>(id)?.setProperties(lineOpacity(opacity), lineWidth(byZoom(width)))
        }
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
                circleStrokeColor(Color.White.toArgb()),
            ),
        )
        val framed = points + lines.flatMap { segment -> segment.line.map { (longitude, latitude) -> LatLng(latitude, longitude) } }
        if (framed.isNotEmpty()) map.frame(framed, inset)
    }
}
