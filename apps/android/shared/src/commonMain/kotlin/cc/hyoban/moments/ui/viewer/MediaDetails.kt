package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/**
 * What a Moment's photos say of themselves for the info panel, read once and kept while the app
 * runs, so turning to one in a viewer shows it whole at once; [ahead] reads a neighbour's before it
 * is turned to. A photo linked to the NAS is read from the NAS's original, where it was taken too;
 * a published original from its start, which keeps its EXIF but never that.
 */
class MediaDetails(private val container: AppContainer) {
    private val kept = object : LinkedHashMap<String, PhotoDetails>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhotoDetails>) = size > KEPT
    }

    /** One photo as it is now: a change to its file, or its link, is another. */
    private fun key(media: MomentMedia) = "${media.uid}:${media.published?.etag ?: media.local?.uri}:${media.nas?.id}"

    /** [media]'s details if they were read already, without waiting. */
    fun cached(media: MomentMedia): PhotoDetails? = synchronized(kept) { kept[key(media)] }

    /** What [media]'s record says of it, shown while its EXIF is read; [occurredAt] is its Moment's time, for one that doesn't say. */
    fun base(media: MomentMedia, occurredAt: String? = null): PhotoDetails {
        val published = media.published
        val at = media.takenAt ?: occurredAt
        return PhotoDetails(
            takenAt = at?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
            offset = media.takenAt?.takeIf { it.length > 19 }?.let { Regex("([+-]\\d{2}:\\d{2}|Z)$").find(it)?.value?.let { o -> if (o == "Z") "+00:00" else o } },
            name = published?.key?.substringAfterLast('/') ?: media.local?.uri?.substringAfterLast('/'),
            format = PhotoDetails.format(published?.contentType, published?.key),
            width = media.width, height = media.height, bytes = published?.bytes,
        )
    }

    /**
     * [media]'s details whole, read once and kept. [local] reads a photo not published yet from its
     * file on the device, where the device can.
     */
    suspend fun read(
        media: MomentMedia,
        occurredAt: String? = null,
        local: (suspend (PhotoDetails, String) -> PhotoDetails)? = null,
    ): PhotoDetails {
        cached(media)?.let { return it }
        val base = base(media, occurredAt)
        if (media.type == MediaType.VIDEO) return base
        val published = media.published
        val nas = container.nas
        val link = media.nas?.takeIf { nas.configured }
        val head = link?.let { nas.head(nas.photo(it)) }
        val whole = when {
            head != null -> PhotoDetails.read(base.copy(name = link.name)) { head.inputStream() }
            published == null -> media.local?.uri?.let { uri -> local?.invoke(base, uri) } ?: return base
            published.original == true -> PhotoDetails.readRemote(base, container.mediaUrls.asset(published.key))
            else -> base
        }
        synchronized(kept) { kept[key(media)] = whole }
        return whole
    }

    /** Reads [media]'s details ahead in [scope], those not read yet and that have to be fetched. */
    fun ahead(scope: CoroutineScope, media: MomentMedia, occurredAt: String? = null) {
        if (media.type == MediaType.VIDEO || cached(media) != null) return
        if (media.published == null && media.nas == null) return
        scope.launch { read(media, occurredAt) }
    }

    private companion object {
        const val KEPT = 300
    }
}
