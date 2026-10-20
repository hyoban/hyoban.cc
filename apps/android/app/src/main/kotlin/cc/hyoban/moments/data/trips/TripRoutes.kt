package cc.hyoban.moments.data.trips

import cc.hyoban.moments.data.Http
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

// Each trip's route as the site draws it: along the roads it drove, and straight and
// dashed where it took a train, a plane or a ferry. The API fetches every leg's route
// after a write and serves each trip's route already cut into stretches at
// `GET /v1/routes`, which the app keeps a copy of so routes draw offline.

/** A stretch of a route as (longitude, latitude) points: drives run together, and each other leg is [straight], drawn dashed. */
data class RouteSegment(val line: List<Pair<Double, Double>>, val straight: Boolean)

@Serializable
private data class SegmentRecord(val line: List<List<Double>>, val straight: Boolean)

/** `GET /v1/routes`: each published trip's stretches, by its id. */
@Serializable
private data class RoutesResponse(val revision: Long, val trips: Map<String, List<SegmentRecord>>)

/** The routes in an answer from `GET /v1/routes`, or null when it isn't one. */
fun parseRoutes(text: String): Map<String, List<RouteSegment>>? = parseAnswer(text)?.second

/** An answer from `GET /v1/routes`: the revision it is of, and the routes. */
private fun parseAnswer(text: String): Pair<Long, Map<String, List<RouteSegment>>>? = try {
    val answer = Json { ignoreUnknownKeys = true }.decodeFromString(RoutesResponse.serializer(), text)
    answer.revision to answer.trips.mapValues { (_, segments) ->
        segments.map { segment ->
            RouteSegment(segment.line.filter { it.size >= 2 }.map { it[0] to it[1] }, segment.straight)
        }.filter { it.line.size >= 2 }
    }
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** The routes, kept in [file] as the API served them and fetched again from [apiUrl] once its revision moves on. */
class TripRoutes(
    private val apiUrl: () -> String,
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _routes = MutableStateFlow<Map<String, List<RouteSegment>>>(emptyMap())

    /** Each trip's route by its id; a trip whose route hasn't come yet is missing. */
    val routes: StateFlow<Map<String, List<RouteSegment>>> = _routes.asStateFlow()

    /** The API's revision the routes kept are of; null before any are. */
    @Volatile private var revision: Long? = null

    suspend fun load() = withContext(io) {
        if (file.exists()) parseAnswer(file.readText())?.let { (kept, routes) ->
            revision = kept
            _routes.value = routes
        }
    }

    /** Forgets the routes, as when they are to come from another API. */
    suspend fun clear() = withContext(io) {
        file.delete()
        revision = null
        _routes.value = emptyMap()
    }

    /**
     * Fetches every trip's route, unless the ones kept are of [apiRevision], the API's
     * revision as the last sync found it; when that fails, the ones kept stay.
     */
    suspend fun refresh(apiRevision: Long) = withContext(io) {
        if (apiRevision == revision) return@withContext
        val text = try {
            Http.getText("${apiUrl()}/v1/routes", io = io)
        } catch (_: IOException) {
            return@withContext
        }
        val (fetched, routes) = parseAnswer(text) ?: return@withContext
        file.parentFile?.mkdirs()
        file.writeText(text)
        revision = fetched
        _routes.value = routes
    }
}
