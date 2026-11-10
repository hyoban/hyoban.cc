package cc.hyoban.moments.ui.picker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import cc.hyoban.moments.ui.components.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A photo or a video in the phone's gallery, as the media store lists it. */
data class LocalItem(
    val id: Long,
    val uri: Uri,
    /** When it was taken, else added, in milliseconds. */
    val taken: Long,
    val mime: String?,
    val video: Boolean,
    /** A video's length, in milliseconds. */
    val duration: Long?,
    val album: String?,
    val albumId: Long?,
    val name: String?,
    val bytes: Long?,
    val width: Int?,
    val height: Int?,
) {
    val key get() = "local/$id"
    val day: LocalDate get() = Instant.ofEpochMilli(taken).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** An album of the gallery: its photos' folder, as the gallery groups them. */
data class LocalAlbum(val id: Long, val name: String, val count: Int)

/** How much of the gallery the app may see. */
enum class GalleryAccess { ALL, CHOSEN, NONE }

object LocalMedia {
    /** What is asked for: photos and videos, and on Android 14 and later what the system offers to choose a few. */
    val permissions: Array<String> = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) else buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }.toTypedArray()

    fun access(context: Context): GalleryAccess = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            if (context.hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) GalleryAccess.ALL else GalleryAccess.NONE
        context.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryAccess.ALL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.CHOSEN
        else -> GalleryAccess.NONE
    }

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Every photo and video the app may see, newest first, again whenever the gallery changes. */
    fun items(context: Context): Flow<List<LocalItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().map { query(context) }.flowOn(Dispatchers.IO)

    private fun query(context: Context): List<LocalItem> {
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val order = "COALESCE(${MediaStore.MediaColumns.DATE_TAKEN}, ${MediaStore.MediaColumns.DATE_ADDED} * 1000) DESC"
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return context.contentResolver.query(collection, columns, selection, null, order)?.use { c ->
            fun long(i: Int) = if (c.isNull(i)) null else c.getLong(i)
            fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i).takeIf { it > 0 }
            buildList(c.count) {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val turned = c.getInt(12).let { it == 90 || it == 270 }
                    val w = int(10)
                    val h = int(11)
                    add(LocalItem(
                        id = id,
                        uri = ContentUris.withAppendedId(if (video) videos else images, id),
                        taken = long(2)?.takeIf { it > 0 } ?: ((long(3) ?: 0L) * 1000),
                        mime = c.getString(4),
                        video = video,
                        duration = long(5),
                        album = c.getString(6),
                        albumId = long(7),
                        name = c.getString(8),
                        bytes = long(9),
                        width = if (turned) h else w,
                        height = if (turned) w else h,
                    ))
                }
            }
        }.orEmpty()
    }

    /** The albums in [items], the largest first. */
    fun albums(items: List<LocalItem>): List<LocalAlbum> =
        items.filter { it.albumId != null }.groupBy { it.albumId!! }
            .map { (id, them) -> LocalAlbum(id, them.first().album ?: "", them.size) }
            .sortedByDescending { it.count }

    /** Whether [item] is a motion photo, as its XMP in the media store says, without reading the file. */
    fun isMotion(context: Context, item: LocalItem): Boolean {
        if (item.video) return false
        return runCatching {
            context.contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.XMP), null, null, null)?.use { c ->
                if (!c.moveToFirst() || c.isNull(0)) return@use false
                val xmp = String(c.getBlob(0), Charsets.UTF_8)
                "MotionPhoto=\"1\"" in xmp || "MicroVideo=\"1\"" in xmp || "<GCamera:MotionPhoto>1" in xmp
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * [uri] for a Moment: the original, where the app may see where it was taken, so its EXIF keeps
     * its place; the media store's redacted copy otherwise.
     */
    fun forMoment(context: Context, uri: Uri): Uri =
        if (context.hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)) MediaStore.setRequireOriginal(uri) else uri
}
