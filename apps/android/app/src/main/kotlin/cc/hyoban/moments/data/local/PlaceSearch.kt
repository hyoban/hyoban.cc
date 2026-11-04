package cc.hyoban.moments.data.local

import android.content.Context
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.spec.PlaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The places around a point, to name a new place after, in WGS 84 as the map and the
 * records are. In China, with the owner's AMap or Tencent key, they come from that service's
 * place search, asked from the phone, as OpenStreetMap knows few shops there: with both keys,
 * the one the owner chose, the other when it can't answer; elsewhere, without a key, or when neither
 * can answer, from Photon at [baseUrl]: OpenStreetMap's, the same the map draws. Where neither can be reached, the system's geocoder stands in.
 */
class PlaceSearch(
    private val baseUrl: () -> String,
    private val tencentKey: () -> String? = { null },
    private val amapKey: () -> String? = { null },
    private val tencentFirst: () -> Boolean = { false },
    private val client: OkHttpClient = Http.client,
) {
    constructor(baseUrl: String, tencentKey: () -> String? = { null }, amapKey: () -> String? = { null }, tencentFirst: () -> Boolean = { false }) :
        this({ baseUrl }, tencentKey, amapKey, tencentFirst)

    /** AMap's and Tencent's answers this run, by what was asked, so a pin moved back costs none of a key's day's calls. */
    private val kept = object : LinkedHashMap<String, List<Found>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Found>>) = size > KEPT
    }

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

    /** The places called [text], nearest [near] first: AMap's or Tencent's in China, else Photon's. */
    suspend fun search(text: String, near: Fix): List<Found> {
        china(near, text)?.takeIf { it.isNotEmpty() }?.let { return it }
        val url = "${baseUrl()}/api".toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("lat", near.latitude.toString())
            .addQueryParameter("lon", near.longitude.toString())
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        return parsePhoton(get(url.toString()))
    }

    /** What the chosen of AMap and Tencent knows around [fix], or called [text], else what the other does; null where neither has anything. */
    private suspend fun china(fix: Fix, text: String? = null): List<Found>? =
        if (tencentFirst()) tencent(fix, text)?.takeIf { it.isNotEmpty() } ?: amap(fix, text)
        else amap(fix, text)?.takeIf { it.isNotEmpty() } ?: tencent(fix, text)

    /**
     * What AMap knows within [AROUND_METRES] of [fix], or called [text] nearest it; null where
     * it has nothing to say: outside China, without the key, or when it can't answer, its calls
     * for the day used up among them. Asked as Tencent is, in GCJ-02 taken to about 100 m.
     */
    private suspend fun amap(fix: Fix, text: String? = null): List<Found>? {
        val key = amapKey()?.takeIf { it.isNotBlank() } ?: return null
        if (!inChina(fix)) return null
        val shifted = wgsToGcj(snap(fix))
        val at = "${shifted.longitude},${shifted.latitude}"
        val url = (if (text == null) "$AMAP/v5/place/around" else "$AMAP/v3/assistant/inputtips").toHttpUrl().newBuilder()
            .apply {
                addQueryParameter("location", at)
                if (text == null) {
                    addQueryParameter("radius", AROUND_METRES.toString())
                    addQueryParameter("sortrule", "distance")
                    addQueryParameter("page_size", AMAP_PAGE.toString())
                } else {
                    addQueryParameter("keywords", text)
                    addQueryParameter("datatype", "poi")
                }
            }
            .build()
        return ask("AMap", url, key, ::parseAmap)
    }

    /**
     * What Tencent knows within [AROUND_METRES] of [fix], or called [text] nearest it; null
     * where it has nothing to say: outside China, without the key, or when it can't answer,
     * its calls for the day or the second used up among them. The point goes there in GCJ-02,
     * taken to about 100 m so a pin moved a little asks what was asked already.
     */
    private suspend fun tencent(fix: Fix, text: String? = null): List<Found>? {
        val key = tencentKey()?.takeIf { it.isNotBlank() } ?: return null
        if (!inChina(fix)) return null
        val shifted = wgsToGcj(snap(fix))
        val at = "${shifted.latitude},${shifted.longitude}"
        val url = (if (text == null) "$TENCENT/explore" else "$TENCENT/suggestion").toHttpUrl().newBuilder()
            .apply {
                if (text == null) {
                    addQueryParameter("boundary", "nearby($at,$AROUND_METRES,0)")
                    addQueryParameter("policy", "2")
                } else {
                    addQueryParameter("keyword", text)
                    addQueryParameter("location", at)
                }
            }
            .addQueryParameter("page_size", TENCENT_PAGE.toString())
            .build()
        return ask("Tencent", url, key, ::parseTencent)
    }

    /** A point taken to about 100 m, so a pin moved a little asks what was asked already. */
    private fun snap(fix: Fix) = Fix(Math.round(fix.latitude * 1e3) / 1e3, Math.round(fix.longitude * 1e3) / 1e3)

    /** [url] asked with [key] and read by [parse], kept by what was asked; null when the service can't answer. */
    private suspend fun ask(service: String, url: okhttp3.HttpUrl, key: String, parse: (String) -> List<Found>): List<Found>? {
        val id = url.toString()
        synchronized(kept) { kept[id] }?.let { return it }
        val found = runCatching { parse(get(url.newBuilder().addQueryParameter("key", key).build().toString())) }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) android.util.Log.w("Moments", "Asking $service for places failed: ${it.message?.replace(key, "…")}") }
            .getOrNull() ?: return null
        synchronized(kept) { kept[id] = found }
        return found
    }

    /**
     * What to call the place at [fix], and what else is around it: the nearest shop, sight
     * or building AMap or Tencent knows in China, else the nearest park, sight, shop or quarter
     * Photon knows, else the street; where neither can be reached, the system's geocoder's town.
     */
    suspend fun around(context: Context, fix: Fix): Around {
        val found = china(fix)?.takeIf { it.isNotEmpty() } ?: runCatching { nearby(fix) }.getOrNull()
        if (found != null) {
            val name = nameAt(found)
            if (name != null) return Around(name, landmarks(found), landmarks(found).firstOrNull()?.let(::kindOf))
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
        private const val SEARCH_LIMIT = 20
        private const val KEPT = 100
        private const val TENCENT = "https://apis.map.qq.com/ws/place/v1"
        /** How far around the pin Tencent is asked what there is, in metres. */
        private const val AROUND_METRES = 400
        private const val TENCENT_PAGE = 20
        private const val AMAP = "https://restapi.amap.com"
        private const val AMAP_PAGE = 20
        private const val USER_AGENT = "hyoban.cc Moments for Android"
    }
}

