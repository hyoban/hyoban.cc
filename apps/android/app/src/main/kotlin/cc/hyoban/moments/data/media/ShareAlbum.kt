package cc.hyoban.moments.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A dedicated album, kept for the user and for drafts after the handoff finishes. */
data class ShareAlbum(val name: String, val count: Int)

private const val SHARE_ALBUM_PREFIX = "Here Share-"

/** The photos 准备图文 has left in its albums, copies of the Moment's own that can go. */
data class ShareAlbums(val albums: Int, val uris: List<Uri>)

/** Every Here Share album still in the gallery, and the photos in them. */
suspend fun shareAlbums(context: Context): ShareAlbums = withContext(Dispatchers.IO) {
    val uris = mutableListOf<Uri>()
    val albums = mutableSetOf<String>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("${Environment.DIRECTORY_PICTURES}/$SHARE_ALBUM_PREFIX%"),
        null,
    )?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val path = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            uris += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id))
            albums += cursor.getString(path)
        }
    }
    ShareAlbums(albums.size, uris)
}

suspend fun saveShareAlbum(context: Context, photos: List<MomentMedia>, urls: MediaUrls): ShareAlbum {
    require(photos.isNotEmpty() && photos.none { it.type == MediaType.VIDEO })
    val files = Share.files(context, photos, urls)
    return withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val name = "$SHARE_ALBUM_PREFIX${now.toString(36)}"
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$name")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                saved += uri
                checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            ensureActive()
            ShareAlbum(name, photos.size)
        } catch (e: Exception) {
            saved.forEach { runCatching { resolver.delete(it, null, null) } }
            throw e
        }
    }
}
