package cc.hyoban.moments.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.picker.Places
import cc.hyoban.moments.ui.picker.Spot
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.min
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.MissingImageRequest
import org.maplibre.compose.map.MissingImageResolver
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point

// 地图's photos (09 地图的照片) as the atlas draws them: a symbol layer of their own, so they
// move with the map in the frame it draws rather than following its camera a frame or more
// behind, as a layer of Compose over it did. Photos that stand within [HeapReach] points of
// each other gather into one heap, by the source's own clustering, which shows its first photo
// and says how many it holds; tapped, a heap opens its photos in the sheet under the map.

private const val Prefix = "here-photo"

/** A heap's photo, the room around it for its count and shadow, and how near two stand before they are one heap. */
private const val HeapSide = 48f
private const val HeapPad = 12f
private const val HeapReach = 64

/** Past the map's deepest zoom, so photos taken on one spot stay one heap however close it comes. */
private const val HeapMaxZoom = 24

/**
 * The photos of [places] the map shows, [year]'s alone when one is picked: a point each, keeping
 * its place in [spots] as `i`, so a heap's first photo is the one with the lowest. [key] names this
 * reading in the images' names, so an image drawn for another never stands in for one of these.
 */
internal class PhotoHeaps(val places: Places, val spots: List<Spot>, val data: GeoJsonData, val key: String) {
    private val index = HashMap<Pair<Int, Long>, Int>(spots.size * 2).apply {
        spots.forEachIndexed { i, spot -> putIfAbsent(spot.from to spot.id, i) }
    }

    /** Where in [spots] the opened heap's first photo is; -1 when none is open. */
    fun opened(): Int = places.opened?.firstOrNull()?.let { index[it.from to it.id] } ?: -1
}

/** [places]' photos laid out for the atlas off the main thread; null until they are read, or when there are none to show. */
@Composable
internal fun rememberPhotoHeaps(places: Places?, year: Int?, dark: Boolean): PhotoHeaps? {
    LaunchedEffect(places) { places?.load() }
    val all = places?.spots
    var heaps by remember { mutableStateOf<PhotoHeaps?>(null) }
    LaunchedEffect(places, all, year, dark) {
        heaps = if (places == null || all == null) null else withContext(Dispatchers.Default) {
            val spots = if (year == null) all else all.filter { it.year == year }
            val features = spots.mapIndexed { i, spot ->
                Feature(Point(positionOf(spot.latitude, spot.longitude)), buildJsonObject { put("i", i) })
            }
            val key = spots.hashCode().toUInt().toString(16) + if (dark) "d" else "l"
            PhotoHeaps(places, spots, GeoJsonData.Features(FeatureCollection(features)), key)
        }
    }
    return heaps
}

/** The heaps of [heaps], over everything else on the map, as the photos stood over it before. */
@Composable
@MaplibreComposable
internal fun PhotoHeapLayer(heaps: PhotoHeaps) {
    val mapState = checkNotNull(LocalMapState.current)
    val scope = rememberCoroutineScope()
    val source = rememberGeoJsonSource(heaps.data) {
        cluster = true
        clusterRadius = HeapReach
        clusterMinPoints = 2
        maxZoom = HeapMaxZoom
        clusterMaxZoom = HeapMaxZoom - 1
        clusterProperties = mapOf(
            "i" to GeoJsonOptions.ClusterPropertyAggregator(
                mapper = feature["i"].asNumber(),
                reducer = min(feature["i"].asNumber(), feature.accumulated().asNumber()),
            ),
        )
    }
    val heapTest = feature.has("point_count")
    val count = switch(condition(heapTest, feature["point_count"].asNumber()), fallback = const(1f))
    val chosen = switch(condition(feature["i"].asNumber() eq const(heaps.opened().toFloat()), const("1")), fallback = const("0"))
    val image = (
        const("$Prefix:${heaps.key}:") + feature["i"].convertToString() + const(":") +
            count.convertToString() + const(":") + chosen
        ).cast<ImageValue?>()
    SymbolLayer(
        id = "shared-photo-heaps",
        source = source,
        iconImage = image,
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = { features ->
            val hit = features.firstOrNull()
            val handle = mapState.style.sources[source]
            if (hit == null || handle == null) {
                ClickResult.Pass
            } else {
                scope.launch {
                    val members = if (handle.isCluster(hit)) {
                        handle.getClusterLeaves(hit, Int.MAX_VALUE, 0)?.features.orEmpty()
                    } else {
                        listOf(hit)
                    }
                    val spots = members.mapNotNull { indexOf(it) }.sorted().mapNotNull(heaps.spots::getOrNull)
                    if (spots.isNotEmpty()) heaps.places.open(spots)
                }
                ClickResult.Consume
            }
        },
    )
}

