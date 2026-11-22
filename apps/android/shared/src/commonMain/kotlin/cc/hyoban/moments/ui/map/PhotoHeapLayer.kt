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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.picker.Places
import cc.hyoban.moments.ui.picker.Spot
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.min
import org.maplibre.compose.expressions.dsl.plus
import org.maplibre.compose.expressions.dsl.rem
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.expressions.value.SymbolAnchor
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

// 地图's photos (09 地图的照片) as the atlas draws them: symbol layers of their own, so they
// move with the map in the frame it draws rather than following its camera a frame or more
// behind, as a layer of Compose over it did. Photos that stand within [HeapReach] points of
// each other gather into one heap, by the source's own clustering, which shows its first photo
// and says how many it holds; tapped, a heap opens its photos in the sheet under the map.
//
// A heap's photo is named after that photo alone, the same however the photos read so far grow
// or a heap changes, so the map keeps the image it has rather than drawing it again blank: its
// count and the ring of the opened heap are layers of their own over it.

private const val Prefix = "here-photo"
private const val CountPrefix = "here-photo-count:"
private const val RingImage = "here-photo-ring"

/** A heap's photo, the room around it for its shadow, and how near two stand before they are one heap. */
private const val HeapSide = 48f
private const val HeapPad = 5f
private const val HeapReach = 64

/** Past the map's deepest zoom, so photos taken on one spot stay one heap however close it comes. */
private const val HeapMaxZoom = 24

/**
 * How a point's `k` holds both its place in the photos shown and its photo's name: the place times
 * this, plus the name, so the lowest `k` of a heap is its first photo and says which it is.
 */
private const val NameSpan = 16_777_216.0

/**
 * A number for each photo the map has been shown, kept while the map is, so its image keeps its
 * name across readings. Read and grown from any thread under its lock.
 */
internal class PhotoNames {
    private val lock = Mutex()
    private val numbers = HashMap<Pair<Int, Long>, Int>()
    private val spots = ArrayList<Spot>()

    suspend fun name(all: List<Spot>): IntArray = lock.withLock {
        IntArray(all.size) { i ->
            val spot = all[i]
            numbers.getOrPut(spot.from to spot.id) { spots.add(spot); spots.size - 1 }
        }
    }

    suspend fun spot(name: Int): Spot? = lock.withLock { spots.getOrNull(name) }
}

/**
 * The photos of [places] the map shows, those taken in [days] alone when they are chosen: a point each, with its
 * place in [spots] as `i` and `k` as [NameSpan] says. [nameOf] finds the opened heap's first photo.
 */
internal class PhotoHeaps(
    val places: Places,
    val spots: List<Spot>,
    val data: GeoJsonData,
    private val nameOf: Map<Pair<Int, Long>, Int>,
) {
    /** The name of the opened heap's first photo; -1 when none is open. */
    fun opened(): Int = places.opened?.firstOrNull()?.let { nameOf[it.from to it.id] } ?: -1
}

/** [places]' photos laid out for the atlas off the main thread; null until they are read. */
@Composable
internal fun rememberPhotoHeaps(places: Places?, days: ClosedRange<java.time.LocalDate>?, names: PhotoNames): PhotoHeaps? {
    LaunchedEffect(places) { places?.load() }
    val all = places?.spots
    var heaps by remember { mutableStateOf<PhotoHeaps?>(null) }
    LaunchedEffect(places, all, days) {
        heaps = if (places == null || all == null) null else withContext(Dispatchers.Default) {
            val spots = if (days == null) all else all.filter { spot -> spot.day?.let { java.time.LocalDate.ofEpochDay(it) in days } == true }
            val numbers = names.name(spots)
            val nameOf = HashMap<Pair<Int, Long>, Int>(spots.size * 2)
            val features = spots.mapIndexed { i, spot ->
                nameOf.putIfAbsent(spot.from to spot.id, numbers[i])
                Feature(
                    Point(positionOf(spot.latitude, spot.longitude)),
                    buildJsonObject {
                        put("i", i)
                        put("k", i * NameSpan + numbers[i])
                    },
                )
            }
            PhotoHeaps(places, spots, GeoJsonData.Features(FeatureCollection(features)), nameOf)
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
            "k" to GeoJsonOptions.ClusterPropertyAggregator(
                mapper = feature["k"].asNumber(),
                reducer = min(feature["k"].asNumber(), feature.accumulated().asNumber()),
            ),
        )
    }
    val cover = feature["k"].asNumber() % const(NameSpan.toInt())
    val onClick: org.maplibre.compose.layers.FeaturesClickHandler = { features ->
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
    }
    SymbolLayer(
        id = "shared-photo-heaps",
        source = source,
        iconImage = (const(Prefix + ":") + cover.convertToString()).cast<ImageValue?>(),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onClick,
    )
    val opened = heaps.opened()
    if (opened >= 0) {
        SymbolLayer(
            id = "shared-photo-heap-open",
            source = source,
            filter = cover eq const(opened.toFloat()),
            iconImage = const(RingImage).cast<ImageValue?>(),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
        )
    }
    // Its count at its top end, its right edge and top 8 beyond the photo's.
    SymbolLayer(
        id = "shared-photo-heap-counts",
        source = source,
        filter = feature.has("point_count"),
        iconImage = (const(CountPrefix) + feature["point_count"].convertToString()).cast<ImageValue?>(),
        iconAnchor = const(SymbolAnchor.TopRight),
        iconOffset = const(DpOffset((HeapSide / 2 + 8).dp, -(HeapSide / 2 + 8).dp)),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
        onClick = onClick,
    )
}

