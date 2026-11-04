package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.Found
import cc.hyoban.moments.data.local.PlaceSearch
import okhttp3.ResponseBody.Companion.toResponseBody
import cc.hyoban.moments.data.local.amapKind
import cc.hyoban.moments.data.local.kindOf
import cc.hyoban.moments.data.local.landmarks
import cc.hyoban.moments.data.local.nameAt
import cc.hyoban.moments.data.local.parseAmap
import cc.hyoban.moments.data.local.parsePhoton
import cc.hyoban.moments.data.local.parseTencent
import cc.hyoban.moments.data.local.tencentKind
import cc.hyoban.moments.data.local.wgsToGcj
import cc.hyoban.moments.data.spec.Division
import cc.hyoban.moments.data.spec.PlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `Tencent's places are read in WGS 84 with their address and kind, the nameless left out`() {
        val here = Fix(31.2001, 121.4001)
        val shifted = wgsToGcj(here)
        val found = parseTencent(
            """
            {"status":0,"data":[
              {"title":"某某面馆(某商场店)","address":"某路 1 号 B1 层","category":"美食:中餐厅:面馆","location":{"lat":${shifted.latitude},"lng":${shifted.longitude}}},
              {"title":"某某大厦","category":"房产小区:商务楼宇","location":{"lat":31.2,"lng":121.4}},
              {"title":"","location":{"lat":31.2,"lng":121.4}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("某某面馆(某商场店)", "某某大厦"), found.map { it.name })
        assertTrue(kotlin.math.abs(found[0].fix.latitude - here.latitude) < 1e-6 && kotlin.math.abs(found[0].fix.longitude - here.longitude) < 1e-6, "shifted back to WGS 84")
        assertEquals("某路 1 号 B1 层", found[0].address)
        assertEquals(PlaceKind.FOOD, kindOf(found[0]))
        assertNull(kindOf(found[1]))
        assertEquals(found, landmarks(found), "every one of them a landmark")
        assertFailsWith<java.io.IOException>("calls used up are an error, so Photon answers") {
            parseTencent("""{"status":121,"message":"此key每日调用量已达到上限"}""")
        }
    }

    @Test
    fun `AMap's places and tips are read in WGS 84 with their address and kind, the pointless left out`() {
        val here = Fix(31.2001, 121.4001)
        val shifted = wgsToGcj(here)
        val around = parseAmap(
            """
            {"status":"1","info":"OK","infocode":"10000","pois":[
              {"name":"某某面馆(某商场店)","address":"某路 1 号 B1 层","typecode":"050100","location":"${shifted.longitude},${shifted.latitude}"},
              {"name":"某某大厦","address":[],"typecode":"120201","location":"121.4,31.2"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("某某面馆(某商场店)", "某某大厦"), around.map { it.name })
        assertTrue(kotlin.math.abs(around[0].fix.latitude - here.latitude) < 1e-6 && kotlin.math.abs(around[0].fix.longitude - here.longitude) < 1e-6, "shifted back to WGS 84")
        assertEquals("某路 1 号 B1 层", around[0].address)
        assertNull(around[1].address, "an empty field comes as []")
        assertEquals(PlaceKind.FOOD, kindOf(around[0]))
        val tips = parseAmap(
            """
            {"status":"1","info":"OK","infocode":"10000","tips":[
              {"name":"某某咖啡","district":"某区","address":"某街 2 号","typecode":"050500","location":"121.4,31.2"},
              {"name":"某路公交","address":[],"typecode":[],"location":[]},
              {"name":"咖啡","location":""}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("某某咖啡"), tips.map { it.name })
        assertFailsWith<java.io.IOException>("calls used up are an error, so Tencent or Photon answers") {
            parseAmap("""{"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10003"}""")
        }
    }

    @Test
    fun `with both keys the chosen service is asked first, the other when it has nothing`() {
        val asked = mutableListOf<String>()
        fun client(emptyHost: String) = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val host = chain.request().url.host
            asked += host
            val body = when {
                host == emptyHost && host.contains("amap") -> """{"status":"1","tips":[]}"""
                host == emptyHost -> """{"status":0,"data":[]}"""
                host.contains("amap") -> """{"status":"1","tips":[{"name":"高德的","location":"121.4,31.2"}]}"""
                else -> """{"status":0,"data":[{"title":"腾讯的","location":{"lat":31.2,"lng":121.4}}]}"""
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        fun search(tencentFirst: Boolean, emptyHost: String = "") = kotlinx.coroutines.runBlocking {
            asked.clear()
            PlaceSearch({ "https://photon.invalid" }, { "t" }, { "a" }, { tencentFirst }, client(emptyHost)).search("某店", Fix(31.2, 121.4)).map { it.name }
        }
        assertEquals(listOf("高德的"), search(tencentFirst = false))
        assertEquals(listOf("restapi.amap.com"), asked)
        assertEquals(listOf("腾讯的"), search(tencentFirst = true))
        assertEquals(listOf("apis.map.qq.com"), asked)
        assertEquals(listOf("高德的"), search(tencentFirst = true, emptyHost = "apis.map.qq.com"))
        assertEquals(listOf("apis.map.qq.com", "restapi.amap.com"), asked)
    }

    @Test
    fun `AMap's type codes say what kind of place it is`() {
        assertEquals(PlaceKind.SHOPPING, amapKind("060101"))
        assertEquals(PlaceKind.STAY, amapKind("100100"))
        assertEquals(PlaceKind.SCENIC, amapKind("110101|110102"))
        assertEquals(PlaceKind.TRANSPORT, amapKind("150500"))
        assertEquals(PlaceKind.TRANSPORT, amapKind("011100"))
        assertEquals(PlaceKind.SCENIC, amapKind("140100"))
        assertNull(amapKind("120201"))
        assertNull(amapKind(null))
    }

    @Test
    fun `Tencent's categories say what kind of place it is`() {
        assertEquals(PlaceKind.SHOPPING, tencentKind("购物:综合商场:购物中心"))
        assertEquals(PlaceKind.STAY, tencentKind("酒店宾馆:星级酒店"))
        assertEquals(PlaceKind.SCENIC, tencentKind("旅游景点:公园"))
        assertEquals(PlaceKind.TRANSPORT, tencentKind("基础设施:交通设施:地铁站"))
        assertEquals(PlaceKind.TRANSPORT, tencentKind("汽车:充电站"))
        assertEquals(PlaceKind.FOOD, tencentKind("娱乐休闲:咖啡厅"))
        assertNull(tencentKind("娱乐休闲:KTV"))
        assertNull(tencentKind(null))
    }
}
