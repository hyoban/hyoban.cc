package cc.hyoban.moments.data.local

import android.content.Context
import cc.hyoban.moments.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The places around a point, from Photon: OpenStreetMap's, the same the map draws, in
 * WGS 84 as the map and the records are, to name a new place after. Where it can't be
 * reached, the system's geocoder stands in.
 */
class PlaceSearch(private val baseUrl: () -> String, private val client: OkHttpClient = Http.client) {
    constructor(baseUrl: String) : this({ baseUrl })

    /** What is around [fix], within [radiusKm], nearest first. */
    suspend fun nearby(fix: Fix, limit: Int = NEARBY_LIMIT, radiusKm: Double = NEARBY_RADIUS_KM): List<Found> {
        val url = "${baseUrl()}/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", fix.latitude.toString())
            .addQueryParameter("lon", fix.longitude.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("radius", radiusKm.toString())
            .build()
        return parsePhoton(get(url.toString()))
    }

    /**
     * What to call the place at [fix], and what else is around it: the nearest park, sight,
     * shop or quarter Photon knows, else the street; where it can't be reached, the
     * system's geocoder's town.
     */
    suspend fun around(context: Context, fix: Fix): Around {
        val found = runCatching { nearby(fix) }.getOrNull()
        if (found != null) {
            val name = nameAt(found)
            if (name != null) return Around(name, landmarks(found))
        }
        return Around(placeName(context, fix), emptyList())
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET $url answered ${response.code}")
            response.body?.string() ?: throw IOException("GET $url answered without a body")
        }
    }

    companion object {
        private const val NEARBY_LIMIT = 12
        private const val NEARBY_RADIUS_KM = 0.3
        private const val USER_AGENT = "hyoban.cc Moments for Android"
    }
}

/** A place OpenStreetMap knows near a point: what it is called, where, and what kind of thing it is (`leisure`, `park`). */
data class Found(val name: String, val fix: Fix, val kind: String? = null, val type: String? = null)

/** What a place is called, and the named places around it to call it instead. */
data class Around(val name: String?, val landmarks: List<Found>)

/** Photon's GeoJSON answer as places, those without a name left out. */
fun parsePhoton(json: String): List<Found> {
    val features = Json.parseToJsonElement(json).jsonObject["features"] as? JsonArray ?: return emptyList()
    return features.mapNotNull { element ->
        val feature = element as? JsonObject ?: return@mapNotNull null
        val properties = feature["properties"] as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = properties[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = text("name") ?: return@mapNotNull null
        val coordinates = (feature["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return@mapNotNull null
        val longitude = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val latitude = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        Found(name, Fix(latitude, longitude), kind = text("osm_key"), type = text("osm_value"))
    }
}

/** The places around worth naming one after: not the streets, nor a building with no more to it than being one. */
fun landmarks(found: List<Found>): List<Found> =
    found.filter { it.kind != "highway" && it.kind != "railway" && !(it.kind == "building" && it.type == "yes") }
        .distinctBy { it.name }

/** The name for a point from what is around it: the nearest landmark, else the nearest street. */
fun nameAt(found: List<Found>): String? =
    landmarks(found).firstOrNull()?.name ?: found.firstOrNull { it.kind == "highway" }?.name
