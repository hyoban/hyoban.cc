package cc.hyoban.moments.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.trips.RouteSegment
import cc.hyoban.moments.data.trips.Stop
import cc.hyoban.moments.ui.DayMark
import cc.hyoban.moments.ui.components.LocalReducedMotion
import cc.hyoban.moments.ui.theme.LocalDarkTheme
import cc.hyoban.moments.ui.theme.tokens
import cc.hyoban.moments.res.Res
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
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
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.ExpandingAttributionButton
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

private val China = CameraPosition(center = Position(longitude = 110.0, latitude = 32.0), zoom = 3.0)
private const val Closest = 12.0
private const val TrackClosest = 16.0
private const val PlaceClosest = 13.0

/** The platform's map surface and input settings. */
internal expect fun sharedMapUiOptions(interactive: Boolean, loadColor: Color): MapUiOptions

fun mapStyle(dark: Boolean) =
    BaseStyle.Uri(Res.getUri(if (dark) "files/map/dark.json" else "files/map/positron.json"))

fun positionOf(latitude: Double, longitude: Double) = Position(longitude = longitude, latitude = latitude)
private val Place.position get() = positionOf(latitude, longitude)
private fun Pair<Double, Double>.position() = positionOf(latitude = second, longitude = first)

private fun pointData(points: List<Position>) = GeoJsonData.Features(
    FeatureCollection(points.map { Feature(geometry = Point(it), properties = null) }),
)

private fun lineData(lines: List<List<Position>>) = GeoJsonData.Features(
    FeatureCollection(
        lines.filter { it.size >= 2 }.map { Feature(geometry = LineString(it), properties = null) },
    ),
)

@Composable
fun SharedMap(
    state: MapState,
    interactive: Boolean,
    modifier: Modifier,
    interactions: MapInteractions = MapInteractions.Standard,
    viewportInsets: PaddingValues = PaddingValues(0.dp),
    attributionPadding: PaddingValues = PaddingValues(8.dp),
) {
    Box(modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = state,
            interactions = interactions,
            viewportInsets = viewportInsets,
            uiOptions = sharedMapUiOptions(interactive, tokens.bg),
            overlay = {
                Box(Modifier.fillMaxSize().padding(attributionPadding)) {
                    ExpandingAttributionButton(Modifier.align(Alignment.BottomStart))
                }
            },
        )
    }
}

