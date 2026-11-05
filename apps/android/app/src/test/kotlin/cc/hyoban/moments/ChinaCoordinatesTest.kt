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
}
