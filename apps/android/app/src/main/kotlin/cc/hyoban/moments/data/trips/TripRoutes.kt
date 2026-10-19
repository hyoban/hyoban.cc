package cc.hyoban.moments.data.trips

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Each trip's route as the site draws it: along the roads it drove, from the Directions
// routes the site's build caches, and straight and dashed where it took a train, a plane
// or a ferry. The API doesn't hold them; the site publishes them with each trip's
// playback, at /trip/<id>/data.json, which the app reads only the route out of.

/** A point of the route, as the site's trip data gives it. */
@Serializable
data class RoutePoint(val latitude: Double, val longitude: Double)

/** A stop of the trip: the places passed on the way to it, then its own. */
@Serializable
data class RouteStop(val places: List<RoutePoint> = emptyList(), val waypoints: List<RoutePoint>? = null)

/** From one point of the route to the next: along [line] (longitude, latitude), or straight without one. */
@Serializable
data class RouteLeg(val line: List<List<Double>>? = null, val mode: String = "car")

/** The part of a trip's playback data the route is drawn from. */
@Serializable
data class RouteData(
    val stops: List<RouteStop> = emptyList(),
    val trailingWaypoints: List<RoutePoint>? = null,
    val legs: List<RouteLeg> = emptyList(),
)

/** A stretch of a route as (longitude, latitude) points: drives run together, and each other leg is [straight], drawn dashed. */
data class RouteSegment(val line: List<Pair<Double, Double>>, val straight: Boolean)

/**
 * The route's stretches as the site's overview has them: drives one after another make
 * one stretch; a train, plane or ferry is a stretch of its own, and one taken both ways
 * is drawn once, as two dashed lines over each other would read as a solid one.
 */
fun segmentsOf(data: RouteData): List<RouteSegment> {
    val path = data.stops.flatMap { (it.waypoints ?: emptyList()) + it.places } + (data.trailingWaypoints ?: emptyList())
    val segments = mutableListOf<Pair<MutableList<Pair<Double, Double>>, Boolean>>()
    val straightLegs = mutableSetOf<String>()
    // Set after a leg is left out, so the drive after it starts a stretch of its own.
    var gap = false

    data.legs.forEachIndexed { index, leg ->
        val from = path.getOrNull(index) ?: return@forEachIndexed
        val to = path.getOrNull(index + 1) ?: return@forEachIndexed
        val straight = leg.mode != "car"
        val line = leg.line?.filter { it.size >= 2 }?.map { it[0] to it[1] }?.takeIf { it.size >= 2 }
            ?: listOf(from.longitude to from.latitude, to.longitude to to.latitude)

        if (straight) {
            val ends = listOf(from, to).map { "${it.longitude},${it.latitude}" }.sorted().joinToString(" ")
            if (!straightLegs.add(ends)) {
                gap = true
                return@forEachIndexed
            }
        }

        val last = segments.lastOrNull()
        if (last != null && !last.second && !straight && !gap) {
            last.first += line.drop(1)
        } else {
            segments += line.toMutableList() to straight
        }
        gap = false
    }

    return segments.map { (line, straight) -> RouteSegment(line, straight) }
}

/**
 * The routes of the trips, kept under [directory] as the site served them so they draw
 * offline, and fetched again from [siteUrl] with each refresh.
 */
class TripRoutes(
    private val siteUrl: String,
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _routes = MutableStateFlow<Map<String, List<RouteSegment>>>(emptyMap())

    /** Each trip's route by its id; a trip whose route hasn't been fetched yet is missing. */
    val routes: StateFlow<Map<String, List<RouteSegment>>> = _routes.asStateFlow()

    suspend fun load() = withContext(io) {
        val files = directory.listFiles { file -> file.extension == "json" } ?: emptyArray()
        _routes.value = files.mapNotNull { file -> parse(file.readText())?.let { file.nameWithoutExtension to it } }.toMap()
    }

    /** Fetches every trip's route; a trip that can't be fetched keeps the one it had. */
    suspend fun refresh(ids: List<String>) = withContext(io) {
        directory.mkdirs()
        val routes = _routes.value.filterKeys { it in ids }.toMutableMap()
        for (id in ids) {
            val text = try {
                fetch("$siteUrl/trip/$id/data.json")
            } catch (_: IOException) {
                continue
            }
            val segments = parse(text) ?: continue
            File(directory, "$id.json").writeText(text)
            routes[id] = segments
        }
        directory.listFiles { file -> file.extension == "json" && file.nameWithoutExtension !in ids }?.forEach(File::delete)
        _routes.value = routes
    }

    private fun parse(text: String): List<RouteSegment>? = try {
        segmentsOf(json.decodeFromString(RouteData.serializer(), text))
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode !in 200..299) throw IOException("GET $url answered ${connection.responseCode}")
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