private fun indexOf(feature: Feature<*, kotlinx.serialization.json.JsonObject?>) =
    (feature.properties?.get("i") as? JsonPrimitive)?.doubleOrNull?.toInt()

/** The colours a heap is drawn in, the theme's as it stands. */
internal class HeapLook(
    val fill: Color,
    val frame: Color,
    val accent: Color,
    val badge: Color,
    val badgeText: Color,
    val count: TextStyle,
)

/** Draws each heap's image the style asks for, by the name [PhotoHeapLayer] gives it: its first photo's thumbnail, framed, and how many it holds. */
internal class PhotoHeapImages(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val text: TextMeasurer,
) : MissingImageResolver {
    var heaps: PhotoHeaps? = null
    var look: HeapLook? = null

    override suspend fun resolve(request: MissingImageRequest): ResolvedStyleImage? {
        // here-photo:<key>:<first photo's index>:<count>:<opened>
        val parts = request.id.split(":")
        if (parts.size != 5 || parts[0] != Prefix) return null
        val heaps = heaps?.takeIf { it.key == parts[1] } ?: return null
        val look = look ?: return null
        val spot = parts[2].toDoubleOrNull()?.toInt()?.let(heaps.spots::getOrNull) ?: return null
        val count = parts[3].toDoubleOrNull()?.toInt() ?: return null
        val thumbnail = heaps.places.thumbnail(spot)?.let(::BitmapPainter)
        val side = HeapSide + HeapPad * 2
        return ResolvedStyleImage.fromPainter(
            HeapPainter { heap(thumbnail, count, chosen = parts[4] == "1", look) },
            density,
            layoutDirection,
            size = DpSize(side.dp, side.dp),
        )
    }

    /** The photo in its rounded frame, ringed in the accent while it is the one opened, its count at the top end when more than one. */
    private fun DrawScope.heap(photo: Painter?, count: Int, chosen: Boolean, look: HeapLook) {
        val pad = HeapPad.dp.toPx()
        val frame = Rect(Offset(pad, pad), Size(HeapSide.dp.toPx(), HeapSide.dp.toPx()))
        val radius = CornerRadius(12.dp.toPx())
        shadow(RoundRect(frame, radius), 6.dp.toPx(), 0.3f, dy = 1.dp.toPx())
        drawPhoto(photo, frame, radius, look.fill)
        val ring = (if (chosen) 3 else 2).dp.toPx()
        val inset = frame.deflate(ring / 2)
        drawRoundRect(
            if (chosen) look.accent else look.frame,
            inset.topLeft,
            inset.size,
            CornerRadius(radius.x - ring / 2),
            style = Stroke(ring),
        )
        if (count <= 1) return
        val label = text.measure(count.toString(), look.count.copy(color = look.badgeText))
        val height = 20.dp.toPx()
        val width = max(height, label.size.width + 12.dp.toPx())
        val right = frame.right + 8.dp.toPx()
        val top = frame.top - 8.dp.toPx()
        val badge = Rect(Offset(right - width, top), Size(width, height))
        drawRoundRect(look.badge, badge.topLeft, badge.size, CornerRadius(height / 2))
        drawText(label, topLeft = badge.center - Offset(label.size.width / 2f, label.size.height / 2f))
    }
}

/** A painter that draws [block], for MapLibre to render into a style image. */
private class HeapPainter(private val block: DrawScope.() -> Unit) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified
    override fun DrawScope.onDraw() = block()
}
