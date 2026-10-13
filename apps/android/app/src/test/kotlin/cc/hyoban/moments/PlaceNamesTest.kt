package cc.hyoban.moments

import cc.hyoban.moments.platform.PlaceNames
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceNamesTest {
    private fun answer(vararg labels: Triple<String?, Int, Int>) = labels.joinToString(",", """{"type":"FeatureCollection","features":[""", "]}") { (name, size, distance) ->
        val named = name?.let { """"name":"$it","name_zh-Hans":"$it",""" } ?: ""
        """{"type":"Feature","properties":{$named"sizerank":$size,"tilequery":{"distance":$distance,"layer":"poi_label"}}}"""
    }

    @Test
    fun `a campus wins over the building beside you`() {
        val body = answer(Triple("教学楼", 13, 31), Triple("无为一中", 4, 81), Triple("实验楼", 14, 102), Triple(null, 10, 318), Triple("清华园", 10, 319))
        assertEquals(listOf("无为一中", "教学楼", "实验楼", "清华园"), PlaceNames.rank(body))
    }

    @Test
    fun `the compound you're in wins over a bigger one down the road`() {
        val body = answer(Triple("和平新村", 14, 23), Triple("南京外国语学校", 10, 234), Triple("C楼", 15, 141))
        assertEquals("和平新村", PlaceNames.rank(body).first())
    }

    @Test
    fun `among shops the mall comes first, each name once`() {
        val body = answer(Triple("apm", 16, 24), Triple("中央商场", 16, 63), Triple("中央商场", 12, 92), Triple("万达广场", 13, 141))
        assertEquals(listOf("中央商场", "apm", "万达广场"), PlaceNames.rank(body))
    }

    @Test
    fun `nothing around is no names`() {
        assertEquals(emptyList(), PlaceNames.rank("""{"type":"FeatureCollection","features":[]}"""))
    }
}
