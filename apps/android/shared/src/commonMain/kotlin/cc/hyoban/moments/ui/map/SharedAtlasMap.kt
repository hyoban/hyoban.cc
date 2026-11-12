package cc.hyoban.moments.ui.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.rememberTextMeasurer
import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.PlaceKind
import cc.hyoban.moments.ui.compose.icon
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.delay
import org.maplibre.compose.expressions.dsl.and
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.lte
import org.maplibre.compose.expressions.dsl.unaryMinus
import org.maplibre.compose.expressions.value.ImageValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.footprints.TrackPoint
import cc.hyoban.moments.ui.LocalBottomClearance
import cc.hyoban.moments.ui.LocalDevice
import cc.hyoban.moments.ui.LocalRailSpace
import cc.hyoban.moments.ui.OnTabReselect
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.components.isLandscape
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.tokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.ln as expressionLn
import org.maplibre.compose.expressions.dsl.min
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * A place on the atlas: its total records, the first year that colours it, and the newest photo
 * taken there, which its pin is drawn as.
 */
data class MapPlace(
    val id: String,
    val place: Place,
    val count: Int,
    val firstYear: Int,
    val photo: MomentMedia? = null,
    /** The years it has records in, which keep it on the map when one is picked. */
    val years: Set<Int> = emptySet(),
)

/**
 * One trip and its route stretches on the atlas, drawn in the colour of the year it began, and
 * on it while a year it runs into is picked.
 */
data class MapRoute(val id: String, val segments: List<RouteSegment>, val year: Int? = null, val endYear: Int? = year)

/** The costly immutable GeoJSON projection of an atlas snapshot, safe to prepare off main. */
class AtlasMapData internal constructor(
    internal val places: GeoJsonData,
    internal val routes: GeoJsonData,
    internal val placePositions: List<Position>,
    /** Names the places a stack's image is drawn from, so a stack of other places is drawn anew. */
    internal val stackKey: String,
)

enum class AtlasFootprintMode { WALK, CYCLE, DRIVE }

data class AtlasFootprintLine(val mode: AtlasFootprintMode, val points: List<TrackPoint>)

data class AtlasFootprints(
    val lines: List<AtlasFootprintLine> = emptyList(),
    val ends: List<TrackPoint> = emptyList(),
    val stays: List<TrackPoint> = emptyList(),
) {
    val points get() = lines.flatMap(AtlasFootprintLine::points) + stays
}

/**
 * Owns the phone's main atlas while its tab is away. MapLibre Compose otherwise closes a
 * remembered map when the navigation entry leaves composition, making every return reload the
 * style, tiles, labels and markers. Other maps keep their screen-scoped lifetime.
 */
class AtlasMapStateStore {
    private var styleContent by mutableStateOf<(@Composable @MaplibreComposable () -> Unit)?>(null)
    private var mapState: MapState? = null

    internal fun getOrCreate(
        baseStyle: BaseStyle,
        content: @Composable @MaplibreComposable () -> Unit,
    ): MapState {
        mapState?.let { return it }
        styleContent = content
        return DefaultMapRuntime.instance.createMapState(
            baseStyle = baseStyle,
            cameraPosition = CameraPosition(center = Position(longitude = 110.0, latitude = 32.0), zoom = 3.0),
        ) { styleContent?.invoke() }.also { mapState = it }
    }

    internal fun update(
        baseStyle: BaseStyle,
        content: @Composable @MaplibreComposable () -> Unit,
    ) {
        styleContent = content
        mapState?.style?.asMutable?.baseStyle = baseStyle
    }

    fun close() {
        mapState?.close()
        mapState = null
        styleContent = null
    }
}

// Places gather into a stack only in a thick crowd, as on the site: this many within this many
// pixels of a 512-pixel tile, up to the zoom past which every place stands on its own.
private const val ClusterMaxZoom = 9
private const val ClusterRadius = 40
private const val ClusterMinPoints = 8
private const val ClusterOpenMaxZoom = 11.0
private const val ClusterOpenPadding = 80
private const val NearbyZoom = 10.0
// Routes in their year's colour, as bold as the site and MapKit draw them.
private const val RouteOpacity = 0.85f
private const val RouteDimmedOpacity = 0.2f
private const val RouteWidth = 2.5
private const val RouteLiftedWidth = 4.0
// An open place's pin lifts off it with a little overshoot, and comes down quicker.
private const val LiftMillis = 220
private const val DropMillis = 120
private const val AnchorDelay = 60
private val LiftEasing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)
private const val PlacesDimmedOpacity = 0.45f
private const val PlaceSheetShare = 0.55f
private const val FogThick = 0.22f
private const val FogFirst = 0.6f
private val FogClear = ln(21.0)
private const val FogRings = 24
private const val FogEdge = 0.35f
private const val FogSweepIn = 1400
private const val FogSweepOut = 900

private data class FogRingLayout(val ids: List<Set<String>>, val even: Boolean)

private fun emptyFogRings() = FogRingLayout(List(FogRings) { emptySet() }, true)

private fun longitudeInBounds(longitude: Double, bounds: VisibleBounds): Boolean =
    bounds.east - bounds.west >= 360.0 ||
        listOf(longitude - 360.0, longitude, longitude + 360.0).any { it in bounds.west..bounds.east }

