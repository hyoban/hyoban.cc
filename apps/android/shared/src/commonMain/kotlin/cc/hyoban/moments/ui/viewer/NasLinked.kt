package cc.hyoban.moments.ui.viewer

import cc.hyoban.moments.data.spec.NasLink
import cc.hyoban.moments.nas.Nas
import cc.hyoban.moments.nas.NasPhoto
import cc.hyoban.moments.nas.NasProtocol
import java.io.IOException

/*
 * A Moment's photo linked to the NAS, looked at in a viewer as the NAS's own photo is: the site's
 * copy may be smaller, 打码'd or a still, while the NAS keeps the original whole, live or not. Only
 * what is looked at changes; what is shared or saved stays the site's.
 */

/**
 * Where [photo]'s live clip is played from, the same wherever it is looked at, 相册's NAS or a Moment's
 * photo linked to it: a motion photo's read by range from its original, where the start of the
 * original says it starts, or from the original already down; an iPhone's, the MOV the NAS keeps
 * beside it, brought down once and kept ([Nas.probeClip]), the answer kept too. Null for a still;
 * throws when the start of the original couldn't be read, so that isn't taken for an iPhone's.
 */
suspend fun Nas.clipOf(photo: NasPhoto): MotionSource? {
    val start = clipStart(photo)
    if (start != null) {
        cached(photo)?.let { return MotionSource(it.toURI().toString(), start) }
        val (url, headers) = original(photo)
        return MotionSource(url, start, headers, motionKey(photo, apart = false))
    }
    return probeClip(photo)?.let { MotionSource(it.toURI().toString()) }
}

/** Where the photo linked to [link] has its live clip ([clipOf]), its original's size read first; throws when the NAS couldn't be asked. */
suspend fun Nas.linkedClip(link: NasLink): MotionSource? =
    clipOf(linkedPhoto(link) ?: throw IOException("the size of ${link.name} not read"))

/**
 * The clip a viewer plays for a photo linked to [link]: the NAS's ([linkedClip]), else [published]
 * (the site's clip, a published live photo's) when the NAS has none or couldn't be asked.
 */
suspend fun Nas.viewedClip(link: NasLink, published: MotionSource?): MotionSource? =
    try {
        linkedClip(link) ?: published
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        published
    }

/** Fetches the NAS's big thumbnail of the photo linked to [link] ahead, for a viewer turning to it. */
suspend fun Nas.linkedAhead(link: NasLink) {
    thumbnail(photo(link), NasProtocol.BIG)
}
