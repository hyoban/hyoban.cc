package cc.hyoban.moments.platform

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * What a point is called, from the names the basemap labels: Mapbox's geocoders know
 * little of China past the city, but the streets tiles name its schools, compounds and
 * malls. Asks the Tilequery API for the labelled places around the point and ranks them
 * by how big they are and how near; nothing when there's no token or no network.
 */
class PlaceNames(private val token: String) {
    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
    }

    suspend fun near(latitude: Double, longitude: Double): List<String> {
        if (token.isBlank()) return emptyList()
        // About ten metres: the same names for a pin nudged a little.
        val key = String.format(Locale.ROOT, "%.4f,%.4f", longitude, latitude)
        cache[key]?.let { return it }
        val names = withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("https://api.mapbox.com/v4/mapbox.mapbox-streets-v8/tilequery/$key.json?radius=$RADIUS&limit=50&layers=poi_label&access_token=$token")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                try {
                    rank(connection.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "Tilequery failed", it) }.getOrNull()
        } ?: return emptyList()
        cache[key] = names
        return names
    }

    companion object {
        private const val TAG = "MomentsPlaceNames"
        private const val RADIUS = 400

        /**
         * The names in a Tilequery answer, likeliest first. A label's `sizerank` grows as
         * what it marks shrinks (a campus 4, its canteen 14, a shop 16); thirty metres
         * further off counts as one step smaller, so the school wins over its classroom
         * block and the compound you're in over the bigger one down the road.
         */
        fun rank(body: String, take: Int = 4): List<String> =
            Json.parseToJsonElement(body).jsonObject["features"]?.jsonArray.orEmpty()
                .mapNotNull { feature ->
                    val properties = feature.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
                    val name = properties.text("name_zh-Hans") ?: properties.text("name") ?: return@mapNotNull null
                    val size = properties["sizerank"]?.jsonPrimitive?.intOrNull ?: 16
                    val distance = properties["tilequery"]?.jsonObject?.get("distance")?.jsonPrimitive?.doubleOrNull ?: RADIUS.toDouble()
                    name to size + distance / 30
                }
                .sortedBy { it.second }
                .map { it.first }
                .distinct()
                .take(take)

        private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}
