package cc.hyoban.moments

import cc.hyoban.moments.data.media.MediaUrls
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.PublishedMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The app asks for the same files the site does (src/asset-url.ts, src/moments/index.ts). */
class MediaUrlsTest {
    private val urls = MediaUrls("https://image.hyoban.cc")
    private val dir = "moments/2026/10/01-02-ferry-sunset"

    private fun image(name: String, width: Int, variants: List<PublishedMedia>? = null, original: Boolean? = null) =
        PublishedMedia("$dir/$name", "image/webp", 1, "0".repeat(32), width, width * 3 / 4, variants, original)

    private val optimized = image("sunset.webp", 1920, listOf(image("sunset-480w.webp", 480), image("sunset-960w.webp", 960)))

    @Test
    fun `an optimized photo comes in its smallest size at least as wide as shown`() {
        val media = MomentMedia("01M3VDBHC1HW3W1Z58ARMSH236", MediaType.IMAGE, width = 1920, height = 1440, published = optimized)

        assertEquals("https://image.hyoban.cc/$dir/sunset-480w.webp", urls.still(media, 200))
        assertEquals("https://image.hyoban.cc/$dir/sunset-960w.webp", urls.still(media, 700))
        assertEquals("https://image.hyoban.cc/$dir/sunset.webp", urls.still(media, 1500))
        assertEquals("https://image.hyoban.cc/$dir/sunset.webp", urls.still(media, 4000))
    }

    @Test
    fun `an oversized photo without a fitting variant falls back to its largest variant`() {
        val big = image("big.jpg", 4032, listOf(image("big-480w.webp", 480), image("big-960w.webp", 960)))

        assertEquals("https://image.hyoban.cc/$dir/big-960w.webp", urls.image(big, 2000))
    }

    @Test
    fun `an original is resized by Cloudflare to the site's widths`() {
        val kept = image("pier.jpg", 4032, variants = emptyList(), original = true)

        assertEquals("https://image.hyoban.cc/cdn-cgi/image/width=480,format=auto,onerror=redirect/$dir/pier.jpg", urls.image(kept, 300))
        assertEquals("https://image.hyoban.cc/cdn-cgi/image/width=1920,format=auto,onerror=redirect/$dir/pier.jpg", urls.image(kept, 1080))
        assertEquals(800, MediaUrls.sizeFor(800, 1080))
    }

    @Test
    fun `a video shows its poster, or an original's first frame, and plays its file`() {
        val video = PublishedMedia("$dir/clip.mp4", "video/mp4", 1, "0".repeat(32), poster = image("clip-poster.webp", 1280))
        val withPoster = MomentMedia("01M3VDBHC1HW3W1Z58ARMSH237", MediaType.VIDEO, width = 1280, height = 720, published = video)
        val kept = withPoster.copy(published = video.copy(poster = null, original = true))

        assertEquals("https://image.hyoban.cc/$dir/clip-poster.webp", urls.still(withPoster, 600))
        assertEquals("https://image.hyoban.cc/cdn-cgi/media/mode=frame,time=0s,width=960,format=jpg/$dir/clip.mp4", urls.still(kept, 600))
        assertNull(urls.still(kept.copy(width = null), 600), "a kept video without its width has no frame to ask for")
        assertEquals("https://image.hyoban.cc/$dir/clip.mp4", urls.motion(withPoster))
    }

    @Test
    fun `keys are encoded as encodeURIComponent does`() {
        assertEquals("https://image.hyoban.cc/moments/a%20b/%E5%9B%BE(1).webp", urls.asset("moments/a b/图(1).webp"))
    }
}
