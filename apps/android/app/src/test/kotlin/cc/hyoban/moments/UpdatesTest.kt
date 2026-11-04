package cc.hyoban.moments

import cc.hyoban.moments.update.Release
import cc.hyoban.moments.update.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    private fun feed(vararg tags: String) = tags.joinToString("\n", "<feed>\n", "\n</feed>") { tag ->
        "<entry><id>tag:github.com,2008:Repository/1234/$tag</id><title>$tag</title></entry>"
    }

    @Test
    fun versionCodesFollowTheBuild() {
        assertEquals(100, Updates.codeOf("0.1.0"))
        assertEquals(10203, Updates.codeOf("1.2.3"))
        assertNull(Updates.codeOf("1.2"))
        assertNull(Updates.codeOf("1.100.0"))
        assertNull(Updates.codeOf("1.2.x"))
    }

    @Test
    fun newestIsTheHighestAppRelease() {
        val newest = Updates.newestOf(feed("android-v0.2.0", "android-v0.10.0", "web-v9.0.0", "android-v0.9.0", "android-v1.2"))
        assertEquals(Release("0.10.0", 1000, "https://github.com/hyoban/hyoban.cc/releases/download/android-v0.10.0/here-0.10.0.apk"), newest)
    }

    @Test
    fun noReleasesIsNoUpdate() {
        assertNull(Updates.newestOf("<feed></feed>"))
    }
}
