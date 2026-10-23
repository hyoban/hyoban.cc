package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.Found
import cc.hyoban.moments.data.local.kindOf
import cc.hyoban.moments.data.local.landmarks
import cc.hyoban.moments.data.local.nameAt
import cc.hyoban.moments.data.local.parsePhoton
import cc.hyoban.moments.data.spec.Division
import cc.hyoban.moments.data.spec.PlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Photon's answers read as places, and a pin named after what is around it. */
class PlaceSearchTest {
    // Trimmed from Photon's /reverse at 31.2400, 121.4860, on the Bund.
    private val bund = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4858,31.2401]},
           "properties":{"osm_key":"highway","osm_value":"residential","name":"九江路","district":"外滩街道","city":"黄浦区","state":"上海市","country":"中国"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4860368,31.2403257]},
           "properties":{"osm_key":"tourism","osm_value":"attraction","name":"外滩","street":"外滩","district":"外滩街道","city":"黄浦区","state":"上海市","country":"中国"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4861,31.2399]},
           "properties":{"osm_key":"amenity","osm_value":"bank","street":"外滩"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4859,31.2398]},
           "properties":{"osm_key":"building","osm_value":"yes","name":"门房"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4862,31.2397]},
           "properties":{"osm_key":"tourism","osm_value":"attraction","name":"字林大楼","street":"中山东一路"}}
        ]}
    """.trimIndent()

    @Test
    fun `features are read with their place and kind, the nameless left out`() {
        val found = parsePhoton(bund)
        assertEquals(listOf("九江路", "外滩", "门房", "字林大楼"), found.map { it.name })
        val bund = found[1]
        assertEquals(Fix(31.2403257, 121.4860368), bund.fix)
        assertEquals("tourism" to "attraction", bund.kind to bund.type)
    }

    @Test
    fun `a pin is named after the nearest landmark, else its street`() {
        val found = parsePhoton(bund)
        assertEquals(listOf("外滩", "字林大楼"), landmarks(found).map { it.name }, "not the streets, nor a building that is only a building")
        assertEquals("外滩", nameAt(found))
        assertEquals("九江路", nameAt(found.filter { it.kind == "highway" }))
        assertNull(nameAt(emptyList()))
        assertEquals(emptyList(), parsePhoton("""{"type":"FeatureCollection"}"""))
    }

    @Test
    fun `a landmark's kind follows from what OpenStreetMap calls it`() {
        fun kind(key: String, value: String) = kindOf(Found("x", Fix(0.0, 0.0), key, value))
        assertEquals(PlaceKind.SCENIC, kind("tourism", "attraction"))
        assertEquals(PlaceKind.FOOD, kind("amenity", "restaurant"))
        assertEquals(PlaceKind.SHOPPING, kind("shop", "mall"))
        assertEquals(PlaceKind.STAY, kind("tourism", "hotel"))
        assertEquals(PlaceKind.TRANSPORT, kind("railway", "station"))
        assertEquals(PlaceKind.TOWN, kind("place", "village"))
        assertNull(kind("amenity", "bank"))
        assertNull(kind("building", "yes"))
    }

    @Test
    fun `a division is shown by its two most particular names`() {
        assertEquals("芜湖市 镜湖区", Division("340202", "安徽省", "芜湖市", "镜湖区").label())
        assertEquals("安徽省 芜湖市", Division("340200", "安徽省", "芜湖市").label())
        assertEquals("北京市", Division("110000", "北京市", "北京市").label())
        assertEquals("四川省", Division("510000", "四川省").label())
    }
}
