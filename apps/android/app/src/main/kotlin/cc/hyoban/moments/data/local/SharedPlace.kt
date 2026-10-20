package cc.hyoban.moments.data.local

import cc.hyoban.moments.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A place shared into the app from a map: what it is called and where it is, in WGS 84. */
data class SharedPlace(val name: String?, val fix: Fix)

/**
 * A place shared from Amap, as its share sheet sends it to another app: the place's name
 * and a short link, `无为市绣溪公园 https://surl.amap.com/…`. The link redirects to
 * `wb.amap.com/?p=<id>,<lat>,<lon>,<name>,<address>`, the point in GCJ-02, which is
 * shifted back to WGS 84.
 */
object AmapShare {
    private val URL = Regex("""https?://[^\s，。、）)】]+""")

    /** The links in a shared text, in order. */
    fun links(text: String): List<String> = URL.findAll(text).map { it.value }.toList()

    /** The place's name in a shared text: in 【】 or 「」, else the text before its link, its app's name left off. */
    fun nameIn(text: String): String? {
        Regex("""[【「]([^】」]+)[】」]""").findAll(text).map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotEmpty() && it != "高德地图" }?.let { return it }
        val before = URL.replace(text, "\n").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return before.removePrefix("高德地图").trim(' ', ':', '：', '-', '—', '|').ifEmpty { null }
    }

    /**
     * The point an Amap address points at, from the parameters its pages and links put it
     * in: `p` and `q` (`id,lat,lon,name,…` or `lat,lon,name`), `position` and `lnglat`
     * (`lon,lat`), or `lat` and `lon`. Which number is which is told by China's ranges,
     * where longitudes are all larger than latitudes.
     */
    fun fromUrl(url: String): SharedPlace? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val name = listOf("name", "poiname").firstNotNullOfOrNull { parsed.queryParameter(it)?.takeIf(String::isNotBlank) }
        for (key in listOf("p", "q", "position", "lnglat", "location", "dest", "to")) {
            val value = parsed.queryParameter(key) ?: continue
            val parts = value.split(',', '|').map { it.trim() }
            val numbers = parts.withIndex().mapNotNull { (index, part) -> part.toDoubleOrNull()?.let { index to it } }
            val pair = numbers.zipWithNext().firstOrNull { (a, b) -> b.first == a.first + 1 && isCoordinatePair(a.second, b.second) } ?: continue
            val fix = orderedFix(pair.first.second, pair.second.second)
            // A name after the numbers, as in `p=id,lat,lon,name,address`.
            val after = parts.drop(pair.second.first + 1).firstOrNull { it.isNotBlank() && it.toDoubleOrNull() == null }
            return SharedPlace(name ?: after, gcjToWgs(fix))
        }
        val lat = parsed.firstNumber("lat", "latitude", "dlat")
        val lon = parsed.firstNumber("lon", "lng", "longitude", "dlon")
        if (lat != null && lon != null && isCoordinatePair(lat, lon)) return SharedPlace(name, gcjToWgs(orderedFix(lat, lon)))
        return null
    }

    /**
     * The place in a shared text: its links, each followed to where it ends, read for a
     * point; the name in the text where the link has none. Null when none of them has one.
     */
    suspend fun resolve(text: String, client: OkHttpClient = Http.client): SharedPlace? {
        val name = nameIn(text)
        for (link in links(text)) {
            val place = fromUrl(link) ?: withContext(Dispatchers.IO) { runCatching { follow(client, link) }.getOrNull() }
            if (place != null) return place.copy(name = name ?: place.name)
        }
        return null
    }

    /**
     * The point a link leads to: each of its redirects read for one in turn, as the pages
     * further on may have dropped it, then the page it ends at.
     */
    private fun follow(client: OkHttpClient, link: String): SharedPlace? {
        val walker = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var url = link.toHttpUrlOrNull() ?: return null
        repeat(MAX_REDIRECTS) {
            val request = Request.Builder().url(url).header("User-Agent", BROWSER).build()
            walker.newCall(request).execute().use { response ->
                val next = response.header("Location")?.let(url::resolve)
                if (!response.isRedirect || next == null) return response.body?.string()?.take(PAGE_LIMIT)?.let(::fromPage)
                fromUrl(next.toString())?.let { return it }
                url = next
            }
        }
        return null
    }

    /** A point in a page's own links, as a page that redirects in script rather than by its status gives it. */
    private fun fromPage(html: String): SharedPlace? =
        URL.findAll(html.replace("\\/", "/").replace("&amp;", "&")).map { it.value }.firstNotNullOfOrNull(::fromUrl)

    private fun HttpUrl.firstNumber(vararg keys: String): Double? = keys.firstNotNullOfOrNull { queryParameter(it)?.toDoubleOrNull() }

    /** Two numbers that can only be a latitude and a longitude in China, in either order. */
    private fun isCoordinatePair(a: Double, b: Double): Boolean =
        (a in LATITUDES && b in LONGITUDES) || (a in LONGITUDES && b in LATITUDES)

    private fun orderedFix(a: Double, b: Double): Fix = if (a in LATITUDES) Fix(a, b) else Fix(b, a)

    private val LATITUDES = 3.0..54.0
    private val LONGITUDES = 73.0..136.0
    private const val PAGE_LIMIT = 200_000
    private const val MAX_REDIRECTS = 5
    /** Amap's short links answer an app's request with a page that asks for the app; a browser is sent on to the map. */
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
}
