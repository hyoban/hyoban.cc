package cc.hyoban.moments.data.media

import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import cc.hyoban.moments.data.spec.PublishedMedia

/**
 * Where a published photo or video is served, as the site picks it (src/asset-url.ts and
 * src/moments/index.ts): a photo's smallest variant at least as wide as it is shown, or
 * for an original the API kept, that width from Cloudflare Images; a video's poster, or
 * its first frame from Media Transformations. A photo not yet published is read from
 * where it is on the phone.
 */
class MediaUrls(private val origin: () -> String) {
    constructor(origin: String) : this({ origin })

    /** The URL of an uploaded object. */
    fun asset(key: String): String = "${origin()}/${encodeKey(key)}"

    /** The still to show for a media item at [width] pixels: a photo, a live photo's still, a video's poster. */
    fun still(media: MomentMedia, width: Int): String? {
        val published = media.published ?: return media.local?.uri

        return when (media.type) {
            MediaType.IMAGE, MediaType.LIVE_PHOTO -> image(published, width)
            MediaType.VIDEO -> published.poster?.let { image(it, width) }
                ?: frame(published, media.width ?: return null, width)
        }
    }

    /** The motion to play: a video, or a live photo's video. */
    fun motion(media: MomentMedia): String? {
        val published = media.published ?: return media.local?.videoUri

        return when (media.type) {
            MediaType.IMAGE -> null
            MediaType.LIVE_PHOTO -> published.video?.let { asset(it.key) }
            MediaType.VIDEO -> asset(published.key)
        }
    }

    /** A published image at the smallest of its sizes at least [width] wide, else its largest. */
    fun image(image: PublishedMedia, width: Int): String {
        if (image.original == true) {
            return transformed("image/width=${sizeFor(image.width ?: RESIZED_WIDTHS.last(), width)},format=auto,onerror=redirect", image.key)
        }

        val sources = (image.variants.orEmpty() + listOfNotNull(image.takeIf { (it.width ?: Int.MAX_VALUE) <= 1920 }))
            .sortedBy { it.width ?: 0 }
        val source = sources.firstOrNull { (it.width ?: 0) >= width } ?: sources.lastOrNull() ?: image

        return asset(source.key)
    }

    private fun frame(video: PublishedMedia, videoWidth: Int, width: Int): String? =
        if (video.original == true) {
            transformed("media/mode=frame,time=0s,width=${sizeFor(videoWidth, width)},format=jpg", video.key)
        } else {
            null
        }

    private fun transformed(options: String, key: String) =
        "${origin()}/cdn-cgi/$options/${encodeKey(key)}"

    companion object {
        /** The widths an original is resized to, as the site asks for them. */
        val RESIZED_WIDTHS = listOf(480, 960, 1920)

        /** The smallest of the site's widths at least [wanted], never wider than the original. */
        fun sizeFor(ownWidth: Int, wanted: Int): Int {
            val widths = (RESIZED_WIDTHS.filter { it < ownWidth } + minOf(ownWidth, 1920)).distinct()
            return widths.firstOrNull { it >= wanted } ?: widths.last()
        }

        /** An object's key in a URL, each of its segments encoded. */
        private fun encodeKey(key: String): String = key.split('/').joinToString("/", transform = ::encodeSegment)

        /** As JavaScript's encodeURIComponent, which the site's URLs are built with. */
        fun encodeSegment(segment: String): String = buildString {
            for (byte in segment.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xff).toChar()
                if (char.isLetterOrDigit() && char.code < 128 || char in "-_.!~*'()") {
                    append(char)
                } else {
                    append('%').append("%02X".format(byte.toInt() and 0xff))
                }
            }
        }
    }
}
