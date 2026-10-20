package cc.hyoban.moments

import cc.hyoban.moments.data.local.AmapShare
import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.data.local.gcjToWgs
import cc.hyoban.moments.data.local.wgsToGcj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A place shared from Amap: its name and link read, its point shifted back from GCJ-02. */
class SharedPlaceTest {
    // As Amap's share sheet sent it, and where its short link redirected.
    private val text = "无为市绣溪公园 https://surl.amap.com/1J5nYjvct2iI"
    private val redirect = "https://wb.amap.com/?commonBizInfo=%7B%22share_from%22%3A%22poi_poi%22%7D" +
        "&p=B0FFFWO5RA%2C31.295353497011277%2C117.91159540414807%2C%E6%97%A0%E4%B8%BA%E5%B8%82%E7%BB%A3%E6%BA%AA%E5%85%AC%E5%9B%AD" +
        "%2C%E6%97%A0%E4%B8%BA%E5%B8%82%E7%8E%AF%E5%9F%8E%E8%A5%BF%E8%B7%AF%E5%8C%97140%E7%B1%B3&src=app_C4820"

    @Test
    fun `the shared text gives the place's name and its link`() {
        assertEquals("无为市绣溪公园", AmapShare.nameIn(text))
        assertEquals(listOf("https://surl.amap.com/1J5nYjvct2iI"), AmapShare.links(text))
        assertEquals("无为市绣溪公园", AmapShare.nameIn("无为市绣溪公园https://surl.amap.com/1J5nYjvct2iI"), "with no space before the link")
        assertEquals("外滩", AmapShare.nameIn("【高德地图】【外滩】上海市黄浦区中山东一路 https://surl.amap.com/x"))
    }

    @Test
    fun `the link's page gives the point, shifted back to where the map has it`() {
        val place = AmapShare.fromUrl(redirect)!!
        assertEquals("无为市绣溪公园", place.name)
        // OpenStreetMap has the park at 31.2971, 117.9055; Amap's point is in it, a few hundred metres from GCJ-02's.
        assertTrue(distance(place.fix, Fix(31.2971178, 117.9055109)) < 250, "${place.fix}")
        assertTrue(distance(place.fix, Fix(31.295353497011277, 117.91159540414807)) > 400)
    }

    @Test
    fun `other ways Amap's links carry a point`() {
        val marker = AmapShare.fromUrl("https://uri.amap.com/marker?position=121.4905,31.2402&name=外滩")!!
        assertEquals("外滩", marker.name)
        assertTrue(distance(marker.fix, gcjToWgs(Fix(31.2402, 121.4905))) < 1)
        assertTrue(AmapShare.fromUrl("https://m.amap.com/?q=31.2402,121.4905,外滩")!!.fix.latitude in 31.0..31.5)
        assertNull(AmapShare.fromUrl("https://surl.amap.com/1J5nYjvct2iI"))
    }

    @Test
    fun `GCJ-02 is undone to well within a metre, and leaves points outside China alone`() {
        for (wgs in listOf(Fix(31.2971, 117.9055), Fix(39.9042, 116.4074), Fix(22.3193, 114.1694), Fix(43.8, 87.6))) {
            val gcj = wgsToGcj(wgs)
            assertTrue(distance(wgs, gcj) in 100.0..1000.0, "GCJ-02 moves $wgs by ${distance(wgs, gcj)} m")
            assertTrue(distance(wgs, gcjToWgs(gcj)) < 0.5)
        }
        val tokyo = Fix(35.68, 139.76)
        assertEquals(tokyo, gcjToWgs(tokyo))
    }
}