@Composable
private fun SharedPointsMap(
    points: List<Position>,
    lines: List<RouteSegment>,
    dot: Dp,
    padding: Dp,
    modifier: Modifier,
    interactive: Boolean,
    color: androidx.compose.ui.graphics.Color = tokens.accent,
    gapColor: androidx.compose.ui.graphics.Color = color,
    width: Dp = 3.dp,
    rings: List<Position> = emptyList(),
    topInset: Dp = 0.dp,
    closest: Double = Closest,
    marks: List<DayMark> = emptyList(),
) {
    val dark = LocalDarkTheme.current
    val ring = tokens.bg
    val solidLines = remember(lines) { lines.filterNot(RouteSegment::straight).map { it.line.map(Pair<Double, Double>::position) } }
    val dashedLines = remember(lines) { lines.filter(RouteSegment::straight).map { it.line.map(Pair<Double, Double>::position) } }
    val orderedMarks = remember(marks) { marks.sortedBy { it.kind != DayMark.Kind.BILL } }
    val badges = orderedMarks.map { it.kind to it.icon }.distinct()
    val painters = badges.map { (kind, icon) -> key(kind, icon) { rememberVectorPainter(icon) } }
    val framed = remember(points, lines, rings, marks) {
        points + rings + orderedMarks.map { positionOf(it.at.latitude, it.at.longitude) } + lines.flatMap { it.line.map(Pair<Double, Double>::position) }
    }
    val state = rememberMapState(
        baseStyle = mapStyle(dark),
        initialCameraPosition = China,
    ) {
        val solid = rememberGeoJsonSource(lineData(solidLines))
        val dashed = rememberGeoJsonSource(lineData(dashedLines))
        Anchor.Below({ it.type == "symbol" }) {
            LineLayer(
                id = "shared-route",
                source = solid,
                color = const(color),
                width = const(width),
                opacity = const(0.8f),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
            )
            LineLayer(
                id = "shared-route-dashed",
                source = dashed,
                color = const(gapColor),
                width = const(2.5.dp),
                opacity = const(0.8f),
                dasharray = const(listOf(2, 2)),
            )
        }
        val stops = rememberGeoJsonSource(pointData(points))
        CircleLayer(
            id = "shared-stops",
            source = stops,
            radius = const(dot),
            color = const(color),
            strokeWidth = const(2.dp),
            strokeColor = const(ring),
        )
        val ringPoints = rememberGeoJsonSource(pointData(rings))
        CircleLayer(
            id = "shared-rings",
            source = ringPoints,
            radius = const(dot),
            color = const(ring),
            strokeWidth = const(2.dp),
            strokeColor = const(color),
        )
        badges.zip(painters).forEachIndexed { index, (badge, painter) ->
            val badgeMarks = orderedMarks.filter { it.kind == badge.first && it.icon == badge.second }
            val source = rememberGeoJsonSource(pointData(badgeMarks.map { positionOf(it.at.latitude, it.at.longitude) }))
            val (fill, edge, glyph) = when (badge.first) {
                DayMark.Kind.MOMENT -> Triple(tokens.accent, tokens.bg, tokens.bg)
                DayMark.Kind.CHECKIN -> Triple(tokens.tx, tokens.bg, tokens.bg)
                DayMark.Kind.BILL -> Triple(tokens.bg, tokens.tx, tokens.tx)
            }
            CircleLayer(
                id = "shared-mark-background-$index",
                source = source,
                radius = const(11.dp),
                color = const(fill),
                strokeWidth = const(1.5.dp),
                strokeColor = const(edge),
            )
            SymbolLayer(
                id = "shared-mark-icon-$index",
                source = source,
                iconImage = image(
                    painter,
                    size = DpSize(14.dp, 14.dp),
                    colorFilter = ColorFilter.tint(glyph),
                ),
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
            )
        }
    }
    LaunchedEffect(state, framed, padding, topInset, closest) {
        if (framed.isEmpty()) return@LaunchedEffect
        val camera = if (framed.size == 1) {
            CameraPosition(center = framed.single(), zoom = closest)
        } else {
            val fitted = state.cameraForCoordinates(
                framed,
                CameraFit(
                    fitPadding = DpPadding(
                        left = padding,
                        top = padding + topInset,
                        right = padding,
                        bottom = padding,
                    ),
                ),
            ) ?: return@LaunchedEffect
            fitted.copy(zoom = minOf(fitted.zoom, closest))
        }
        state.setCameraPosition(camera)
    }
    SharedMap(state, interactive, modifier)
}

/** One place as a dot among its streets, implemented in shared Compose map code. */
@Composable
fun SharedPlaceMap(place: Place, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    SharedPointsMap(
        points = listOf(place.position),
        lines = emptyList(),
        dot = 7.dp,
        padding = 48.dp,
        modifier = modifier,
        interactive = false,
        topInset = topInset,
        closest = PlaceClosest,
    )
}

/** Where a photo was taken, a dot on its streets in the viewer's information sheet. */
@Composable
fun SharedPointMap(fix: Fix, modifier: Modifier = Modifier) {
    SharedPointsMap(
        points = listOf(positionOf(fix.latitude, fix.longitude)),
        lines = emptyList(),
        dot = 7.dp,
        padding = 24.dp,
        modifier = modifier,
        interactive = false,
        closest = PlaceClosest,
    )
}

/** A trip's route and stops, implemented in shared Compose map code. */
@Composable
fun SharedTripMap(stops: List<Stop>, segments: List<RouteSegment>, modifier: Modifier = Modifier) {
    val points = remember(stops) { stops.map { it.place.position } }
    val lines = remember(points, segments) {
        segments.ifEmpty {
            if (points.size > 1) {
                listOf(RouteSegment(points.map { it.longitude to it.latitude }, straight = false))
            } else {
                emptyList()
            }
        }
    }
    SharedPointsMap(points, lines, 5.dp, 40.dp, modifier, interactive = false)
}

