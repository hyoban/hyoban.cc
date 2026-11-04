package cc.hyoban.moments

import cc.hyoban.moments.data.local.Fix
import cc.hyoban.moments.data.local.Found
import cc.hyoban.moments.data.local.PlaceSearch
import cc.hyoban.moments.data.local.PlaceSource
import cc.hyoban.moments.ui.compose.bySource
import cc.hyoban.moments.ui.compose.toPlace
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import cc.hyoban.moments.data.local.amapKind
import cc.hyoban.moments.data.local.amapTypecode
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
        assertEquals(PlaceKind.STATION, kind("railway", "station"))
        assertEquals(PlaceKind.CAFE, kind("amenity", "cafe"))
        assertEquals(PlaceKind.PARK, kind("leisure", "park"))
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
        assertEquals(PlaceKind.WORK, kindOf(found[1]))
        assertEquals(found, landmarks(found), "every one of them a landmark")
        assertFailsWith<cc.hyoban.moments.data.local.CallsUsedUp>("calls used up are an error, so Photon answers") {
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
              {"id":"B0TEST0001","name":"某某面馆(某商场店)","address":"某路 1 号 B1 层","typecode":"050100","location":"${shifted.longitude},${shifted.latitude}"},
              {"name":"某某大厦","address":[],"typecode":"120201","location":"121.4,31.2"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("某某面馆(某商场店)", "某某大厦"), around.map { it.name })
        assertTrue(kotlin.math.abs(around[0].fix.latitude - here.latitude) < 1e-6 && kotlin.math.abs(around[0].fix.longitude - here.longitude) < 1e-6, "shifted back to WGS 84")
        assertEquals("某路 1 号 B1 层", around[0].address)
        assertNull(around[1].address, "an empty field comes as []")
        assertEquals(PlaceSource("amap", "B0TEST0001"), around[0].source)
        assertNull(around[1].source)
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
        assertFailsWith<cc.hyoban.moments.data.local.CallsUsedUp>("calls used up are an error, so Tencent or Photon answers") {
            parseAmap("""{"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10003"}""")
        }
    }

    @Test
    fun `what is around comes from reverse geocoding, nearest first, kinds read from AMap's words`() {
        val amap = parseAmap(
            """
            {"status":"1","info":"OK","infocode":"10000","regeocode":{"formatted_address":"某市某区","pois":[
              {"id":"B0TEST0003","name":"某某公园","type":"风景名胜;公园广场;公园","distance":"120.5","address":"某路 3 号","location":"121.4,31.2"},
              {"id":"B0TEST0004","name":"某某咖啡(某路店)","type":"餐饮服务;咖啡厅;咖啡厅","distance":"12.0","address":[],"location":"121.4,31.2"},
              {"id":"B0TEST0005","name":"某某寺","type":"风景名胜;风景名胜;寺庙道观","distance":"300","location":"121.4,31.2"},
              {"id":"B0TEST0006","name":"某某委员会","type":"政府机构及社会团体;政府机关;政府机关","distance":"30","location":"121.4,31.2"}
            ]}}
            """.trimIndent(),
        )
        assertEquals(listOf("某某咖啡(某路店)", "某某委员会", "某某公园", "某某寺"), amap.map { it.name })
        assertEquals(listOf(PlaceKind.CAFE, null, PlaceKind.PARK, PlaceKind.TEMPLE), amap.map { it.placeKind })
        assertEquals(PlaceSource("amap", "B0TEST0004"), amap[0].source)
        assertEquals("050500", amapTypecode("餐饮服务;咖啡厅;星巴克咖啡"))
        assertEquals("080304", amapTypecode("体育休闲服务;娱乐场所;酒吧"))
        assertEquals("050000", amapTypecode("餐饮服务;某种餐厅;某种餐厅"))
        assertNull(amapTypecode("地名地址信息;热点地名;热点地名"))
        val tencent = parseTencent(
            """
            {"status":0,"message":"Success","result":{"address":"某市某区","poi_count":1,"pois":[
              {"id":"1000000000000000001","title":"某某茶饮","address":"某街 4 号","category":"美食:冷饮店","location":{"lat":31.2,"lng":121.4},"_distance":8.5}
            ]}}
            """.trimIndent(),
        )
        assertEquals(listOf("某某茶饮"), tencent.map { it.name })
        assertEquals(PlaceKind.TEA, tencent[0].placeKind)
        assertEquals(PlaceSource("tencent", "1000000000000000001"), tencent[0].source)
    }

    @Test
    fun `the places around come from the place searches first, the reverse geocoders once their calls are used up`() {
        val asked = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            asked += url.encodedPath
            val body = when (url.encodedPath) {
                "/v5/place/around" -> """{"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10003"}"""
                "/ws/place/v1/explore" -> """{"status":121,"message":"此key每日调用量已达到上限"}"""
                "/v3/geocode/regeo" -> """{"status":"1","regeocode":{"pois":[]}}"""
                else -> """{"status":0,"result":{"pois":[{"title":"某某店","location":{"lat":31.2,"lng":121.4}}]}}"""
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        fun search(tencentFirst: Boolean) = PlaceSearch({ "https://photon.invalid" }, { "t" }, { "a" }, { tencentFirst }, client)
        val places = search(tencentFirst = false)
        val first = kotlinx.coroutines.runBlocking { places.china(Fix(31.2, 121.4)) }
        assertEquals(listOf("某某店"), first?.map { it.name })
        assertEquals(listOf("/v5/place/around", "/ws/place/v1/explore", "/v3/geocode/regeo", "/ws/geocoder/v1/"), asked)
        asked.clear()
        kotlinx.coroutines.runBlocking { places.china(Fix(31.3, 121.5)) }
        assertEquals(listOf("/v3/geocode/regeo", "/ws/geocoder/v1/"), asked, "the used-up place searches aren't asked again today")
        asked.clear()
        kotlinx.coroutines.runBlocking { search(tencentFirst = true).china(Fix(31.2, 121.4)) }
        assertEquals(listOf("/ws/place/v1/explore", "/v5/place/around", "/ws/geocoder/v1/"), asked, "Tencent chosen, each pair turns round")
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
    fun `a place picked from a map service keeps its id, and is found by it again`() {
        val picked = cc.hyoban.moments.ui.compose.PlaceChoice.New("某某咖啡", Fix(31.2, 121.4), PlaceKind.CAFE, PlaceSource("amap", "B0TEST0002"))
        val place = picked.toPlace("2026-11-04T12:00:00+08:00")
        val text = cc.hyoban.moments.data.spec.Spec.json.encodeToString(cc.hyoban.moments.data.spec.Place.serializer(), place)
        val source = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["source"]!!.jsonObject
        assertEquals("amap", source["app"]!!.jsonPrimitive.content)
        assertEquals("B0TEST0002", source["venue"]!!.jsonPrimitive.content)
        val read = cc.hyoban.moments.data.spec.Spec.json.decodeFromString(cc.hyoban.moments.data.spec.Place.serializer(), text)
        assertEquals("cafe", read.kind)
        assertEquals(mapOf(PlaceSource("amap", "B0TEST0002") to "p1"), bySource(mapOf("p1" to read, "p2" to read.copy(extra = kotlinx.serialization.json.JsonObject(emptyMap())))))
    }

    @Test
    fun `the app's kinds are the spec's, in its order`() {
        val schema = java.io.File("../../../data/schema/places.schema.json").readText()
        val kinds = Regex("\"kind\"\\s*:\\s*\\{[^}]*?\"enum\"\\s*:\\s*\\[([^\\]]*)\\]").find(schema)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }
        assertEquals(kinds, PlaceKind.entries.map { it.value })
    }

    @Test
    fun `AMap's type codes say what kind of place it is`() {
        assertEquals(PlaceKind.SHOPPING, amapKind("060101"))
        assertEquals(PlaceKind.STAY, amapKind("100100"))
        assertEquals(PlaceKind.PARK, amapKind("110101|110102"))
        assertEquals(PlaceKind.CAFE, amapKind("050500"))
        assertEquals(PlaceKind.FOOD, amapKind("050100"))
        assertEquals(PlaceKind.METRO, amapKind("150500"))
        assertEquals(PlaceKind.STATION, amapKind("150200"))
        assertEquals(PlaceKind.CHARGING, amapKind("011100"))
        assertEquals(PlaceKind.MUSEUM, amapKind("140100"))
        assertEquals(PlaceKind.WORK, amapKind("120201"))
        assertNull(amapKind("190000"))
        assertNull(amapKind(null))
    }

    @Test
    fun `Tencent's categories say what kind of place it is`() {
        assertEquals(PlaceKind.SHOPPING, tencentKind("购物:综合商场:购物中心"))
        assertEquals(PlaceKind.STAY, tencentKind("酒店宾馆:星级酒店"))
        assertEquals(PlaceKind.SCENIC, tencentKind("旅游景点:风景名胜"))
        assertEquals(PlaceKind.METRO, tencentKind("基础设施:交通设施:地铁站"))
        assertEquals(PlaceKind.CHARGING, tencentKind("汽车:充电站"))
        assertEquals(PlaceKind.CAFE, tencentKind("娱乐休闲:咖啡厅"))
        assertEquals(PlaceKind.CAFE, tencentKind("美食:咖啡厅"))
        assertEquals(PlaceKind.PARK, tencentKind("旅游景点:公园"))
        assertEquals(PlaceKind.SPORT, tencentKind("运动健身:健身中心"))
        assertEquals(PlaceKind.LEISURE, tencentKind("娱乐休闲:KTV"))
        assertNull(tencentKind("地名地址:行政地名"))
        assertNull(tencentKind(null))
    }
}
