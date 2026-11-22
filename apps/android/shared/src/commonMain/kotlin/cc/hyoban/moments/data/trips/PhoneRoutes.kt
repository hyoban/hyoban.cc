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
import kotlin.math.cos
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
        return simplify(trimTurnBacks(decodePolyline(shape)))
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
        // A point that isn't two numbers is left out rather than failing the whole way.
        return simplify(points.mapNotNull { point ->
            val pair = point as? JsonArray ?: return@mapNotNull null
            val lng = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            lng to lat
        })
    }

    /** How a way between two points is gone: by road, on foot, by bike, or by train. */
    enum class By { DRIVE, WALK, BIKE, TRAIN }

    /** How long, in seconds, and how far, in metres, a way is as a router reckons it, timetables aside. */
    data class Travel(val seconds: Long, val metres: Long)

    /**
     * How long and how far it is [by] from [from] to [to], or null when there is no way; an
     * [IOException] when asking failed. A train's is the line's running, not a timetable's.
     */
    suspend fun travel(from: LngLat, to: LngLat, by: By): Travel? = withContext(Dispatchers.IO) {
        if (by == By.TRAIN) return@withContext railTravel(from, to)
        val query = buildJsonObject {
            put("locations", buildJsonArray {
                for ((lng, lat) in listOf(from, to)) add(buildJsonObject { put("lat", lat); put("lon", lng) })
            })
            put("costing", when (by) { By.WALK -> "pedestrian"; By.BIKE -> "bicycle"; else -> "auto" })
            put("directions_type", "none")
        }
        val url = "https://valhalla1.openstreetmap.de/route".toHttpUrl().newBuilder().addQueryParameter("json", query.toString()).build()
        val (code, body) = get(url.toString())
        val answer = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = (answer?.get("error_code") as? JsonPrimitive)?.intOrNull
        if (error != null && error in NO_PATH) return@withContext null
        val summary = (answer?.get("trip") as? JsonObject)?.get("summary") as? JsonObject
        val seconds = (summary?.get("time") as? JsonPrimitive)?.doubleOrNull
        val km = (summary?.get("length") as? JsonPrimitive)?.doubleOrNull
        if (code !in 200..299 || seconds == null || km == null) throw IOException("Valhalla answered $code")
        Travel(seconds.toLong(), (km * 1000).toLong())
    }

    private fun railTravel(from: LngLat, to: LngLat): Travel? {
        val url = "https://routing.openrailrouting.org/route".toHttpUrl().newBuilder()
            .addQueryParameter("point", "${from.second},${from.first}")
            .addQueryParameter("point", "${to.second},${to.first}")
            .addQueryParameter("profile", "tgv_all")
            .addQueryParameter("calc_points", "false")
            .addQueryParameter("instructions", "false")
            .build()
        val (code, body) = get(url.toString())
        val answer = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val path = (answer?.get("paths") as? JsonArray)?.firstOrNull() as? JsonObject
        val ms = (path?.get("time") as? JsonPrimitive)?.doubleOrNull
        val metres = (path?.get("distance") as? JsonPrimitive)?.doubleOrNull
        if (ms == null || metres == null) {
            if ((answer?.get("message") as? JsonPrimitive)?.content?.contains("not found") == true) return null
            throw IOException("OpenRailRouting answered $code")
        }
        return Travel((ms / 1000).toLong(), metres.toLong())
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
        // Null where the text ends inside a number: a line cut short keeps the points before it.
        fun next(): Long? {
            var result = 0L
            var shift = 0
            var byte: Int
            do {
                if (index >= text.length || shift > 60) return null
                byte = text[index++].code - 63
                result = result or ((byte and 0x1f).toLong() shl shift)
                shift += 5
            } while (byte >= 0x20)
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < text.length) {
            latitude += next() ?: break
            longitude += next() ?: break
            line += longitude / 1e6 to latitude / 1e6
        }
        return line
    }

    /**
     * [line] without the spurs a router drives out and back at a leg's ends to reach a place from
     * the far side of the road, as trip-map's `trimTurnBacks` cuts them for the API: where the leg
     * first passes within [TURN_BACK_METRES] of its end and then runs on and returns no further than
     * [TURN_BACK_RATIO] times as far as it reaches, it stops there and steps across.
     */
    fun trimTurnBacks(line: List<LngLat>): List<LngLat> {
        if (line.size < 3 || metresBetween(line.first(), line.last()) < 2 * TURN_BACK_METRES) return line
        return trimEnd(trimEnd(line).reversed()).reversed()
    }

    private const val TURN_BACK_METRES = 300.0
    private const val TURN_BACK_MIN_METRES = 4 * TURN_BACK_METRES
    private const val TURN_BACK_RATIO = 2.6
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    private fun metresBetween(a: LngLat, b: LngLat): Double {
        val scale = cos(Math.toRadians((a.second + b.second) / 2))
        return Math.toRadians(hypot((b.first - a.first) * scale, b.second - a.second)) * EARTH_RADIUS_METRES
    }

    private fun trimEnd(line: List<LngLat>): List<LngLat> {
        val end = line.last()
        // Metres east and north of the end, near enough flat over a spur's few kilometres.
        val metres = Math.toRadians(1.0) * EARTH_RADIUS_METRES
        val scale = cos(Math.toRadians(end.second))
        val local = line.map { (x, y) -> (x - end.first) * scale * metres to (y - end.second) * metres }
        for (index in 0 until local.size - 2) {
            val (ax, ay) = local[index]
            val (bx, by) = local[index + 1]
            val length = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (length > 0) (-(ax * (bx - ax) + ay * (by - ay)) / length).coerceIn(0.0, 1.0) else 0.0
            val passing = ax + t * (bx - ax) to ay + t * (by - ay)
            if (hypot(passing.first, passing.second) >= TURN_BACK_METRES) continue
            // The spur: from where it passes on to the end.
            val spur = listOf(passing) + local.subList(index + 1, local.size)
            var spurLength = 0.0
            var reach = 0.0
            for (point in 1 until spur.size) {
                spurLength += hypot(spur[point].first - spur[point - 1].first, spur[point].second - spur[point - 1].second)
                reach = maxOf(reach, hypot(spur[point].first - passing.first, spur[point].second - passing.second))
            }
            if (spurLength < TURN_BACK_MIN_METRES || spurLength > TURN_BACK_RATIO * reach) return line
            val (first, second) = line[index] to line[index + 1]
            val cut = first.first + t * (second.first - first.first) to first.second + t * (second.second - first.second)
            return line.subList(0, index + 1) + listOf(cut, end)
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
