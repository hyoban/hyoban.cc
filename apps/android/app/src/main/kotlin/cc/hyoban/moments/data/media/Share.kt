package cc.hyoban.moments.data.media

import android.content.ActivityNotFoundException
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Hands a photo or video to the share sheet: a photo, or a live photo's still, at its largest,
 * a video whole. A published one is downloaded, one on the phone copied, into the cache's
 * shared/, which the app's FileProvider serves. Each handoff has its own directory, so
 * sharing another item doesn't remove files a receiving app may still be reading.
 */
object Share {
    suspend fun media(context: Context, media: MomentMedia, urls: MediaUrls) {
        val send = prepare(context, listOf(media), urls)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens X's post composer, never its separate direct-message share target. */
    suspend fun toX(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) {
        require(photos.size <= 4)
        toApp(context, "com.twitter.android", text, photos, urls,
            activity = "com.twitter.composer.ComposerActivity")
    }

    /** The recipient and final send are chosen inside Telegram. */
    suspend fun toTelegram(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) =
        toApp(context, "org.telegram.messenger", text, photos, urls)

    suspend fun toXiaohongshu(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) =
        toApp(context, "com.xingin.xhs", text, photos, urls)

    suspend fun toThreads(context: Context, text: String, photos: List<MomentMedia>, urls: MediaUrls) {
        if (photos.isEmpty()) {
            require(text.isNotBlank())
            val compose = Intent(Intent.ACTION_VIEW, Uri.Builder()
                .scheme("barcelona").authority("create").appendQueryParameter("text", text).build())
                .setPackage("com.instagram.barcelona")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryOpen(context, compose)) return
        }
        toApp(context, "com.instagram.barcelona", text, photos, urls)
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

    private suspend fun prepare(context: Context, media: List<MomentMedia>, urls: MediaUrls): Intent {
        if (media.isEmpty()) return Intent(Intent.ACTION_SEND).setType("text/plain")
        val files = files(context, media, urls)
        val uris = ArrayList(files.map { (file, _) ->
            FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        })
        val types = files.map { it.second }.distinct()
        val type = types.singleOrNull() ?: if (types.all { it.startsWith("image/") }) "image/*" else "*/*"
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

    internal suspend fun files(context: Context, media: List<MomentMedia>, urls: MediaUrls): List<Pair<File, String>> =
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
            val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                media.mapIndexed { index, item ->
                    ensureActive()
                    fetch(context, item, urls, directory, index)
                }
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }

    private fun fetch(context: Context, media: MomentMedia, urls: MediaUrls, directory: File, index: Int): Pair<File, String> {
        val video = media.type == MediaType.VIDEO
        val published = media.published
        if (published != null) {
            val type = published.contentType
            val file = File(directory, "$index.${extension(type)}")
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
        val file = File(directory, "$index.${extension(type)}")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read $uri")
        input.use { from -> file.outputStream().use { from.copyTo(it) } }
        return file to type
    }

    private fun extension(type: String) = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: type.substringAfter('/')
}
