package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.distance
import cc.hyoban.moments.data.local.gcjToWgs
import cc.hyoban.moments.data.local.wgsToGcj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GCJ-02, as AMap and Tencent give their points, and back to WGS 84. */
class ChinaCoordinatesTest {
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

    @Test
    fun `GCJ-02 shifts the mainland, Hong Kong and Macau, and the shore just off them, but not Taiwan or China's neighbours`() {
        val shifted = mapOf(
            "Hefei" to Fix(31.7158, 117.2954),
            "Urumqi" to Fix(43.8, 87.6),
            "Harbin" to Fix(45.75, 126.65),
            "Sanya" to Fix(18.25, 109.5),
            "Hong Kong" to Fix(22.2855, 114.1577),
            "Macau" to Fix(22.1987, 113.5439),
            "off Xiamen's shore" to Fix(24.435, 118.06),
        )
        for ((name, fix) in shifted) assertTrue(distance(fix, wgsToGcj(fix)) > 100, name)
        val kept = mapOf(
            "Taipei" to Fix(25.033, 121.5654),
            "Seoul" to Fix(37.5665, 126.978),
            "Osaka" to Fix(34.6937, 135.5023),
            "Hanoi" to Fix(21.0278, 105.8342),
            "Bangkok" to Fix(13.7563, 100.5018),
            "Singapore" to Fix(1.3521, 103.8198),
            "Ulaanbaatar" to Fix(47.8864, 106.9057),
            "Pyongyang" to Fix(39.0392, 125.7625),
        )
        for ((name, fix) in kept) {
            assertEquals(fix, wgsToGcj(fix), name)
            assertEquals(fix, gcjToWgs(fix), name)
        }
    }
}
