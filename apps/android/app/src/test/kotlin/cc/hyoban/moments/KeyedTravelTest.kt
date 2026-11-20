package cc.hyoban.moments

import cc.hyoban.moments.data.local.CallsUsedUp
import cc.hyoban.moments.data.local.parseAmapTravel
import cc.hyoban.moments.data.local.parseTencentTravel
import cc.hyoban.moments.data.trips.Routers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** AMap's and Tencent's routing answers, what the plan's ways fall back on, read as a way's time and length. */
class KeyedTravelTest {
    @Test
    fun readsAmapsWays() {
        // Driving and walking (v3) say it as text, in seconds and metres.
        assertEquals(Routers.Travel(1_860, 24_300), parseAmapTravel("""{"status":"1","info":"OK","route":{"paths":[{"distance":"24300","duration":"1860"}]}}"""))
        // By bike (v4) under data, with errcode.
        assertEquals(Routers.Travel(900, 3_200), parseAmapTravel("""{"errcode":0,"errmsg":"OK","data":{"paths":[{"distance":3200,"duration":900}]}}"""))
        assertNull(parseAmapTravel("""{"status":"1","info":"OK","route":{"paths":[]}}"""))
        // Calls used up, said so, for the day to pass it by.
        assertFailsWith<CallsUsedUp> { parseAmapTravel("""{"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10044"}""") }
    }

    @Test
    fun readsTencentsWays() {
        // Its duration is in minutes.
        assertEquals(Routers.Travel(1_860, 24_300), parseTencentTravel("""{"status":0,"message":"query ok","result":{"routes":[{"distance":24300,"duration":31}]}}"""))
        assertNull(parseTencentTravel("""{"status":0,"message":"query ok","result":{"routes":[]}}"""))
        assertFailsWith<CallsUsedUp> { parseTencentTravel("""{"status":121,"message":"此key每日调用量已达到上限"}""") }
    }
}
