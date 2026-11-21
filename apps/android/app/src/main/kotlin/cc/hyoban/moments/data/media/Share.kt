package cc.hyoban.moments.data.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import cc.hyoban.moments.data.spec.MediaType
import cc.hyoban.moments.data.spec.MomentMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Hands a photo or video to the share sheet: a photo, or a live photo's still, at its largest,
 * a video whole, without where it was taken ([Outgoing]). The phone's own copy is used while it
 * is there, else the published original is downloaded, into the cache's shared/, which the app's
 * FileProvider serves. Each handoff has its own directory, so
 * sharing another item doesn't remove files a receiving app may still be reading.
 */
object Share {
    /** The one item, its Moment's [text] beside it for an app that takes words with a photo. */
    suspend fun media(context: Context, media: MomentMedia, urls: MediaUrls, text: String) {
        val send = prepare(context, listOf(media), urls).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens X's post composer, never its separate direct-message share target. */
    suspend fun toX(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) {
        require(photos.size <= 20)
        toApp(context, "com.twitter.android", text, photos, urls,
            activity = "com.twitter.composer.ComposerActivity")
    }

    /** The recipient and final send are chosen inside Telegram. */
    suspend fun toTelegram(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls, beforeOpen: () -> Unit = {}) =
        toApp(context, "org.telegram.messenger", text, photos, urls, beforeOpen = beforeOpen)

    suspend fun toXiaohongshu(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) =
        toApp(context, "com.xingin.xhs", text, photos, urls)

    suspend fun toThreads(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls, beforeOpen: () -> Unit = {}) {
        if (photos.isEmpty()) {
            require(text.isNotBlank())
            val compose = Intent(Intent.ACTION_VIEW, Uri.Builder()
                .scheme("barcelona").authority("create").appendQueryParameter("text", text).build())
                .setPackage("com.instagram.barcelona")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryOpen(context, compose)) return
        }
        toApp(context, "com.instagram.barcelona", text, photos, urls, beforeOpen = beforeOpen)
    }