private fun fogRingLayout(regions: List<FogRegion>, center: Position, bounds: VisibleBounds): FogRingLayout {
    if (regions.isEmpty()) return emptyFogRings()
    val squash = cos(center.latitude * PI / 180.0)
    fun distance(region: FogRegion) = hypot((region.cx - center.longitude) * squash, region.cy - center.latitude)
    val inSight = regions.filter {
        longitudeInBounds(it.cx, bounds) && it.cy in bounds.south..bounds.north
    }.map(::distance)
    val even = inSight.size < 2 || inSight.max() - inSight.min() < 1e-6
    if (even) return FogRingLayout(listOf(regions.map(FogRegion::id).toSet()) + List(FogRings - 1) { emptySet() }, true)
    val near = inSight.min()
    val far = inSight.max()
    val width = (far - near) / FogRings
    val rings = List(FogRings) { mutableSetOf<String>() }
    for (region in regions) {
        val held = distance(region).coerceIn(near, far)
        val index = floor((held - near) / width).toInt().coerceIn(0, FogRings - 1)
        rings[index] += region.id
    }
    return FogRingLayout(rings, false)
}

private fun smoothstep(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

internal fun fogRingOpacity(index: Int, even: Boolean, reach: Float, entering: Boolean): Float {
    if (even) {
        if (index != 0) return 0f
        return if (entering) smoothstep(reach) else 1f - smoothstep(reach)
    }
    val position = (index + 0.5f) / FogRings
    val distance = reach * (1f + FogEdge)
    val past = if (entering) {
        (distance - (1f - position)) / FogEdge
    } else {
        (distance - position) / FogEdge
    }
    val through = smoothstep(past)
    return if (entering) through else 1f - through
}

private data class WaterLayer(
    val source: String,
    val sourceLayer: String?,
    val filter: JsonElement?,
    val color: JsonElement?,
    val opacity: JsonElement?,
    val pattern: JsonElement?,
)

private fun pointFeature(position: Position, properties: JsonObject? = null) = Feature(Point(position), properties)

private fun pointsData(points: List<TrackPoint>) = GeoJsonData.Features(
    FeatureCollection(points.map { pointFeature(positionOf(it.latitude, it.longitude)) }),
)

private fun lineFeatures(lines: List<List<TrackPoint>>) = GeoJsonData.Features(
    FeatureCollection(
        lines.filter { it.size >= 2 }.map { line ->
            Feature(
                LineString(line.map { positionOf(it.latitude, it.longitude) }),
                null,
            )
        },
    ),
)

private fun routeData(routes: List<MapRoute>) = GeoJsonData.Features(
    FeatureCollection(
        routes.asReversed().flatMap { route ->
            route.segments.map { segment ->
                Feature(
                    LineString(segment.line.map { positionOf(it.second, it.first) }),
                    buildJsonObject {
                        put("trip", route.id)
                        put("straight", segment.straight)
                        route.year?.let { put("year", it) }
                        route.endYear?.let { put("endYear", it) }
                    },
                )
            }
        },
    ),
)

/** A place's point, with the names of its pin's images, regular and lifted. */
private fun placeFeature(item: MapPlace, colors: Map<Int, Color>): Feature<Point, JsonObject?> {
    val (pin, open) = pinImageIds(colors[item.firstYear] ?: Color.Gray, item.photo, item.place.kind)
    return pointFeature(
        positionOf(item.place.latitude, item.place.longitude),
        buildJsonObject {
            put("id", item.id)
            put("count", item.count)
            put("firstYear", item.firstYear)
            put("name", item.place.name)
            put("balloon", item.photo == null)
            put("pin", pin)
            put("open", open)
        },
    )
}

private fun placeData(places: List<MapPlace>, colors: Map<Int, Color>) = GeoJsonData.Features(
    FeatureCollection(places.map { placeFeature(it, colors) }),
)

private fun stackKey(places: List<MapPlace>, colors: Map<Int, Color>): String {
    var hash = colors.hashCode()
    for (item in places) {
        hash = 31 * hash + item.id.hashCode()
        hash = 31 * hash + item.count
        hash = 31 * hash + item.firstYear
        hash = 31 * hash + (item.photo?.uid?.hashCode() ?: 0)
    }
    return hash.toUInt().toString(16)
}

/** Builds what MapLibre sources need without making the first map composition do the conversion. */
fun prepareAtlasMapData(places: List<MapPlace>, routes: List<MapRoute>, colors: Map<Int, Color>) = AtlasMapData(
    places = placeData(places, colors),
    routes = routeData(routes),
    placePositions = places.map { positionOf(it.place.latitude, it.place.longitude) },
    stackKey = stackKey(places, colors),
)

private fun featureString(feature: Feature<*, JsonObject?>, name: String) =
    (feature.properties?.get(name) as? JsonPrimitive)?.contentOrNull

/** A stand-in for a cluster of the places' source, for asking it what places it holds. */
private fun clusterFeature(id: Long) = Feature(
    Point(Position(0.0, 0.0)),
    buildJsonObject {
        put("cluster", true)
        put("cluster_id", id)
    },
)

/** Reads a stack's places from the map's source, once the style has one. */
private class ClusterLeaves {
    var read: (suspend (Long) -> List<String>?)? = null
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

private fun Position.southBy(dp: Double, zoom: Double): Position {
    val world = 512.0 * 2.0.pow(zoom)
    val y = (1 - ln(tan(PI / 4 + latitude * PI / 360)) / PI) / 2 * world + dp
    return Position(longitude = longitude, latitude = atan(sinh(PI * (1 - 2 * y / world))) * 180 / PI)
}

private fun cameraAnimation(reduced: Boolean, milliseconds: Int, fly: Boolean = false): CameraAnimation =
    if (fly) {
        CameraAnimation.Fly { duration = (if (reduced) 0 else milliseconds).milliseconds }
    } else {
        CameraAnimation.Ease { duration = (if (reduced) 0 else milliseconds).milliseconds }
    }

private suspend fun framePositions(
    state: org.maplibre.compose.map.MapState,
    positions: List<Position>,
    padding: DpPadding,
    closest: Double,
    animated: Boolean,
    reduced: Boolean,
) {
    if (positions.isEmpty()) return
    val camera = if (positions.size == 1) {
        CameraPosition(center = positions.single(), zoom = closest, bearing = 0.0)
    } else {
        val fitted = state.cameraForCoordinates(positions, CameraFit(fitPadding = padding)) ?: return
        fitted.copy(zoom = minOf(fitted.zoom, closest), bearing = 0.0)
    }
    if (animated) state.animateCamera(camera.toCameraUpdate(), cameraAnimation(reduced, 600, fly = true))
    else state.setCameraPosition(camera)
}

/** The shared interactive atlas used by the phone map tab and, later, the desktop app. */
@Composable
fun SharedAtlasMap(
    places: List<MapPlace>,
    colors: Map<Int, Color>,
    routes: List<MapRoute>,
    selected: String?,
    highlighted: String?,
    onPlace: (String) -> Unit,
    onRoute: (String?) -> Unit,
    onTrip: (String) -> Unit,
    prepared: AtlasMapData? = null,
    modifier: Modifier = Modifier,
    locate: Int = 0,
    north: Int = 0,
    onBearing: (Double) -> Unit = {},
    onLocated: (Boolean) -> Unit = {},
    onEmptyTap: () -> Unit = {},
    tab: Any? = null,
    focusedPlace: String? = null,
    focusedTrip: String? = null,
    onFocused: () -> Unit = {},
    fog: FogOfWar? = null,
    fogSweep: Int = 0,
    fogRegions: List<FogRegion> = emptyList(),
    footprints: AtlasFootprints = AtlasFootprints(),
    footprintFrame: List<TrackPoint> = emptyList(),
    frameFootprints: Boolean = false,
    onFootprintsFramed: () -> Unit = {},
    onFootprint: () -> Unit = {},
    panel: Dp = 0.dp,
    mediaUrls: MediaUrls? = null,
    /**
     * The year picked: only the places with records then and the trips under way then stay on
     * the map, the other places stepping back to gray dots; the map frames them, and going back
     * to every year puts it back where it was, unless it was moved since.
     */
    year: Int? = null,
) {
    val reduced = LocalReducedMotion.current
    val dark = LocalDarkTheme.current
    val device = LocalDevice.current
    val scope = rememberCoroutineScope()
    val tapPlace = rememberUpdatedState(onPlace)
    val tapRoute = rememberUpdatedState(onRoute)
    val openTrip = rememberUpdatedState(onTrip)
    val tapEmpty = rememberUpdatedState(onEmptyTap)
    val tapFootprint = rememberUpdatedState(onFootprint)
    val currentHighlighted = rememberUpdatedState(highlighted)
    val located = rememberUpdatedState(onLocated)
    val focused = rememberUpdatedState(onFocused)
    val bearingChanged = rememberUpdatedState(onBearing)
    val footprintsFramed = rememberUpdatedState(onFootprintsFramed)
    var renderedFog by remember { mutableStateOf(fog) }
    var fogEntering by remember { mutableStateOf(fog != null) }
    var fogSweeping by remember { mutableStateOf(false) }
    var handledFogSweep by remember { mutableStateOf(fogSweep) }
    // Both directions finish at one: entering leaves every ring opaque, exiting leaves it clear.
    val fogReach = remember { Animatable(1f) }

    LaunchedEffect(fog, fogSweep, reduced) {
        val entering = fog != null
        // Settings and the local sweep counter can invalidate composition separately. Do not
        // consume the counter until its matching on/off change arrives.
        val swept = fogSweep != handledFogSweep && entering != fogEntering
        if (!swept) {
            fogSweeping = false
            renderedFog = fog
            if (entering == fogEntering) return@LaunchedEffect
            fogEntering = entering
            fogReach.snapTo(1f)
            return@LaunchedEffect
        }
        handledFogSweep = fogSweep
        if (entering) renderedFog = fog
        if (fogEntering != entering) {
            fogReach.snapTo(1f - fogReach.value)
            fogEntering = entering
        }
        fogSweeping = true
        val whole = if (entering) FogSweepIn else FogSweepOut
        val duration = (whole * abs(1f - fogReach.value)).toInt().coerceAtLeast(1)
        fogReach.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = if (reduced) 0 else duration,
                easing = if (entering) Easing { sin(it * PI / 2).toFloat() } else LinearEasing,
            ),
        )
        if (!entering) renderedFog = null
        fogSweeping = false
    }

    val ink = tokens.tx
    val ring = tokens.bg
    val unknown = tokens.borderStrong
    val me = tokens.accent
    val shownPlaces = remember(places, year) { if (year == null) places else places.filter { year in it.years } }
    val dimGeoJson = remember(places, year) {
        GeoJsonData.Features(
            FeatureCollection(
                if (year == null) {
                    emptyList()
                } else {
                    places.filter { year !in it.years }.map { pointFeature(positionOf(it.place.latitude, it.place.longitude)) }
                },
            ),
        )
    }
    val placeGeoJson = remember(shownPlaces, colors, prepared, year) {
        prepared?.places?.takeIf { year == null } ?: placeData(shownPlaces, colors)
    }
    val stackPrefix = stackImagePrefix(
        remember(shownPlaces, colors, prepared, year) { prepared?.stackKey?.takeIf { year == null } ?: stackKey(shownPlaces, colors) },
    )
    val yearPositions = remember(shownPlaces) { shownPlaces.map { positionOf(it.place.latitude, it.place.longitude) } }
    val routeYearTest = year?.let { feature["year"].asNumber() lte const(it) and (feature["endYear"].asNumber() gte const(it)) }
    val routeGeoJson = remember(routes, prepared) { prepared?.routes ?: routeData(routes) }
    val driveGeoJson = remember(footprints) { lineFeatures(footprints.lines.filter { it.mode == AtlasFootprintMode.DRIVE }.map { it.points }) }
    val cycleGeoJson = remember(footprints) { lineFeatures(footprints.lines.filter { it.mode == AtlasFootprintMode.CYCLE }.map { it.points }) }
    val walkGeoJson = remember(footprints) { lineFeatures(footprints.lines.filter { it.mode == AtlasFootprintMode.WALK }.map { it.points }) }
    val endGeoJson = remember(footprints) { pointsData(footprints.ends) }
    val stayGeoJson = remember(footprints) { pointsData(footprints.stays) }
    val allPositions = remember(places, footprints, prepared) {
        (prepared?.placePositions ?: places.map { positionOf(it.place.latitude, it.place.longitude) }) +
            footprints.points.map { positionOf(it.latitude, it.longitude) }
    }

    // The open place's pin lifts off it, larger, over a dot left on the spot with its name under
    // it, as MapKit lifts a selected one; closed, it comes down before another lifts.
    var openPin by remember { mutableStateOf<String?>(null) }
    val lift = remember { Animatable(0f) }
    val anchorShown = remember { Animatable(0f) }
    LaunchedEffect(selected, reduced) {
        if (openPin != null && openPin != selected) {
            anchorShown.snapTo(0f)
            lift.animateTo(0f, tween(if (reduced) 0 else DropMillis))
        }
        openPin = selected
        if (selected == null) return@LaunchedEffect
        launch {
            if (!reduced) delay(AnchorDelay.milliseconds)
            anchorShown.animateTo(1f, tween(if (reduced) 0 else LiftMillis))
        }
        lift.animateTo(1f, tween(if (reduced) 0 else LiftMillis, easing = LiftEasing))
    }
    val openPlace = remember(openPin, places) { places.firstOrNull { it.id == openPin } }
    val openGeoJson = remember(openPlace, colors) {
        GeoJsonData.Features(FeatureCollection(listOfNotNull(openPlace?.let { placeFeature(it, colors) })))
    }

    val clusterTest = feature.has("point_count")
    val openTest = feature["id"] eq const(openPin ?: "")
    val balloonTest = feature["balloon"] eq const(true)
    val liftedTest = feature["trip"] eq const(highlighted ?: "")
    val placeAlpha = if (highlighted == null) 1f else PlacesDimmedOpacity
    val placeOpacity = const(placeAlpha)
    val routeOpacity = if (highlighted == null) {
        const(RouteOpacity)
    } else {
        switch(condition(liftedTest, const(1f)), fallback = const(RouteDimmedOpacity))
    }
    fun routeWidthAt(scale: Double) = if (highlighted == null) {
        const((RouteWidth * scale).dp)
    } else {
        switch(condition(liftedTest, const((RouteLiftedWidth * scale).dp)), fallback = const((RouteWidth * scale).dp))
    }
    val scaledRouteWidth = interpolate(
        linear(),
        zoom(),
        4 to routeWidthAt(1.0),
        10 to routeWidthAt(1.4),
        14 to routeWidthAt(2.0),
    )
    val yearColor = remember(colors, unknown) {
        switch(
            input = feature["firstYear"],
            cases = colors.map { (year, color) -> case(year, const(color)) },
            fallback = const(unknown),
        )
    }
    // Each route in the colour of the year its trip began, as the places have it.
    val routeColor = remember(colors, ink) {
        switch(
            input = feature["year"],
            cases = colors.map { (year, color) -> case(year, const(color)) },
            fallback = const(ink),
        )
    }
    // A stack's image is named after its cluster, drawn once its places are known.
    val pinImage = switch(
        condition(clusterTest, const(stackPrefix) + feature["cluster_id"].convertToString()),
        fallback = feature["pin"].asString(),
    ).cast<ImageValue?>()
    // The stacks over the pile, then the busiest places over the rest.
    val pinSortKey = switch(condition(clusterTest, const(1_000_000f)), fallback = feature["count"].asNumber())
    val pinAnchor = switch(condition(balloonTest, const(SymbolAnchor.Bottom)), fallback = const(SymbolAnchor.Center))
    val leaves = remember { ClusterLeaves() }
    val platformContext = LocalPlatformContext.current
    val pinDensity = LocalDensity.current
    val pinDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    val kindIcons = PlaceKind.entries.associate { it.value to rememberVectorPainter(it.icon) }
    val pinIcon = rememberVectorPainter(AppIcons.Pin)
    val pinImages = remember(pinDensity, pinDirection, platformContext, mediaUrls, textMeasurer) {
        mediaUrls?.let { PlacePinImages(pinDensity, pinDirection, platformContext, it, textMeasurer, kindIcons, pinIcon) }
    }
    val fogCount = renderedFog?.weights.orEmpty()
    // The region-dependent part stays stable through a sweep. Each animation frame then changes
    // only a ring's constant opacity, as the classic map did, instead of replacing 24
    // data-driven expressions and making MapLibre reevaluate them all.
    val fogColor = remember(fogCount, ink) {
        val fogWeight = switch(
            input = feature["id"],
            cases = fogCount.map { (id, count) -> case(id, const(count)) },
            fallback = const(0),
        ).asNumber()
        interpolate(
            linear(),
            expressionLn(const(1) + fogWeight),
            0 to const(ink.copy(alpha = ink.alpha * FogThick)),
            ln(2.0) to const(ink.copy(alpha = ink.alpha * FogThick * FogFirst)),
            FogClear to const(ink.copy(alpha = 0f)),
        )
    }
    val quickTransition = TransitionOptions(duration = (if (reduced) 0 else 300).milliseconds)
    val instantTransition = remember { TransitionOptions(duration = 0.milliseconds) }
    var userLocation by remember { mutableStateOf<Position?>(null) }
    var water by remember { mutableStateOf<WaterLayer?>(null) }
    var fogLayout by remember(fogRegions) { mutableStateOf(emptyFogRings()) }

    val interactions = remember {
        MapInteractions(MapInteractions.Standard) {
            camera { pitch { enabled = false } }
            callbacks {
                click {
                    onUnhandled {
                        if (currentHighlighted.value != null) tapRoute.value(null)
                        tapEmpty.value()
                        ClickResult.Consume
                    }
                }
            }
        }
    }
    val styleContent: @Composable @MaplibreComposable () -> Unit = {
        val mapState = checkNotNull(LocalMapState.current)
        val routeSource = rememberGeoJsonSource(routeGeoJson)
        val placeSource = rememberGeoJsonSource(placeGeoJson) {
            cluster = true
            clusterRadius = ClusterRadius
            clusterMinPoints = ClusterMinPoints
            clusterMaxZoom = ClusterMaxZoom
            clusterProperties = mapOf(
                "firstYear" to GeoJsonOptions.ClusterPropertyAggregator(
                    mapper = feature["firstYear"].asNumber(),
                    reducer = min(feature["firstYear"].asNumber(), feature.accumulated().asNumber()),
                ),
                "momentCount" to GeoJsonOptions.ClusterPropertyAggregator(
                    mapper = feature["count"].asNumber(),
                    reducer = feature["momentCount"].asNumber() + feature.accumulated().asNumber(),
                ),
            )
        }
        val openSource = rememberGeoJsonSource(openGeoJson)
        val dimSource = rememberGeoJsonSource(dimGeoJson)
        SideEffect {
            leaves.read = { id ->
                mapState.style.sources[placeSource]
                    ?.getClusterLeaves(clusterFeature(id), Int.MAX_VALUE, 0)
                    ?.features
                    ?.mapNotNull { featureString(it, "id") }
            }
        }
        val drives = rememberGeoJsonSource(driveGeoJson)
        val cycles = rememberGeoJsonSource(cycleGeoJson)
        val walks = rememberGeoJsonSource(walkGeoJson)
        val ends = rememberGeoJsonSource(endGeoJson)
        val stays = rememberGeoJsonSource(stayGeoJson)
        // Loading this 1.8 MB GeoJSON parses and tiles it. Keep it out of the style until fog is
        // actually visible, and retain it through an exit sweep until the final clear frame.
        val fogSource = if (renderedFog != null || fogSweeping) {
            rememberGeoJsonSource(GeoJsonData.Uri(FOG_REGIONS_URI)) { maxZoom = 10 }
        } else {
            null
        }

        val placeClick: org.maplibre.compose.layers.FeaturesClickHandler = { features ->
            val hit = features.firstOrNull()
            if (hit == null) {
                ClickResult.Pass
            } else {
                val handle = mapState.style.sources[placeSource]
                if (handle?.isCluster(hit) == true) {
                    // Frames the stack's places close enough to tell apart, never short of the
                    // zoom it parts at, around the stack itself when they spread wider than that.
                    scope.launch {
                        val positions = handle.getClusterLeaves(hit, Int.MAX_VALUE, 0)?.features.orEmpty()
                            .mapNotNull { (it.geometry as? Point)?.coordinates }
                        if (positions.isEmpty()) return@launch
                        val part = handle.getClusterExpansionZoom(hit)?.plus(0.01) ?: 0.0
                        val edge = ClusterOpenPadding.dp
                        val fitted = mapState.cameraForCoordinates(positions, CameraFit(fitPadding = DpPadding(edge, edge, edge, edge)))
                        val framedZoom = minOf(fitted?.zoom ?: mapState.cameraPosition.zoom, ClusterOpenMaxZoom)
                        val center = (hit.geometry as? Point)?.coordinates
                        val camera = if (fitted == null || framedZoom < part) {
                            mapState.cameraPosition.copy(center = center ?: mapState.cameraPosition.center, zoom = maxOf(framedZoom, part))
                        } else {
                            fitted.copy(zoom = framedZoom)
                        }
                        mapState.animateCamera(camera.toCameraUpdate(), cameraAnimation(reduced, 300))
                    }
                } else {
                    featureString(hit, "id")?.let(tapPlace.value)
                }
                ClickResult.Consume
            }
        }
        val routeClick: org.maplibre.compose.layers.FeaturesClickHandler = { features ->
            val trip = features.firstOrNull()?.let { featureString(it, "trip") }
            when {
                trip == null -> ClickResult.Pass
                trip == highlighted -> {
                    openTrip.value(trip)
                    ClickResult.Consume
                }
                else -> {
                    tapRoute.value(trip)
                    ClickResult.Consume
                }
            }
        }
        val footprintClick: org.maplibre.compose.layers.FeaturesClickHandler = { features ->
            if (features.isEmpty()) ClickResult.Pass else {
                tapFootprint.value()
                ClickResult.Consume
            }
        }

        Anchor.Below({ it.type == "symbol" }) {
            if (fogSource != null) {
                for (index in 0 until FogRings) {
                    val ids = fogLayout.ids[index]
                    val ringFilter = if (ids.isEmpty()) {
                        feature["id"] eq const("__here-no-fog-region__")
                    } else {
                        switch(
                            input = feature["id"],
                            cases = ids.map { id -> case(id, const(true)) },
                            fallback = const(false),
                        )
                    }
                    FillLayer(
                        id = "shared-fog-ring-$index",
                        source = fogSource,
                        filter = ringFilter,
                        color = fogColor,
                        colorTransition = instantTransition,
                        opacity = const(
                            if (fogSweeping) {
                                fogRingOpacity(index, fogLayout.even, fogReach.value, fogEntering)
                            } else {
                                1f
                            },
                        ),
                        opacityTransition = instantTransition,
                        antialias = const(false),
                    )
                }
            }
            water?.takeIf { renderedFog != null }?.let { layer ->
                Layer(id = "shared-fog-water", type = "fill") {
                    root("source", JsonPrimitive(layer.source))
                    layer.sourceLayer?.let { root("source-layer", JsonPrimitive(it)) }
                    layer.filter?.let { root("filter", it) }
                    layer.color?.let { paint("fill-color", it) }
                    layer.opacity?.let { paint("fill-opacity", it) }
                    layer.pattern?.let { paint("fill-pattern", it) }
                }
            }
            LineLayer(
                id = "shared-routes",
                source = routeSource,
                filter = (!feature["straight"].asBoolean()).let { if (routeYearTest == null) it else it and routeYearTest },
                color = routeColor,
                opacity = routeOpacity,
                opacityTransition = quickTransition,
                width = scaledRouteWidth,
                widthTransition = quickTransition,
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
                onClick = routeClick,
                hitPadding = 22.dp,
            )
            LineLayer(
                id = "shared-routes-dashed",
                source = routeSource,
                filter = feature["straight"].asBoolean().let { if (routeYearTest == null) it else it and routeYearTest },
                color = routeColor,
                opacity = routeOpacity,
                opacityTransition = quickTransition,
                width = scaledRouteWidth,
                widthTransition = quickTransition,
                // Dotted, round dots apart, as MapKit draws a flight or a ferry.
                cap = const(LineCap.Round),
                dasharray = const(listOf(0, 2)),
                onClick = routeClick,
                hitPadding = 22.dp,
            )
            LineLayer("shared-footprint-drives", drives, color = const(ink), width = const(3.dp), cap = const(LineCap.Round), join = const(LineJoin.Round), onClick = footprintClick, hitPadding = 22.dp)
            LineLayer("shared-footprint-cycles", cycles, color = const(ink), width = const(2.dp), cap = const(LineCap.Round), join = const(LineJoin.Round), onClick = footprintClick, hitPadding = 22.dp)
            LineLayer("shared-footprint-walks", walks, color = const(ink), width = const(3.dp), cap = const(LineCap.Round), join = const(LineJoin.Round), dasharray = const(listOf(0, 2)), onClick = footprintClick, hitPadding = 22.dp)
            CircleLayer("shared-footprint-ends", ends, radius = const(4.dp), color = const(ink), strokeWidth = const(1.5.dp), strokeColor = const(ring), onClick = footprintClick, hitPadding = 22.dp)
            CircleLayer("shared-footprint-stays", stays, radius = const(5.dp), color = const(ring), strokeWidth = const(2.5.dp), strokeColor = const(ink), onClick = footprintClick, hitPadding = 22.dp)
        }
        Anchor.Top {
            // Places outside the year picked, stepping back to small gray dots under every pin
            // rather than leaving, so the year is seen among every place.
            CircleLayer(
                id = "shared-places-dim",
                source = dimSource,
                radius = const(4.dp - 1.5.dp),
                color = const(tokens.tx3),
                opacity = const(0.8f * placeAlpha),
                strokeWidth = const(1.5.dp),
                strokeColor = const(Color.White),
                strokeOpacity = const(0.8f * placeAlpha),
            )
            // A balloon's name under its tip, where it clears the pins and names around it:
            // placed after the pins over it, so it gives way to them.
            SymbolLayer(
                id = "shared-places-names",
                source = placeSource,
                filter = balloonTest and !openTest,
                sortKey = -feature["count"].asNumber(),
                textField = feature["name"].asString(),
                textFont = const(listOf("Noto Sans Bold")),
                textSize = const(11.sp),
                textAnchor = const(SymbolAnchor.Top),
                textOffset = textOffset(0.em, 0.2.em),
                textMaxWidth = const(100f.em),
                textColor = const(ink),
                textHaloColor = const(ring),
                textHaloWidth = const(1.5.dp),
                textOpacity = placeOpacity,
                textOpacityTransition = quickTransition,
            )
            // Every pin on its very spot, piling up where places crowd, as MapKit's do.
            SymbolLayer(
                id = "shared-places",
                source = placeSource,
                filter = !openTest,
                sortKey = pinSortKey,
                iconImage = pinImage,
                iconAnchor = pinAnchor,
                iconPadding = const(DpPadding.Zero),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(false),
                iconOpacity = placeOpacity,
                iconOpacityTransition = quickTransition,
                onClick = placeClick,
                hitPadding = 4.dp,
            )
            val lifted = lift.value
            val openBalloon = openPlace?.photo == null
            val openScale = if (openBalloon) PinSize.OpenBalloon else PinSize.OpenPhoto
            val openSize = lerp(1f / openScale, 1f, lifted)
            val openTip = if (openBalloon) {
                lerp(0f, -PinSize.OpenBalloonTip, lifted)
            } else {
                lerp(PhotoTailTip, -PinSize.OpenPhotoTip, lifted)
            }
            val shown = anchorShown.value * placeAlpha
            CircleLayer(
                id = "shared-place-anchor",
                source = openSource,
                radius = const(PinSize.Anchor.dp),
                color = yearColor,
                opacity = const(shown),
                opacityTransition = instantTransition,
                strokeWidth = const(1.5.dp),
                strokeColor = const(Color.White),
                strokeOpacity = const(shown),
                strokeOpacityTransition = instantTransition,
            )
            SymbolLayer(
                id = "shared-place-open-name",
                source = openSource,
                textField = feature["name"].asString(),
                textFont = const(listOf("Noto Sans Bold")),
                textSize = const(12.sp),
                textAnchor = const(SymbolAnchor.Top),
                textOffset = textOffset(0.em, 0.67.em),
                textMaxWidth = const(10.7f.em),
                textColor = const(ink),
                textHaloColor = const(ring),
                textHaloWidth = const(1.5.dp),
                textOpacity = const(shown),
                textOpacityTransition = instantTransition,
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
            )
            // Its size and its lift are both laid out, so they move together; the lift is
            // given in the scaled pin's units.
            SymbolLayer(
                id = "shared-place-open",
                source = openSource,
                iconImage = feature["open"].asString().cast<ImageValue?>(),
                iconAnchor = const(SymbolAnchor.Bottom),
                iconSize = const(openSize),
                iconOffset = const(DpOffset(0.dp, (openTip / openSize).dp)),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
                iconOpacity = const(placeAlpha),
                iconOpacityTransition = instantTransition,
                onClick = placeClick,
                hitPadding = 4.dp,
            )
            LocationIndicatorLayer(
                id = "shared-user-location",
                location = userLocation,
                accuracyRadiusColor = me.copy(alpha = 0.12f),
                accuracyRadiusBorderColor = me,
            )
        }
    }
    val baseStyle = mapStyle(dark)
    val store = device.atlasMapStateStore
    val state = if (store == null) {
        rememberMapState(
            baseStyle = baseStyle,
            initialCameraPosition = CameraPosition(center = Position(longitude = 110.0, latitude = 32.0), zoom = 3.0),
            content = styleContent,
        )
    } else {
        val retained = remember(store) { store.getOrCreate(baseStyle, styleContent) }
        SideEffect { store.update(baseStyle, styleContent) }
        retained
    }

    SideEffect {
        pinImages?.inputs = PinInputs(
            photos = places.mapNotNull { it.photo }.associateBy { it.uid },
            places = places.associateBy { it.id },
            colors = colors,
            unknown = unknown,
            leaves = { id -> leaves.read?.invoke(id) },
        )
        state.missingImageResolver = pinImages
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val barLeft = with(density) { bars.getLeft(this, layoutDirection).toDp() }
    val barTop = with(density) { bars.getTop(this).toDp() }
    val barRight = with(density) { bars.getRight(this, layoutDirection).toDp() }
    val barBottom = with(density) { bars.getBottom(this).toDp() }
    val clearance = LocalBottomClearance.current
    val rail = LocalRailSpace.current
    val landscape = isLandscape()
    val framePadding = if (landscape) {
        DpPadding(
            left = barLeft + rail.left + panel + 24.dp,
            top = barTop + 56.dp,
            right = barRight + rail.right + 24.dp,
            bottom = barBottom + clearance + 16.dp,
        )
    } else {
        val start = barLeft + 32.dp
        val end = barRight + 32.dp
        if (layoutDirection == LayoutDirection.Ltr) {
            DpPadding(start, barTop + 96.dp, end, barBottom + clearance + 16.dp)
        } else {
            DpPadding(end, barTop + 96.dp, start, barBottom + clearance + 16.dp)
        }
    }
    val attributionPadding = PaddingValues(
        start = barLeft + rail.left + 8.dp,
        end = barRight + rail.right + 8.dp,
        bottom = barBottom + clearance + 8.dp,
    )
    val shape = "$landscape:${rail.left.value}:${rail.right.value}:${panel.value}"
    var framed by rememberSaveable { mutableStateOf(false) }
    var framedShape by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.style.loadState) {
        if (state.style.loadState != StyleLoadState.Ready) return@LaunchedEffect
        val layer = state.style.layers.firstOrNull { it.type == "fill" && it.sourceLayer == "water" } ?: return@LaunchedEffect
        val source = layer.source ?: return@LaunchedEffect
        water = WaterLayer(
            source = source,
            sourceLayer = layer.sourceLayer,
            filter = layer.getProperty("filter"),
            color = layer.getProperty("fill-color"),
            opacity = layer.getProperty("fill-opacity"),
            pattern = layer.getProperty("fill-pattern"),
        )
    }
    LaunchedEffect(state, fogRegions) {
        snapshotFlow { state.isCameraMoving to state.viewport }.collectLatest { (moving, viewport) ->
            if (!moving && viewport != null) {
                fogLayout = fogRingLayout(fogRegions, viewport.cameraPosition.center, viewport.visibleBounds)
            }
        }
    }
    // What the map frames: the year's places while one is picked, else everything on it.
    val homePositions = if (year != null && yearPositions.isNotEmpty()) yearPositions else allPositions
    LaunchedEffect(state, allPositions, shape) {
        state.awaitViewport()
        if ((!framed || framedShape != shape) && homePositions.isNotEmpty()) {
            framePositions(state, homePositions, framePadding, closest = 12.0, animated = false, reduced = reduced)
            framed = true
            framedShape = shape
        }
    }
    OnTabReselect(tab) {
        if (homePositions.isNotEmpty()) framePositions(state, homePositions, framePadding, 12.0, animated = true, reduced = reduced)
    }
    // Where the map was before a year was picked, and where picking it left the map: going back
    // to every year returns there only if the map hasn't been moved since.
    var framedYear by rememberSaveable { mutableStateOf(year) }
    var beforeYear by remember { mutableStateOf<CameraPosition?>(null) }
    var yearView by remember { mutableStateOf<CameraPosition?>(null) }
    LaunchedEffect(state, year, framed) {
        if (!framed || year == framedYear) return@LaunchedEffect
        val previous = framedYear
        framedYear = year
        if (year != null) {
            if (previous == null) beforeYear = state.cameraPosition
            if (yearPositions.isNotEmpty() && focusedPlace == null && selected == null) {
                framePositions(state, yearPositions, framePadding, 12.0, animated = true, reduced = reduced)
            }
            yearView = state.cameraPosition
        } else {
            val back = beforeYear
            if (back != null && state.cameraPosition == yearView) {
                state.animateCamera(back.toCameraUpdate(), cameraAnimation(reduced, 600, fly = true))
            } else if (selected == null && allPositions.isNotEmpty()) {
                framePositions(state, allPositions, framePadding, 12.0, animated = true, reduced = reduced)
            }
            beforeYear = null
            yearView = null
        }
    }
    LaunchedEffect(state, focusedPlace, focusedTrip, framed, places, routes) {
        if (!framed) return@LaunchedEffect
        when {
            focusedPlace != null -> {
                val place = places.firstOrNull { it.id == focusedPlace }?.place ?: return@LaunchedEffect
                val zoom = maxOf(state.cameraPosition.zoom, NearbyZoom)
                val viewport = state.awaitViewport()
                val clearTop = barTop + 72.dp
                val clearBottom = viewport.size.height * (1f - PlaceSheetShare) - 48.dp
                val above = viewport.size.height / 2 - (clearTop + clearBottom) / 2
                val camera = CameraPosition(
                    center = positionOf(place.latitude, place.longitude).southBy(above.value.toDouble(), zoom),
                    zoom = zoom,
                    bearing = 0.0,
                )
                state.animateCamera(camera.toCameraUpdate(), cameraAnimation(reduced, 600))
                focused.value()
            }
            focusedTrip != null -> {
                val route = routes.firstOrNull { it.id == focusedTrip } ?: return@LaunchedEffect
                val positions = route.segments.flatMap { it.line.map { pair -> positionOf(pair.second, pair.first) } }
                if (positions.isNotEmpty()) {
                    framePositions(
                        state,
                        positions,
                        framePadding.copy(bottom = framePadding.bottom + 112.dp),
                        closest = 12.0,
                        animated = true,
                        reduced = reduced,
                    )
                }
                focused.value()
            }
        }
    }
    var previousSelected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, selected, framed) {
        val place = places.firstOrNull { it.id == selected }?.place
        if (framed && place != null && selected != previousSelected && focusedPlace != selected) {
            val position = positionOf(place.latitude, place.longitude)
            val viewport = state.viewport
            val onScreen = viewport?.screenLocationFromPosition(position)
            if (onScreen != null) {
                val clearTop = barTop + 72.dp
                val clearBottom = viewport.size.height * (1f - PlaceSheetShare) - 48.dp
                if (onScreen.y > clearBottom || onScreen.y < clearTop) {
                    val center = viewport.positionFromScreenLocation(
                        DpOffset(
                            viewport.size.width / 2,
                            viewport.size.height / 2 + onScreen.y - (clearTop + clearBottom) / 2,
                        ),
                    )
                    if (center != null) state.animateCamera(CameraUpdate(center = center), cameraAnimation(reduced, 400))
                }
            }
        }
        previousSelected = selected
    }
    LaunchedEffect(state) {
        snapshotFlow { state.cameraPosition.bearing }.collectLatest { bearingChanged.value(it) }
    }
    LaunchedEffect(state, north) {
        if (north > 0) state.animateCamera(CameraUpdate(bearing = 0.0), cameraAnimation(reduced, 400))
    }
    LaunchedEffect(state, locate) {
        if (locate == 0) return@LaunchedEffect
        val fix = device.currentFix()
        if (fix == null) {
            located.value(false)
        } else {
            val position = positionOf(fix.latitude, fix.longitude)
            userLocation = position
            state.animateCamera(
                CameraUpdate(center = position, zoom = maxOf(state.cameraPosition.zoom, NearbyZoom)),
                cameraAnimation(reduced, 800),
            )
            located.value(true)
        }
    }
    val footprintPositions = remember(footprintFrame, footprints) {
        footprintFrame.ifEmpty { footprints.points }.map { positionOf(it.latitude, it.longitude) }
    }
    LaunchedEffect(state, frameFootprints, framed, footprintPositions) {
        if (frameFootprints && framed && footprintPositions.isNotEmpty()) {
            framePositions(state, footprintPositions, framePadding, 17.0, animated = true, reduced = reduced)
            footprintsFramed.value()
        }
    }
    SharedMap(
        state = state,
        interactive = true,
        modifier = modifier,
        interactions = interactions,
        attributionPadding = attributionPadding,
    )
}
