package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.TravelMode
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

// The routes of the trips the API hasn't routed, worked out on the phone as the API works
// out the rest (apps/api/src/routes.ts): a drive along the road FOSSGIS's public Valhalla
// finds, a train along the rail line OpenRailRouting's demo finds between the places'
// stations, a ferry or a flight straight. Neither needs a key. Each leg is kept by the key
// the API keeps it by, so a trip once published draws as it did.

/** A point as (longitude, latitude). */
typealias LngLat = Pair<Double, Double>

/** A leg of a trip between two of its stops, [by] null for a drive. */
data class TripLeg(val from: Stop, val to: Stop, val by: TravelMode?)

/** A leg to ask a router for: its key, and its ends, a train's at their stations. */
data class LegRequest(val key: String, val train: Boolean, val from: LngLat, val to: LngLat)

/** A drive's key, as `@hyoban/trip-map`'s `getLegKey`: both ends to five places. */
fun legKey(from: Place, to: Place): String =
    String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f", from.longitude, from.latitude, to.longitude, to.latitude)

/** A train's key, as `getTrainLegKey`. */
fun trainLegKey(from: Place, to: Place): String = "train:${legKey(from, to)}"

/** A trip's legs between its stops, as the site's `buildTrip` takes them: driven unless its route says otherwise. */
fun legsOf(trip: TripRecord, stops: List<Stop>): List<TripLeg> = stops.zipWithNext { from, to ->
    val by = trip.straightLegs.firstOrNull {
        (it.from == from.placeId && it.to == to.placeId) || (it.from == to.placeId && it.to == from.placeId)
    }?.by
    TripLeg(from, to, by)
}

/** What a leg is asked for as, or null for a ferry or a flight, which go straight. */
fun requestOf(leg: TripLeg): LegRequest? = when (leg.by) {
    null -> LegRequest(legKey(leg.from.place, leg.to.place), false, leg.from.place.lngLat(), leg.to.place.lngLat())
    TravelMode.TRAIN -> LegRequest(
        trainLegKey(leg.from.place, leg.to.place),
        true,
        leg.from.place.station() ?: leg.from.place.lngLat(),
        leg.to.place.station() ?: leg.to.place.lngLat(),
    )
    else -> null
}

private fun Place.lngLat(): LngLat = longitude to latitude

/** A place's station, which a train leaves from rather than its centre. */
private fun Place.station(): LngLat? {
    val station = extra["station"] as? JsonObject ?: return null
    val latitude = (station["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val longitude = (station["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
    return longitude to latitude
}

/**
 * A trip's route as the site's `getTripSummary` draws it: drives one after another as one
 * stretch, each train, ferry or flight a dashed stretch of its own, and one taken both ways
 * drawn once. A leg with no line in [lines] is drawn straight between its stops.
 */
fun segmentsOf(legs: List<TripLeg>, lines: Map<String, List<LngLat>>): List<RouteSegment> {
    val segments = mutableListOf<RouteSegment>()
    val straightEnds = mutableSetOf<String>()
    var gap = false
    for (leg in legs) {
        val straight = leg.by != null
        val line = requestOf(leg)?.let { lines[it.key] }?.takeIf { it.size >= 2 }
            ?: listOf(leg.from.place.lngLat(), leg.to.place.lngLat())
        if (straight) {
            val ends = listOf(leg.from.placeId, leg.to.placeId).sorted().joinToString(" ")
            if (!straightEnds.add(ends)) {
                gap = true
                continue
            }
        }
        val last = segments.lastOrNull()
        if (last != null && !last.straight && !straight && !gap) {
            segments[segments.lastIndex] = last.copy(line = last.line + line.drop(1))
        } else {
            segments += RouteSegment(line, straight)
        }
        gap = false
    }
    return segments
}

/**
 * The legs routed on the phone, kept in [file] by their keys: a line for each, or none for a
 * leg no road or rail joins, drawn straight and not asked for again. A leg that failed is
 * asked for again the next time.
 */
class PhoneRoutes(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val route: suspend (LegRequest) -> List<LngLat>? = Routers::route,
    /** How long to wait between asks, as the public routers ask. */
    private val pause: Long = 1_100,
) {
    private val _lines = MutableStateFlow<Map<String, List<LngLat>>>(emptyMap())
    private val filling = Mutex()

    /** Each routed leg's line by its key; empty for a leg with no way along it. */
    val lines: StateFlow<Map<String, List<LngLat>>> = _lines.asStateFlow()

    suspend fun load() = withContext(io) {
        if (!file.exists()) return@withContext
        _lines.value = runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, line) ->
                line.jsonArray.map { point -> point.jsonArray.let { (it[0] as JsonPrimitive).doubleOrNull!! to (it[1] as JsonPrimitive).doubleOrNull!! } }
            }
        }.getOrDefault(emptyMap())
    }

    /** Routes the legs not kept yet, one at a time, keeping each as it comes. */
    suspend fun fill(legs: Collection<LegRequest>) = filling.withLock {
        for (leg in legs.distinctBy { it.key }) {
            if (leg.key in _lines.value) continue
            val line = try {
                route(leg) ?: emptyList()
            } catch (_: IOException) {
                continue
            }
            _lines.value = _lines.value + (leg.key to line)
            save()
            delay(pause)
        }
    }

    private suspend fun save() = withContext(io) {
        file.parentFile?.mkdirs()
        file.writeText(
            buildJsonObject {
                _lines.value.forEach { (key, line) ->
                    put(key, buildJsonArray { line.forEach { (lng, lat) -> add(buildJsonArray { add(JsonPrimitive(lng)); add(JsonPrimitive(lat)) }) } })
                }
            }.toString(),
        )
    }
}

