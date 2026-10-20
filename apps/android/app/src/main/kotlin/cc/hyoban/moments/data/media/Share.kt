package cc.hyoban.moments.data.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Hands a photo or video to the share sheet: a photo, or a live photo's still, at its largest,
 * a video whole. A published one is downloaded, one on the phone copied, into the cache's
 * shared/, which the app's FileProvider serves, the last one shared replacing what was there.
 */
object Share {
    suspend fun media(context: Context, media: MomentMedia, urls: MediaUrls) {
        val (file, type) = withContext(Dispatchers.IO) { fetch(context, media, urls) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun fetch(context: Context, media: MomentMedia, urls: MediaUrls): Pair<File, String> {
        val directory = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val video = media.type == MediaType.VIDEO
        val published = media.published
        if (published != null) {
            val type = published.contentType
            val file = File(directory, "${media.uid}.${extension(type)}")
            Http.client.newCall(Request.Builder().url(urls.asset(published.key)).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GET ${published.key} answered ${response.code}")
                file.outputStream().use { response.body!!.byteStream().copyTo(it) }
            }
            return file to type
        }
        val local = media.local ?: throw IOException("${media.uid} is neither published nor on the phone")
        val uri = Uri.parse(if (video) local.videoUri ?: local.uri else local.uri)
        val type = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
            ?: if (video) "video/mp4" else "image/jpeg"
        val file = File(directory, "${media.uid}.${extension(type)}")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read $uri")
        input.use { from -> file.outputStream().use { from.copyTo(it) } }
        return file to type
    }

    private fun extension(type: String) = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: type.substringAfter('/')
}
