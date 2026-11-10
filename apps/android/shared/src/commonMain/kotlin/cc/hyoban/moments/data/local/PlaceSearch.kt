package cc.hyoban.moments.data.local

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
 * records are. In China, with the owner's AMap or Tencent key, they come from that service,
 * asked from the phone, as OpenStreetMap knows few shops there: with both keys, the one the
 * owner chose, the other when it can't answer. What is around a point comes from their place
 * searches first, which know the shops nearest, then from their reverse geocoding, whose calls
 * are thirty times as many but which knows mostly sights and buildings; what is called
 * something comes from their input tips. Elsewhere, without a key, or when neither
 * can answer, from Photon at [baseUrl]: OpenStreetMap's, the same the map draws. Where neither can be reached, the system's geocoder stands in.
 */
/** Photon, over the same OpenStreetMap data the map draws: where places are searched by name and found around a pin. */
const val PHOTON_URL = "https://photon.komoot.io"

class PlaceSearch(
    private val baseUrl: () -> String,
    private val tencentKey: () -> String? = { null },
    private val amapKey: () -> String? = { null },
    private val tencentFirst: () -> Boolean = { false },
    private val client: OkHttpClient = Http.client,
    /** What the device itself calls the place at a point, a town or a part of one, when no service can be reached; nothing where it has no geocoder. */
    private val townAt: suspend (Fix) -> String? = { null },
) {
    constructor(
        baseUrl: String,
        tencentKey: () -> String? = { null },
        amapKey: () -> String? = { null },
        tencentFirst: () -> Boolean = { false },
        townAt: suspend (Fix) -> String? = { null },
    ) : this({ baseUrl }, tencentKey, amapKey, tencentFirst, townAt = townAt)

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

    /**
     * What AMap and Tencent know around [fix], or called [text]; null where neither has anything.
     * Called something, the chosen one's tips, else the other's. Around, the better answers
     * first: the chosen one's place search, the other's, then the chosen one's reverse
     * geocoding and the other's, so the shops nearest come while their calls last.
     */
    suspend fun china(fix: Fix, text: String? = null): List<Found>? {
        if (!inChina(fix)) return null
        val asks: List<suspend () -> List<Found>?> = if (text != null) {
            listOf({ amapTips(fix, text) }, { tencentTips(fix, text) })
        } else {
            listOf({ amapAround(fix) }, { tencentAround(fix) }, { amapRegeo(fix) }, { tencentGeocoder(fix) })
        }
        // Each pair is AMap's then Tencent's; with Tencent chosen, each pair turns round.
        val ordered = if (tencentFirst()) asks.chunked(2).flatMap { it.reversed() } else asks
        var last: List<Found>? = null
        for (ask in ordered) {
            val found = ask() ?: continue
            if (found.isNotEmpty()) return found
            last = found
        }
        return last
    }

    /** The point as AMap and Tencent take it: in GCJ-02, taken to about 100 m so a pin moved a little asks what was asked already. */
    private fun gcj(fix: Fix) = wgsToGcj(snap(fix))

    /** AMap's places within [AROUND_METRES] of [fix], nearest first, from its place search: the shops nearest, from its smaller allowance. */
    private suspend fun amapAround(fix: Fix): List<Found>? = amap("$AMAP/v5/place/around", fix) {
        addQueryParameter("radius", AROUND_METRES.toString())
        addQueryParameter("sortrule", "distance")
        addQueryParameter("page_size", AMAP_PAGE.toString())
    }

    /** AMap's places within [AROUND_METRES] of [fix], from its reverse geocoding: mostly sights and buildings, from an allowance thirty times as large. */
    private suspend fun amapRegeo(fix: Fix): List<Found>? = amap("$AMAP/v3/geocode/regeo", fix) {
        addQueryParameter("radius", AROUND_METRES.toString())
        addQueryParameter("extensions", "all")
    }

    /** AMap's places called [text], nearest [fix] first. */
    private suspend fun amapTips(fix: Fix, text: String): List<Found>? = amap("$AMAP/v3/assistant/inputtips", fix) {
        addQueryParameter("keywords", text)
        addQueryParameter("datatype", "poi")
    }

    /** [endpoint] of AMap asked about [fix]; null without the key or when it can't answer, its calls used up among them. */
    private suspend fun amap(endpoint: String, fix: Fix, query: okhttp3.HttpUrl.Builder.() -> Unit): List<Found>? {
        val key = amapKey()?.takeIf { it.isNotBlank() } ?: return null
        val at = gcj(fix)
        val url = endpoint.toHttpUrl().newBuilder().addQueryParameter("location", "${at.longitude},${at.latitude}").apply(query).build()
        return ask("AMap", url, key, ::parseAmap)
    }

    /** Tencent's places within [AROUND_METRES] of [fix], from its place search: the shops nearest, from its smaller allowance. */
    private suspend fun tencentAround(fix: Fix): List<Found>? = tencent("$TENCENT/place/v1/explore", fix, near = false) {
        val at = gcj(fix)
        addQueryParameter("boundary", "nearby(${at.latitude},${at.longitude},$AROUND_METRES,0)")
        addQueryParameter("policy", "2")
        addQueryParameter("page_size", TENCENT_PAGE.toString())
    }

    /**
     * Tencent's places within [AROUND_METRES] of [fix], from its reverse geocoding, from an
     * allowance thirty times as large: the shops and sights first (policy 4) rather than the
     * buildings at the point.
     */
    private suspend fun tencentGeocoder(fix: Fix): List<Found>? = tencent("$TENCENT/geocoder/v1/", fix) {
        addQueryParameter("get_poi", "1")
        addQueryParameter("poi_options", "radius=$AROUND_METRES;page_size=$TENCENT_PAGE;policy=4")
    }

    /** Tencent's places called [text], nearest [fix] first. */
    private suspend fun tencentTips(fix: Fix, text: String): List<Found>? = tencent("$TENCENT/place/v1/suggestion", fix) {
        addQueryParameter("keyword", text)
        addQueryParameter("page_size", TENCENT_PAGE.toString())
    }

    /** [endpoint] of Tencent asked about [fix], as `location` where [near]; null without the key or when it can't answer, its calls for the day or the second used up among them. */
    private suspend fun tencent(endpoint: String, fix: Fix, near: Boolean = true, query: okhttp3.HttpUrl.Builder.() -> Unit): List<Found>? {
        val key = tencentKey()?.takeIf { it.isNotBlank() } ?: return null
        val at = gcj(fix)
        val url = endpoint.toHttpUrl().newBuilder()
            .apply { if (near) addQueryParameter("location", "${at.latitude},${at.longitude}") }
            .apply(query).build()
        return ask("Tencent", url, key, ::parseTencent)
    }

    /** A point taken to about 100 m, so a pin moved a little asks what was asked already. */
    private fun snap(fix: Fix) = Fix(Math.round(fix.latitude * 1e3) / 1e3, Math.round(fix.longitude * 1e3) / 1e3)

    /**
     * [url] asked with [key] and read by [parse], kept by what was asked; null when the service
     * can't answer. An endpoint whose calls are used up isn't asked again until tomorrow, so the
     * next one answers at once.
     */
    private suspend fun ask(service: String, url: okhttp3.HttpUrl, key: String, parse: (String) -> List<Found>): List<Found>? {
        val id = url.toString()
        val endpoint = url.host + url.encodedPath
        synchronized(kept) {
            kept[id]?.let { return it }
            if (spent[endpoint] == today()) return null
        }
        val found = runCatching { parse(get(url.newBuilder().addQueryParameter("key", key).build().toString())) }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                if (it is CallsUsedUp) synchronized(kept) { spent[endpoint] = today() }
                // Not said off the phone, in a unit test, where Log is a stub.
                runCatching { cc.hyoban.moments.logWarning("Moments", "Asking $service for places failed: ${it.message?.replace(key, "…")}") }
            }
            .getOrNull() ?: return null
        synchronized(kept) { kept[id] = found }
        return found
    }

    /** The endpoints whose calls were used up, each with the day it was, as [today] says. */
    private val spent = mutableMapOf<String, Long>()

    /**
     * What to call the place at [fix], and what else is around it: the nearest shop, sight
     * or building AMap or Tencent knows in China, else the nearest park, sight, shop or quarter
     * Photon knows, else the street; where neither can be reached, the town the device's own geocoder gives.
     */
    suspend fun around(fix: Fix): Around {
        val found = china(fix)?.takeIf { it.isNotEmpty() } ?: runCatching { nearby(fix) }.getOrNull()
        if (found != null) {
            val name = nameAt(found)
            if (name != null) return Around(name, landmarks(found), landmarks(found).firstOrNull()?.let(::kindOf))
        }
        return Around(townAt(fix), emptyList())
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
        private const val TENCENT = "https://apis.map.qq.com/ws"
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
    /** The map service's own id for it, so the same place picked again is the one already kept. */
    val source: PlaceSource? = null,
)