/** The keyless routers the API uses too. */
object Routers {
    private const val USER_AGENT = "hyoban.cc Moments for Android"

    /** Valhalla's answers for a leg it will never route: no road near (171), too long (154), no path (442, 443). */
    private val NO_PATH = setOf(154, 171, 442, 443)

    /** About 30 m, as the API simplifies. */
    private const val TOLERANCE = 0.0003

    /** A leg's line, or null when there is no way along it; an [IOException] when asking failed. */
    suspend fun route(leg: LegRequest): List<LngLat>? = withContext(Dispatchers.IO) {
        if (leg.train) rail(leg) else drive(leg)
    }

    private fun drive(leg: LegRequest): List<LngLat>? {
        val query = buildJsonObject {
            put("locations", buildJsonArray {
                for ((lng, lat) in listOf(leg.from, leg.to)) add(buildJsonObject { put("lat", lat); put("lon", lng) })
            })
            put("costing", "auto")
            put("directions_type", "none")
        }
        val url = "https://valhalla1.openstreetmap.de/route".toHttpUrl().newBuilder().addQueryParameter("json", query.toString()).build()
        val (code, body) = get(url.toString())
        val answer = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = (answer?.get("error_code") as? JsonPrimitive)?.intOrNull
        if (error != null && error in NO_PATH) return null
        val shape = ((answer?.get("trip") as? JsonObject)?.get("legs") as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("shape") as? JsonPrimitive }?.content
        if (code !in 200..299 || shape == null) throw IOException("Valhalla answered $code")
        return simplify(decodePolyline(shape))
    }

    private fun rail(leg: LegRequest): List<LngLat>? {
        val url = "https://routing.openrailrouting.org/route".toHttpUrl().newBuilder()
            .addQueryParameter("point", "${leg.from.second},${leg.from.first}")
            .addQueryParameter("point", "${leg.to.second},${leg.to.first}")
            .addQueryParameter("profile", "tgv_all")
            .addQueryParameter("points_encoded", "false")
            .addQueryParameter("instructions", "false")
            .build()
        val (code, body) = get(url.toString())
        val answer = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val points = ((answer?.get("paths") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { (it["points"] as? JsonObject)?.get("coordinates") as? JsonArray }
        if (points == null) {
            if ((answer?.get("message") as? JsonPrimitive)?.content?.contains("not found") == true) return null
            throw IOException("OpenRailRouting answered $code")
        }
        return simplify(points.map { point -> point.jsonArray.let { (it[0] as JsonPrimitive).doubleOrNull!! to (it[1] as JsonPrimitive).doubleOrNull!! } })
    }

    private fun get(url: String): Pair<Int, String> {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        return Http.client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
    }

    /** A line in Valhalla's encoded polyline, six places, as (longitude, latitude). */
    fun decodePolyline(text: String): List<LngLat> {
        val line = mutableListOf<LngLat>()
        var index = 0
        var latitude = 0L
        var longitude = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            var byte: Int
            do {
                byte = text[index++].code - 63
                result = result or ((byte and 0x1f).toLong() shl shift)
                shift += 5
            } while (byte >= 0x20)
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < text.length) {
            latitude += next()
            longitude += next()
            line += longitude / 1e6 to latitude / 1e6
        }
        return line
    }

    /** Douglas–Peucker to [TOLERANCE] degrees, then five places, as the API keeps a route. */
    fun simplify(line: List<LngLat>): List<LngLat> {
        if (line.size < 3) return line.map(::round)
        val keep = BooleanArray(line.size).also { it[0] = true; it[line.lastIndex] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to line.lastIndex) }
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var distance = 0.0
            for (index in first + 1 until last) {
                val d = offLine(line[index], line[first], line[last])
                if (d > distance) { distance = d; farthest = index }
            }
            if (farthest >= 0 && distance > TOLERANCE) {
                keep[farthest] = true
                stack.add(first to farthest)
                stack.add(farthest to last)
            }
        }
        return line.filterIndexed { index, _ -> keep[index] }.map(::round)
    }

    private fun offLine(point: LngLat, start: LngLat, end: LngLat): Double {
        val dx = end.first - start.first
        val dy = end.second - start.second
        if (dx == 0.0 && dy == 0.0) return hypot(point.first - start.first, point.second - start.second)
        return abs(dy * point.first - dx * point.second + end.first * start.second - end.second * start.first) / hypot(dx, dy)
    }

    private fun round(point: LngLat): LngLat =
        Math.round(point.first * 1e5) / 1e5 to Math.round(point.second * 1e5) / 1e5
}