    /** Bluesky's composer takes text on its own link, and photos (four at most) through its share entry. */
    suspend fun toBluesky(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) {
        require(photos.size <= 4)
        if (photos.isEmpty()) {
            require(text.isNotBlank())
            val compose = Intent(Intent.ACTION_VIEW, Uri.Builder()
                .scheme("bluesky").authority("intent").path("compose").appendQueryParameter("text", text).build())
                .setPackage("xyz.blueskyweb.app")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryOpen(context, compose)) return
        }
        toApp(context, "xyz.blueskyweb.app", text, photos, urls)
    }

    /** Instagram's feed post, never its Story, Reel or Direct entries; it takes photos only, its caption written there. */
    suspend fun toInstagram(context: Context, photos: List<MomentMedia>, urls: MediaUrls, beforeOpen: () -> Unit = {}) {
        require(photos.isNotEmpty())
        toApp(context, "com.instagram.android", "", photos, urls, beforeOpen = beforeOpen,
            activity = if (photos.size == 1) "com.instagram.share.handleractivity.ShareHandlerActivity"
            else "com.instagram.share.handleractivity.ShareHandlerActivityMultipleFeedAlias")
    }

    suspend fun toBilibili(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) =
        toApp(context, "tv.danmaku.bili", text, photos, urls,
            activity = "com.bilibili.bplus.followingpublish.FollowingPublishActivity",
            grantToPackage = true) {
            action = "tv.danmaku.bili.intent.action.FOLLOWING_SHARE"
            putExtra("content", text)
            putExtra("share_publish", true)
        }

    suspend fun toWeChat(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) {
        require(photos.size <= 1)
        toApp(context, "com.tencent.mm", text, photos, urls,
            activity = "com.tencent.mm.ui.tools.ShareToTimeLineUI",
            grantToPackage = true) {
            // This receiver requires an image MIME type even for a text-only Moment.
            type = "image/*"
            putExtra("Kdescription", text)
            if (photos.isEmpty()) putExtra("Ksnsupload_empty_img", true)
        }
    }

    suspend fun toDouyin(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) {
        require(photos.isNotEmpty())
        toApp(context, "com.ss.android.ugc.aweme", text, photos, urls,
            activity = "com.ss.android.ugc.aweme.share.SystemShareActivity",
            fallbackActivity = "com.ss.android.ugc.aweme.share.ui.SystemShareNewActivity",
            grantToPackage = true)
    }

    private suspend fun toApp(
        context: Context,
        packageName: String,
        text: String,
        photos: List<MomentMedia>,
        urls: MediaUrls,
        activity: String? = null,
        fallbackActivity: String? = null,
        grantToPackage: Boolean = false,
        beforeOpen: () -> Unit = {},
        configure: Intent.() -> Unit = {},
    ) {
        require(photos.none { it.type == MediaType.VIDEO })
        require(text.isNotBlank() || photos.isNotEmpty())
        val probe = Intent(if (photos.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
            .setType(if (photos.isEmpty()) "text/plain" else "image/*")
            .setPackage(packageName)
            .apply { activity?.let { setClassName(packageName, it) } }
            .apply(configure)
        val fallbackProbe = fallbackActivity?.let { Intent(probe).setClassName(packageName, it) }
        if (probe.resolveActivity(context.packageManager) == null &&
            fallbackProbe?.resolveActivity(context.packageManager) == null) throw ActivityNotFoundException()
        val send = prepare(context, photos, urls)
            .setPackage(packageName)
            .apply { activity?.let { setClassName(packageName, it) } }
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply(configure)
        // Some receivers close before the publishing flow reads the files. Keep read access
        // across that internal handoff; files() revokes it when the cached files expire.
        val granted = mutableListOf<Uri>()
        try {
            if (grantToPackage) send.clipData?.let { clip ->
                repeat(clip.itemCount) { index ->
                    val uri = clip.getItemAt(index).uri
                    context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    granted.add(uri)
                }
            }
            beforeOpen()
            if (!tryOpen(context, send) &&
                (fallbackActivity == null || !tryOpen(context, Intent(send).setClassName(packageName, fallbackActivity)))) {
                throw ActivityNotFoundException()
            }
        } catch (e: Exception) {
            granted.forEach { context.revokeUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            throw e
        }
    }

    /** Only an unavailable/restricted entry falls back; download failures and cancellation do not. */
    private fun tryOpen(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Hands [uris], 相册's photos and videos (the gallery's, the camera's, the NAS's kept on the
     * phone), to the share sheet as a Moment's are: copied without where they were taken.
     */
    suspend fun uris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val files = PhoneFiles(context)
        val parts = withContext(Dispatchers.IO) {
            uris.map { uri -> Outgoing.Part(uri.toString(), published = null, video = files.type(uri.toString())?.startsWith("video/") == true) }
        }
        // Nothing of these is published, so nothing is asked of the media's origin.
        val shared = handoff(context, parts, MediaUrls(""))
        context.startActivity(Intent.createChooser(intent(context, shared), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun prepare(context: Context, media: List<MomentMedia>, urls: MediaUrls): Intent {
        if (media.isEmpty()) return Intent(Intent.ACTION_SEND).setType("text/plain")
        return intent(context, files(context, media, urls))
    }

    private fun intent(context: Context, files: List<Pair<File, String>>): Intent {
        val uris = ArrayList(files.map { (file, _) ->
            FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        })
        val types = files.map { it.second }.distinct()
        val type = types.singleOrNull() ?: when {
            types.all { it.startsWith("image/") } -> "image/*"
            types.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            .setType(type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply {
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri(null, uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
    }

    /**
     * Each item as it may leave the phone, as [Outgoing] has it: a photo or a live photo's still
     * at its largest, a video whole, without where it was taken.
     */
    internal suspend fun files(context: Context, media: List<MomentMedia>, urls: MediaUrls): List<Pair<File, String>> =
        handoff(context, media.map { Outgoing.parts(it, withClip = false).single() }, urls)

    /**
     * [parts] written for one handoff into a new directory of the cache's shared/, the published
     * originals kept beside it; handoffs and originals a week old go.
     */
    private suspend fun handoff(context: Context, parts: List<Outgoing.Part>, urls: MediaUrls): List<Pair<File, String>> =
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, "shared").apply { mkdirs() }
            // Leave recent handoffs intact, including X drafts that haven't read their files yet.
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { old ->
                old.walkTopDown().filter { it.isFile }.forEach { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                old.deleteRecursively()
            }
            val originals = File(context.cacheDir, ORIGINALS).apply { mkdirs() }
            originals.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                // Each item is read or downloaded on its own, a few at a time, rather than one after another.
                val gate = Semaphore(PARALLEL)
                val files = PhoneFiles(context)
                parts.mapIndexed { index, part ->
                    async { gate.withPermit { Outgoing.fetch(part, urls, files, directory, originals, "$index") } }
                }.awaitAll()
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }

    /** How the phone reads its own photos and videos: through their providers, their types as those say or as their names do. */
    private class PhoneFiles(private val context: Context) : Outgoing.Files {
        override fun open(address: String) = context.contentResolver.openInputStream(address.toUri())

        override fun type(address: String): String? = address.toUri().let { uri ->
            context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
        }

        override fun extension(type: String) = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: type.substringAfter('/')
    }

    private const val ORIGINALS = "shared-originals"
    private const val PARALLEL = 4
}