/**
 * A place known near a point: what it is called, where, and what kind of thing it is, as
 * OpenStreetMap says it (`leisure`, `park`) or, from AMap or Tencent, as a place's [placeKind];
 * and its [address] where they give one.
 */
data class Found(
    val name: String,
    val fix: Fix,
    val kind: String? = null,
    val type: String? = null,
    val address: String? = null,
    val placeKind: PlaceKind? = null,
)

/** What a place is called, the named places around it to call it instead, and what sort of place the name is of. */
data class Around(val name: String?, val landmarks: List<Found>, val kind: PlaceKind? = null)

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

/**
 * Tencent's answer as places, shifted from its GCJ-02 back to WGS 84, each with what its
 * category says it is; one that isn't an answer is an error, as when the key's calls are
 * used up, and the nameless are left out.
 */
fun parseTencent(json: String): List<Found> {
    val answer = Json.parseToJsonElement(json).jsonObject
    val status = answer["status"]?.jsonPrimitive?.intOrNull
    if (status != 0) throw IOException("Tencent answered $status: ${answer["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
    val data = answer["data"] as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val place = element as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = place[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = text("title") ?: return@mapNotNull null
        val location = place["location"] as? JsonObject ?: return@mapNotNull null
        val latitude = location["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val longitude = location["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        Found(name, gcjToWgs(Fix(latitude, longitude)), address = text("address"), placeKind = tencentKind(text("category")))
    }
}

/** What sort of place Tencent's category says it is, `美食:中餐厅:粤菜` food; null where it says too little. */
fun tencentKind(category: String?): PlaceKind? {
    val parts = category.orEmpty().split(':')
    val second = parts.getOrNull(1).orEmpty()
    return when (parts.first()) {
        "美食" -> PlaceKind.FOOD
        "购物" -> PlaceKind.SHOPPING
        "酒店宾馆" -> PlaceKind.STAY
        "旅游景点", "自然地物", "文化场馆" -> PlaceKind.SCENIC
        "基础设施" -> if (second == "交通设施") PlaceKind.TRANSPORT else null
        "娱乐休闲" -> if (listOf("咖啡", "茶", "酒吧").any { it in second }) PlaceKind.FOOD else null
        "汽车" -> if (listOf("充电", "加油", "停车").any { it in category.orEmpty() }) PlaceKind.TRANSPORT else null
        else -> null
    }
}

/**
 * AMap's answer, its places around (v5) or its input tips (v3), as places shifted from GCJ-02
 * back to WGS 84, each with what its type code says it is; one that isn't an answer is an
 * error, as when the key's calls are used up, and tips with no name or point (a bus line, a
 * category) are left out. Fields it has nothing for come as `[]`.
 */
fun parseAmap(json: String): List<Found> {
    val answer = Json.parseToJsonElement(json).jsonObject
    fun JsonObject.text(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    if (answer.text("status") != "1") throw IOException("AMap answered ${answer.text("infocode")}: ${answer.text("info").orEmpty()}")
    val places = (answer["pois"] ?: answer["tips"]) as? JsonArray ?: return emptyList()
    return places.mapNotNull { element ->
        val place = element as? JsonObject ?: return@mapNotNull null
        val name = place.text("name") ?: return@mapNotNull null
        val (longitude, latitude) = place.text("location")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 } ?: return@mapNotNull null
        Found(name, gcjToWgs(Fix(latitude, longitude)), address = place.text("address"), placeKind = amapKind(place.text("typecode")))
    }
}

/** What sort of place AMap's type code says it is, `050100` food; null where it says too little. Several codes take the first. */
fun amapKind(typecode: String?): PlaceKind? {
    val code = typecode?.substringBefore('|')?.takeIf { it.length == 6 } ?: return null
    return when {
        code.startsWith("05") -> PlaceKind.FOOD
        code.startsWith("06") -> PlaceKind.SHOPPING
        code.startsWith("10") -> PlaceKind.STAY
        code.startsWith("11") -> PlaceKind.SCENIC
        code.startsWith("15") -> PlaceKind.TRANSPORT
        code.startsWith("0101") || code.startsWith("0111") -> PlaceKind.TRANSPORT // fuel, charging
        code.take(4) in setOf("1401", "1402", "1404") -> PlaceKind.SCENIC // museums, exhibitions, galleries
        code == "080304" -> PlaceKind.FOOD // bars
        else -> null
    }
}

/** The places around worth naming one after: not the streets, nor a building with no more to it than being one. */
fun landmarks(found: List<Found>): List<Found> =
    found.filter { it.kind != "highway" && it.kind != "railway" && !(it.kind == "building" && it.type == "yes") }
        .distinctBy { it.name }

/** The name for a point from what is around it: the nearest landmark, else the nearest street. */
fun nameAt(found: List<Found>): String? =
    landmarks(found).firstOrNull()?.name ?: found.firstOrNull { it.kind == "highway" }?.name

/** What sort of place an OpenStreetMap feature is, by its key and value; null where that says too little. */
fun kindOf(found: Found): PlaceKind? = found.placeKind ?: when (found.kind) {
    "place" -> when (found.type) {
        "city" -> PlaceKind.CITY
        "town", "village", "hamlet", "county", "municipality" -> PlaceKind.TOWN
        "region", "state", "province", "sea", "island", "archipelago" -> PlaceKind.REGION
        else -> null
    }
    "amenity" -> when (found.type) {
        "restaurant", "cafe", "fast_food", "food_court", "bar", "pub", "biergarten", "ice_cream" -> PlaceKind.FOOD
        "marketplace" -> PlaceKind.SHOPPING
        "parking", "bus_station", "ferry_terminal", "charging_station", "fuel" -> PlaceKind.TRANSPORT
        "place_of_worship", "theatre", "arts_centre", "university" -> PlaceKind.SCENIC
        else -> null
    }
    "shop" -> PlaceKind.SHOPPING
    "tourism" -> when (found.type) {
        "hotel", "hostel", "guest_house", "motel", "apartment", "chalet", "camp_site", "caravan_site" -> PlaceKind.STAY
        "information" -> null
        else -> PlaceKind.SCENIC
    }
    "leisure" -> when (found.type) {
        "park", "garden", "nature_reserve", "beach_resort", "marina" -> PlaceKind.SCENIC
        else -> null
    }
    "natural", "historic", "waterway", "mountain_pass" -> PlaceKind.SCENIC
    "aeroway", "railway", "public_transport" -> PlaceKind.TRANSPORT
    "building" -> when (found.type) {
        "train_station", "transportation" -> PlaceKind.TRANSPORT
        "hotel" -> PlaceKind.STAY
        "retail", "commercial" -> PlaceKind.SHOPPING
        "temple", "church", "museum" -> PlaceKind.SCENIC
        else -> null
    }
    else -> null
}