/** A map service's answer that the key's calls of that endpoint are used up for now, so it isn't asked again until tomorrow. */
class CallsUsedUp(message: String) : IOException(message)

/** Tencent's status for a key's calls of the day used up; 120, those of the second, passes. */
private const val TENCENT_DAY_USED_UP = 121

/** AMap's codes for a key's or its account's calls used up, at home or abroad. */
private val AMAP_USED_UP = setOf("10003", "10044", "10045")

/** Today in China, where both services count their calls, as days since 1970. */
fun today(): Long = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toEpochDay()

/** A place as a map service knows it: `amap` or `tencent`, and that service's id; a record's `source`. */
data class PlaceSource(val app: String, val venue: String)

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
 * Tencent's answer, its reverse geocoding's places around or its suggestions, as places
 * shifted from its GCJ-02 back to WGS 84, each with what its
 * category says it is; one that isn't an answer is an error, as when the key's calls are
 * used up, and the nameless are left out.
 */
fun parseTencent(json: String): List<Found> {
    val answer = Json.parseToJsonElement(json).jsonObject
    val status = answer["status"]?.jsonPrimitive?.intOrNull
    if (status != 0) {
        val message = "Tencent answered $status: ${answer["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
        throw if (status == TENCENT_DAY_USED_UP) CallsUsedUp(message) else IOException(message)
    }
    val data = (answer["data"] ?: (answer["result"] as? JsonObject)?.get("pois")) as? JsonArray ?: return emptyList()
    return data.mapNotNull { element ->
        val place = element as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = place[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = text("title") ?: return@mapNotNull null
        val location = place["location"] as? JsonObject ?: return@mapNotNull null
        val latitude = location["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val longitude = location["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        Found(
            name,
            gcjToWgs(Fix(latitude, longitude)),
            address = text("address"),
            placeKind = tencentKind(text("category")),
            source = text("id")?.let { PlaceSource("tencent", it) },
        )
    }
}

/** What sort of place Tencent's category says it is, `美食:咖啡厅` a café; null where it says too little. */
fun tencentKind(category: String?): PlaceKind? {
    val text = category.orEmpty()
    val parts = text.split(':')
    val rest = parts.drop(1).joinToString(":")
    fun has(vararg words: String) = words.any { it in rest }
    return when (parts.first()) {
        "美食" -> when {
            has("咖啡") -> PlaceKind.CAFE
            has("茶", "冷饮", "饮品") -> PlaceKind.TEA
            has("甜品", "面包", "蛋糕", "烘焙") -> PlaceKind.DESSERT
            has("酒吧") -> PlaceKind.BAR
            else -> PlaceKind.FOOD
        }
        "购物" -> when {
            has("超市", "便利店") -> PlaceKind.SUPERMARKET
            has("市场") -> PlaceKind.MARKET
            else -> PlaceKind.SHOPPING
        }
        "酒店宾馆" -> if (has("露营")) PlaceKind.CAMP else PlaceKind.STAY
        "旅游景点" -> when {
            has("公园", "植物园", "动物园") -> PlaceKind.PARK
            has("寺", "庙", "教堂", "宗教", "道观") -> PlaceKind.TEMPLE
            has("博物馆", "纪念馆", "展览") -> PlaceKind.MUSEUM
            has("山", "湖", "海滩", "自然") -> PlaceKind.NATURE
            else -> PlaceKind.SCENIC
        }
        "自然地物" -> PlaceKind.NATURE
        "文化场馆" -> if (has("剧", "音乐厅")) PlaceKind.SHOW else PlaceKind.MUSEUM
        "娱乐休闲" -> when {
            has("咖啡") -> PlaceKind.CAFE
            has("茶") -> PlaceKind.TEA
            has("酒吧") -> PlaceKind.BAR
            has("电影", "剧", "演出") -> PlaceKind.SHOW
            else -> PlaceKind.LEISURE
        }
        "运动健身" -> PlaceKind.SPORT
        "医疗保健" -> PlaceKind.HOSPITAL
        "教育学校" -> PlaceKind.SCHOOL
        "房产小区" -> if (has("住宅")) PlaceKind.RESIDENTIAL else PlaceKind.WORK
        "公司企业" -> PlaceKind.WORK
        "生活服务" -> PlaceKind.LIFE
        "基础设施" -> if (!has("交通")) null else when {
            has("机场") -> PlaceKind.AIRPORT
            has("火车站") -> PlaceKind.STATION
            has("地铁") -> PlaceKind.METRO
            has("公交", "长途") -> PlaceKind.BUS
            has("港口", "码头", "渡口") -> PlaceKind.PORT
            has("停车") -> PlaceKind.PARKING
            else -> PlaceKind.TRANSPORT
        }
        "汽车" -> when {
            has("充电") -> PlaceKind.CHARGING
            has("加油") -> PlaceKind.FUEL
            has("停车") -> PlaceKind.PARKING
            else -> null
        }
        else -> null
    }
}

/**
 * AMap's answer, its reverse geocoding's places around, nearest first, or its input tips, as
 * places shifted from GCJ-02 back to WGS 84, each with what its type code says it is (or its
 * type's words, where reverse geocoding gives no code); one that isn't an answer is an
 * error, as when the key's calls are used up, and tips with no name or point (a bus line, a
 * category) are left out. Fields it has nothing for come as `[]`.
 */
fun parseAmap(json: String): List<Found> {
    val answer = Json.parseToJsonElement(json).jsonObject
    fun JsonObject.text(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    if (answer.text("status") != "1") {
        val code = answer.text("infocode")
        val message = "AMap answered $code: ${answer.text("info").orEmpty()}"
        throw if (code in AMAP_USED_UP) CallsUsedUp(message) else IOException(message)
    }
    val around = (answer["regeocode"] as? JsonObject)?.get("pois") as? JsonArray
    val places = around ?: (answer["pois"] ?: answer["tips"]) as? JsonArray ?: return emptyList()
    val ordered = if (around == null) places else places.sortedBy { (it as? JsonObject)?.text("distance")?.toDoubleOrNull() ?: Double.MAX_VALUE }
    return ordered.mapNotNull { element ->
        val place = element as? JsonObject ?: return@mapNotNull null
        val name = place.text("name") ?: return@mapNotNull null
        val (longitude, latitude) = place.text("location")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 2 } ?: return@mapNotNull null
        Found(
            name,
            gcjToWgs(Fix(latitude, longitude)),
            address = place.text("address"),
            placeKind = amapKind(place.text("typecode") ?: amapTypecode(place.text("type"))),
            source = place.text("id")?.let { PlaceSource("amap", it) },
        )
    }
}

/**
 * The type code for AMap's type in words, `餐饮服务;咖啡厅;星巴克咖啡` 050500, as far as
 * [amapKind] reads codes; null for a type it would make nothing of.
 */
fun amapTypecode(type: String?): String? {
    val words = type?.split('|')?.first()?.split(';') ?: return null
    val big = AMAP_TYPES[words.first()] ?: return null
    val mid = AMAP_SUBTYPES["$big${words.getOrNull(1).orEmpty()}"] ?: return big + "0000"
    val small = when ("$mid${words.getOrNull(2).orEmpty()}") {
        "0803酒吧" -> "04"
        "1102寺庙道观" -> "05"
        else -> "00"
    }
    return mid + small
}

/** AMap's kinds in words, by their type codes' first two figures. */
private val AMAP_TYPES = mapOf(
    "汽车服务" to "01", "餐饮服务" to "05", "购物服务" to "06", "生活服务" to "07", "体育休闲服务" to "08",
    "医疗保健服务" to "09", "住宿服务" to "10", "风景名胜" to "11", "商务住宅" to "12", "科教文化服务" to "14",
    "交通设施服务" to "15", "公司企业" to "17",
)

/** The classes [amapKind] tells apart, by their kind's figures and their words, to their four figures. */
private val AMAP_SUBTYPES = mapOf(
    "01加油站" to "0101", "01充电站" to "0111",
    "05咖啡厅" to "0505", "05茶艺馆" to "0506", "05冷饮店" to "0507", "05糕饼店" to "0508", "05甜品店" to "0509",
    "06便民商店/便利店" to "0602", "06超级市场" to "0604", "06综合市场" to "0607",
    "08运动场馆" to "0801", "08娱乐场所" to "0803", "08影剧院" to "0806",
    "11公园广场" to "1101", "11风景名胜" to "1102",
    "12住宅区" to "1203",
    "14博物馆" to "1401", "14展览馆" to "1402", "14会展中心" to "1403", "14美术馆" to "1404", "14图书馆" to "1405",
    "14文化宫" to "1409", "14学校" to "1412",
    "15机场相关" to "1501", "15火车站" to "1502", "15港口码头" to "1503", "15长途汽车站" to "1504", "15地铁站" to "1505",
    "15公交车站" to "1507", "15停车场" to "1509",
)

/**
 * What sort of place AMap's type code says it is, `050500` a café, by its kind and then its
 * class; null where it says too little. Of several codes the first is taken.
 */
fun amapKind(typecode: String?): PlaceKind? {
    val code = typecode?.substringBefore('|')?.takeIf { it.length == 6 } ?: return null
    val big = code.take(2)
    val mid = code.take(4)
    return when {
        mid == "0101" -> PlaceKind.FUEL
        mid == "0111" -> PlaceKind.CHARGING
        big == "05" -> when (mid) {
            "0505" -> PlaceKind.CAFE
            "0506", "0507" -> PlaceKind.TEA
            "0508", "0509" -> PlaceKind.DESSERT
            else -> PlaceKind.FOOD
        }
        big == "06" -> when (mid) {
            "0602", "0604" -> PlaceKind.SUPERMARKET
            "0607" -> PlaceKind.MARKET
            else -> PlaceKind.SHOPPING
        }
        big == "07" -> PlaceKind.LIFE
        big == "08" -> when {
            code == "080304" -> PlaceKind.BAR
            mid == "0801" -> PlaceKind.SPORT
            mid == "0806" -> PlaceKind.SHOW
            else -> PlaceKind.LEISURE
        }
        big == "09" -> PlaceKind.HOSPITAL
        big == "10" -> PlaceKind.STAY
        big == "11" -> when {
            mid == "1101" -> PlaceKind.PARK
            code == "110205" -> PlaceKind.TEMPLE
            else -> PlaceKind.SCENIC
        }
        big == "12" -> if (mid == "1203") PlaceKind.RESIDENTIAL else PlaceKind.WORK
        big == "14" -> when (mid) {
            "1401", "1402", "1403", "1404", "1405", "1409" -> PlaceKind.MUSEUM
            "1412" -> PlaceKind.SCHOOL
            else -> null
        }
        big == "15" -> when (mid) {
            "1501" -> PlaceKind.AIRPORT
            "1502" -> PlaceKind.STATION
            "1503" -> PlaceKind.PORT
            "1505" -> PlaceKind.METRO
            "1504", "1507" -> PlaceKind.BUS
            "1509" -> PlaceKind.PARKING
            else -> PlaceKind.TRANSPORT
        }
        big == "17" -> PlaceKind.WORK
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
        "cafe" -> PlaceKind.CAFE
        "bar", "pub", "biergarten" -> PlaceKind.BAR
        "ice_cream" -> PlaceKind.DESSERT
        "restaurant", "fast_food", "food_court" -> PlaceKind.FOOD
        "marketplace" -> PlaceKind.MARKET
        "parking" -> PlaceKind.PARKING
        "charging_station" -> PlaceKind.CHARGING
        "fuel" -> PlaceKind.FUEL
        "bus_station" -> PlaceKind.BUS
        "ferry_terminal" -> PlaceKind.PORT
        "place_of_worship" -> PlaceKind.TEMPLE
        "theatre", "cinema", "arts_centre" -> PlaceKind.SHOW
        "library" -> PlaceKind.MUSEUM
        "university", "college", "school" -> PlaceKind.SCHOOL
        "hospital", "clinic" -> PlaceKind.HOSPITAL
        else -> null
    }
    "shop" -> when (found.type) {
        "supermarket", "convenience" -> PlaceKind.SUPERMARKET
        "bakery", "confectionery", "pastry" -> PlaceKind.DESSERT
        "tea" -> PlaceKind.TEA
        else -> PlaceKind.SHOPPING
    }
    "tourism" -> when (found.type) {
        "hotel", "hostel", "guest_house", "motel", "apartment", "chalet" -> PlaceKind.STAY
        "camp_site", "caravan_site" -> PlaceKind.CAMP
        "museum", "gallery" -> PlaceKind.MUSEUM
        "zoo", "theme_park", "aquarium" -> PlaceKind.LEISURE
        "information" -> null
        else -> PlaceKind.SCENIC
    }
    "leisure" -> when (found.type) {
        "park", "garden" -> PlaceKind.PARK
        "nature_reserve", "beach_resort" -> PlaceKind.NATURE
        "marina" -> PlaceKind.PORT
        "sports_centre", "stadium", "fitness_centre", "pitch", "swimming_pool" -> PlaceKind.SPORT
        else -> null
    }
    "natural", "waterway", "mountain_pass" -> PlaceKind.NATURE
    "historic" -> PlaceKind.SCENIC
    "aeroway" -> PlaceKind.AIRPORT
    "railway", "public_transport" -> when (found.type) {
        "subway_entrance" -> PlaceKind.METRO
        "station", "halt" -> if (found.kind == "railway") PlaceKind.STATION else PlaceKind.TRANSPORT
        else -> PlaceKind.TRANSPORT
    }
    "building" -> when (found.type) {
        "train_station" -> PlaceKind.STATION
        "transportation" -> PlaceKind.TRANSPORT
        "hotel" -> PlaceKind.STAY
        "retail", "commercial" -> PlaceKind.SHOPPING
        "temple", "church", "mosque", "cathedral" -> PlaceKind.TEMPLE
        "museum" -> PlaceKind.MUSEUM
        "hospital" -> PlaceKind.HOSPITAL
        "school", "university" -> PlaceKind.SCHOOL
        "office" -> PlaceKind.WORK
        "apartments", "residential" -> PlaceKind.RESIDENTIAL
        else -> null
    }
    "office" -> PlaceKind.WORK
    else -> null
}