/** A day's recorded stretches and event badges, implemented in shared Compose map code. */
@Composable
fun SharedDayTrackMap(
    stretches: List<List<Pair<Double, Double>>>,
    marks: List<DayMark>,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
) {
    val lines = remember(stretches) {
        stretches.map { RouteSegment(it, straight = false) } +
            stretches.zipWithNext { a, b -> RouteSegment(listOf(a.last(), b.first()), straight = true) }
    }
    SharedPointsMap(
        points = emptyList(),
        lines = lines,
        dot = 4.dp,
        padding = 32.dp,
        modifier = modifier,
        interactive = false,
        color = tokens.tx,
        gapColor = tokens.tx3,
        width = 4.dp,
        topInset = topInset,
        closest = TrackClosest,
        marks = marks,
    )
}

/** A shared interactive map for choosing a known place or moving its centre to a target. */
@Composable
fun SharedPickerMap(
    places: Map<String, Place>,
    chosen: String?,
    target: MapTarget?,
    onCenter: (latitude: Double, longitude: Double, byUser: Boolean) -> Unit,
    onPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDrag: (Boolean) -> Unit = {},
) {
    val dark = LocalDarkTheme.current
    val reduced = LocalReducedMotion.current
    val first = remember { target }
    val tapPlace = rememberUpdatedState(onPlace)
    val centered = rememberUpdatedState(onCenter)
    val dragged = rememberUpdatedState(onDrag)
    val primary = tokens.accent
    val dot = tokens.tx2
    val ink = tokens.tx
    val halo = tokens.bg
    val data = remember(places) {
        GeoJsonData.Features(
            FeatureCollection(
                places.map { (id, place) ->
                    Feature(
                        geometry = Point(positionOf(place.latitude, place.longitude)),
                        properties = buildJsonObject {
                            put("id", id)
                            put("name", place.name)
                        },
                    )
                },
            ),
        )
    }
    val chosenFeature = feature["id"] eq const(chosen ?: "")
    val interactions = remember {
        MapInteractions(MapInteractions.Standard) {
            camera { pitch { enabled = false } }
        }
    }
    val state = rememberMapState(
        baseStyle = mapStyle(dark),
        initialCameraPosition = first?.let {
            CameraPosition(center = positionOf(it.latitude, it.longitude), zoom = it.zoom)
        } ?: China,
    ) {
        val source = rememberGeoJsonSource(data)
        val click = { features: List<Feature<org.maplibre.spatialk.geojson.Geometry, kotlinx.serialization.json.JsonObject?>> ->
            val id = features.firstOrNull()?.properties?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
            if (id == null) {
                ClickResult.Pass
            } else {
                tapPlace.value(id)
                ClickResult.Consume
            }
        }
        CircleLayer(
            id = "shared-pick-places",
            source = source,
            radius = switch(condition(chosenFeature, const(8.dp)), fallback = const(6.dp)),
            color = switch(condition(chosenFeature, const(primary)), fallback = const(dot)),
            strokeWidth = const(2.dp),
            strokeColor = const(halo),
            onClick = { features -> click(features) },
            hitPadding = 16.dp,
        )
        SymbolLayer(
            id = "shared-pick-names",
            source = source,
            textField = feature["name"].asString(),
            textSize = const(12.sp),
            textAnchor = const(SymbolAnchor.Top),
            textOffset = textOffset(0.em, 0.8.em),
            textColor = const(ink),
            textHaloColor = const(halo),
            textHaloWidth = const(1.5.dp),
            onClick = { features -> click(features) },
            hitPadding = 16.dp,
        )
    }

    LaunchedEffect(state, target, first, reduced) {
        if (target == null || target == first) return@LaunchedEffect
        state.animateCamera(
            CameraUpdate(center = positionOf(target.latitude, target.longitude), zoom = target.zoom),
            CameraAnimation.Ease {
                duration = if (reduced) 0.milliseconds else 600.milliseconds
            },
        )
    }
    LaunchedEffect(state) {
        var movedByUser = false
        snapshotFlow { Triple(state.viewport != null, state.isCameraMoving, state.cameraMoveReason) }
            .collectLatest { (attached, moving, reason) ->
                if (!attached) return@collectLatest
                if (moving) {
                    if (reason == CameraMoveReason.Gesture && !movedByUser) {
                        movedByUser = true
                        dragged.value(true)
                    }
                } else {
                    dragged.value(false)
                    val center = state.cameraPosition.center
                    centered.value(center.latitude, center.longitude, movedByUser)
                    movedByUser = false
                }
            }
    }
    SharedMap(state, interactive = true, modifier = modifier, interactions = interactions)
}
