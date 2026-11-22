package cc.hyoban.moments

import cc.hyoban.moments.xiaohongshu.NoteCard
import cc.hyoban.moments.xiaohongshu.Xiaohongshu
import cc.hyoban.moments.xiaohongshu.XiaohongshuPage
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Here reads back off Xiaohongshu's pages, as `evaluateJavascript` hands it over: a string quoted as JSON. */
class XiaohongshuPageTest {
    private fun handed(json: String) = JsonPrimitive(json).toString()

    @Test
    fun readsASearchPage() {
        val page = XiaohongshuPage.searchOf(
            handed("""{"cards":[{"id":"0123456789abcdef01234567","token":"AB=","title":"某店 拉面","author":"某人","likes":"1.2万"},{"id":"x","token":"","title":""}],"signIn":false,"refused":false}"""),
        )!!
        assertEquals(listOf(NoteCard("0123456789abcdef01234567", "AB=", "某店 拉面", "某人", "1.2万")), page.cards)
        assertEquals(false, page.signIn)
    }

    @Test
    fun readsASignInWall() {
        val page = XiaohongshuPage.searchOf(handed("""{"cards":[],"signIn":true,"refused":false}"""))!!
        assertEquals(true, page.signIn)
        assertNull(XiaohongshuPage.searchOf("null"))
    }

    @Test
    fun readsANotePage() {
        val drawn = XiaohongshuPage.noteOf(handed("""{"title":"好吃","text":"排了半小时","time":1767225600000,"ip":"东京","likes":"30","author":"某人"}"""), ZoneId.of("Asia/Tokyo"))!!
        assertEquals("2026-01-01", drawn.posted)
        assertEquals("东京", drawn.ip)
        // Only its markup: the date as the page writes it.
        val marked = XiaohongshuPage.noteOf(handed("""{"title":"","text":"不错","dated":"10-01 上海"}"""))!!
        assertEquals("10-01 上海", marked.posted)
        assertNull(marked.title)
    }

    @Test
    fun aCardWithoutATitleTakesItsFirstLine() {
        val card = NoteCard("0123456789abcdef01234567", "AB=", "", null, "3")
        val found = XiaohongshuPage.noteFound(card, XiaohongshuPage.noteOf(handed("""{"text":"第一行\n第二行"}""")))
        assertEquals("第一行", found.title)
        assertEquals("3", found.likes)
        assertEquals("https://www.xiaohongshu.com/explore/0123456789abcdef01234567?xsec_token=AB%3D&xsec_source=pc_search", found.link)
    }

    @Test
    fun asksAsDesktopChromeOfTheSameVersion() {
        val agent = Xiaohongshu.desktopAgent("Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/142.0.7444.0 Mobile Safari/537.36")
        assertEquals(true, agent.contains("Macintosh") && agent.contains("Chrome/142.0.7444.0") && !agent.contains("Mobile"))
    }

    @Test
    fun followsTheSitesHttpRedirectsOverHttps() {
        assertEquals("https://www.xiaohongshu.com/search_result/?keyword=a", XiaohongshuPage.secured("http://www.xiaohongshu.com/search_result/?keyword=a"))
        assertNull(XiaohongshuPage.secured("https://www.xiaohongshu.com/explore"))
        assertNull(XiaohongshuPage.secured("http://example.com/xiaohongshu.com"))
    }

    @Test
    fun spellsTheAddresses() {
        assertEquals("https://www.xiaohongshu.com/search_result/?keyword=%E4%B8%9C%E4%BA%AC%20%E6%8B%89%E9%9D%A2&source=web_explore_feed", XiaohongshuPage.searchUrl("东京 拉面"))
        assertEquals(
            "https://www.xiaohongshu.com/explore/0123456789abcdef01234567?xsec_token=AB%3D&xsec_source=pc_search",
            XiaohongshuPage.noteUrl(NoteCard("0123456789abcdef01234567", "AB=", "", null, null)),
        )
    }
}