private fun indexOf(feature: Feature<*, JsonObject?>) =
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

/**
 * Draws each image of the heaps the style asks for, by the names [PhotoHeapLayer] gives them: a
 * photo's thumbnail, framed; the opened heap's ring; and a count.
 */
internal class PhotoHeapImages(
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val text: TextMeasurer,
    private val names: PhotoNames,
) : MissingImageResolver {
    var places: Places? = null
    var look: HeapLook? = null

    override suspend fun resolve(request: MissingImageRequest): ResolvedStyleImage? {
        val look = look ?: return null
        val id = request.id
        val side = HeapSide + HeapPad * 2
        return when {
            id == RingImage -> render(side, side) { ring(look) }
            id.startsWith(CountPrefix) -> {
                val count = id.removePrefix(CountPrefix).toDoubleOrNull()?.toInt() ?: return null
                val label = text.measure(count.toString(), look.count.copy(color = look.badgeText))
                val height = 20f
                val width = max(height, with(density) { label.size.width.toDp().value } + 12f)
                render(ceil(width), height) {
                    drawRoundRect(look.badge, cornerRadius = CornerRadius(size.height / 2))
                    drawText(label, topLeft = center - Offset(label.size.width / 2f, label.size.height / 2f))
                }
            }
            id.startsWith("$Prefix:") -> {
                val name = id.removePrefix("$Prefix:").toDoubleOrNull()?.toInt() ?: return null
                val spot = names.spot(name) ?: return null
                val photo = places?.thumbnail(spot)?.let(::BitmapPainter)
                render(side, side) { photo(photo, look) }
            }
            else -> null
        }
    }

    private suspend fun render(width: Float, height: Float, block: DrawScope.() -> Unit) =
        ResolvedStyleImage.fromPainter(HeapPainter(block), density, layoutDirection, size = DpSize(width.dp, height.dp))

    private fun DrawScope.frame() = Rect(Offset(HeapPad.dp.toPx(), HeapPad.dp.toPx()), Size(HeapSide.dp.toPx(), HeapSide.dp.toPx()))

    private fun DrawScope.radius() = CornerRadius(12.dp.toPx())

    /** The photo in its rounded frame, in the fill while it has none. */
    private fun DrawScope.photo(photo: Painter?, look: HeapLook) {
        val frame = frame()
        shadow(RoundRect(frame, radius()), 6.dp.toPx(), 0.3f, dy = 1.dp.toPx())
        drawPhoto(photo, frame, radius(), look.fill)
        outline(frame, 2.dp.toPx(), look.frame)
    }

    /** The opened heap's ring in the accent, laid over its photo's frame. */
    private fun DrawScope.ring(look: HeapLook) = outline(frame(), 3.dp.toPx(), look.accent)

    private fun DrawScope.outline(frame: Rect, width: Float, color: Color) {
        val inset = frame.deflate(width / 2)
        drawRoundRect(color, inset.topLeft, inset.size, CornerRadius(radius().x - width / 2), style = Stroke(width))
    }
}

/** A painter that draws [block], for MapLibre to render into a style image. */
private class HeapPainter(private val block: DrawScope.() -> Unit) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified
    override fun DrawScope.onDraw() = block()
}
