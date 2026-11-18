package cc.hyoban.moments.nas

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId

/** Where the phone keeps the NAS's photos: the gallery's Here album, one already there found by its name and size. */
class GalleryKeep(context: Context) : NasKeep {
    private val context = context.applicationContext

    private fun collection(photo: NasPhoto) =
        if (photo.video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun existing(photo: NasPhoto): Uri? {
        val collection = collection(photo)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(photo.name, photo.size.toString(), ALBUM), null,
        )?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(collection, it.getLong(0)) }
        return null
    }

    override suspend fun save(photo: NasPhoto, write: suspend (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.name)
            put(MediaStore.MediaColumns.MIME_TYPE, photo.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM)
            photo.taken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(photo), values) ?: throw IOException("the gallery took nothing")
        try {
            resolver.openOutputStream(uri)!!.buffered(1 shl 20).use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private companion object {
        val ALBUM = Environment.DIRECTORY_PICTURES + "/Here/"
    }
}
