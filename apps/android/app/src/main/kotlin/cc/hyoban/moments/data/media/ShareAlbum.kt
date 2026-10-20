package cc.hyoban.moments.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** A dedicated album, kept for the user and for drafts after the handoff finishes. */
data class ShareAlbum(val name: String, val count: Int)

suspend fun saveShareAlbum(context: Context, photos: List<MomentMedia>, urls: MediaUrls): ShareAlbum {
    require(photos.isNotEmpty() && photos.none { it.type == MediaType.VIDEO })
    val files = Share.files(context, photos, urls)
    return withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val name = "这里分享-${now.toString(36)}"
        val resolver = context.contentResolver
        val saved = mutableListOf<Uri>()
        try {
            // The newest first is also the Moment's first. Give both insertion order and
            // timestamps that order, since gallery versions sort on different columns.
            files.withIndex().reversed().forEach { (index, item) ->
                ensureActive()
                val (file, type) = item
                val filename = "${(index + 1).toString().padStart(maxOf(2, files.size.toString().length), '0')}.${file.extension}"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, type)
                    put(MediaStore.Images.Media.DATE_TAKEN, now - index * 1000L)
                    put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000 - index)
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$name")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name).apply { mkdirs() }
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.DATA, File(directory, filename).absolutePath)
                    }
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                saved += uri
                checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
            }
            ensureActive()
            ShareAlbum(name, photos.size)
        } catch (e: Exception) {
            saved.forEach { runCatching { resolver.delete(it, null, null) } }
            throw e
        }
    }
}
