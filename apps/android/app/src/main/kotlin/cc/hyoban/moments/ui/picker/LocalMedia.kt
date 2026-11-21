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
import cc.hyoban.moments.ui.CameraShot
import cc.hyoban.moments.ui.components.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
data class LocalAlbum(val id: Long, val name: String, val count: Int) {
    /** Whether the app writes it itself: its Here album, or one of 准备图文's Here Share albums. */
    val own get() = ownAlbum(name)
}

/** Whether the album named [name] is the app's own, the Here album or a Here Share one. */
fun ownAlbum(name: String?): Boolean = name == "Here" || name?.startsWith("Here Share-") == true

/** Whether [item] is in an album hidden in 相簿, which [settings] keeps. */
fun hiddenAlbum(settings: cc.hyoban.moments.Settings, item: LocalItem): Boolean =
    item.albumId != null && settings.isAlbumHidden(item.albumId.toString(), ownAlbum(item.album))

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
    fun items(context: Context): Flow<List<LocalItem>> = changes(context).map { query(context) }.flowOn(Dispatchers.IO)

    /** Once at first and again whenever the gallery changes, conflated. */
    private fun changes(context: Context): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

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

    /**
     * The photos and videos added to the gallery since [since] (in milliseconds), oldest first, each
     * with its size, again whenever the gallery changes: what the camera app saved while a Moment
     * waited for it. A camera may list a live photo's still first and write it again with its clip
     * a few seconds later, so the same one comes again with its new size. The app's own albums are
     * left out, and nothing is seen without the whole gallery to look in.
     */
    fun addedSince(context: Context, since: Long): Flow<List<CameraShot>> = changes(context).map {
        if (access(context) == GalleryAccess.ALL) shotsSince(context, since) else emptyList()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun shotsSince(context: Context, since: Long): List<CameraShot> {
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" +
            " AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val order = "${MediaStore.MediaColumns.DATE_ADDED} ASC, ${MediaStore.Files.FileColumns._ID} ASC"
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return context.contentResolver.query(collection, columns, selection, arrayOf((since / 1000).toString()), order)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val bytes = if (c.isNull(3)) 0L else c.getLong(3)
                    if (bytes <= 0 || ownAlbum(c.getString(2))) continue
                    val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    add(CameraShot(forMoment(context, ContentUris.withAppendedId(if (video) videos else images, c.getLong(0))), bytes))
                }
            }
        }.orEmpty()
    }

    /** The albums in [items], the largest first. */
    fun albums(items: List<LocalItem>): List<LocalAlbum> =
        items.filter { it.albumId != null }.groupBy { it.albumId!! }
            .map { (id, them) -> LocalAlbum(id, them.first().album ?: "", them.size) }
            .sortedByDescending { it.count }

    /**
     * The gallery's motion photos, by id, as their XMP in the media store says, in one
     * query rather than one for each photo, for 相册's badges and filter.
     */
    fun motionIds(context: Context): Set<Long> = runCatching {
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val xmp = "CAST(${MediaStore.MediaColumns.XMP} AS TEXT)"
        val where = MOTION_MARKS.joinToString(" OR ") { "$xmp LIKE ?" }
        context.contentResolver.query(images, arrayOf(MediaStore.MediaColumns._ID), where, MOTION_MARKS.map { "%$it%" }.toTypedArray(), null)?.use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
    }.onFailure { cc.hyoban.moments.logWarning("Moments", "gallery: motion photos not listed", it) }.getOrDefault(emptySet())

    /** What a motion photo's XMP says of it, Google's and Samsung's and the older Camera's. */
    private val MOTION_MARKS = listOf("MotionPhoto=\"1\"", "MicroVideo=\"1\"", "<GCamera:MotionPhoto>1")

    /**
     * [uri] for a Moment: the original, where the app may see where it was taken, so its EXIF keeps
     * its place; the media store's redacted copy otherwise.
     */
    fun forMoment(context: Context, uri: Uri): Uri =
        if (context.hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)) MediaStore.setRequireOriginal(uri) else uri
}
