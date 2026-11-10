package cc.hyoban.moments.nas

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import cc.hyoban.moments.Setting
import cc.hyoban.moments.Token
import cc.hyoban.moments.camera.TAG
import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.nas.NasProtocol.obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.PublicKey
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Why the NAS couldn't be reached or signed in to, for the words the picker shows. */
enum class NasFailure { NOT_FOUND, UNREACHABLE, SIGN_IN, OTHER }

class NasException(val failure: NasFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The owner's UGREEN NAS, its photo app's photos and albums, for 选照片: where it is (its
 * UGREENlink ID, looked up, or an address), who signs in, and the session once signed in.
 * The password is kept beside the WebDAV backup's, out of the phone's backups.
 */
class Nas(context: Context, private val client: OkHttpClient = Http.client) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("nas", Context.MODE_PRIVATE)

    /** The UGREENlink ID, or the NAS's own address. */
    val link = Setting(prefs, "link", "")
    val user = Setting(prefs, "user", "")
    val password = Token(File(context.noBackupFilesDir, "nas-password"))

    val configured get() = link.isSet && user.isSet && password.isSet

    fun change(link: String, user: String, password: String) {
        this.link.set(link.trim())
        this.user.set(user.trim())
        if (password.isNotEmpty()) this.password.set(password)
        forgetSession()
    }

    fun forget() {
        link.set("")
        user.set("")
        password.set("")
        forgetSession()
    }

    private class Session(val base: String, val uid: String, val token: String, val static: String, val key: PublicKey)

    private val lock = Mutex()
    private var session: Session? = null

    private fun forgetSession() {
        session = null
        thumbs.evictAll()
    }

    /** The address it answered at last, tried first next time, so a lookup is needed only when that fails. */
    private var lastBase: String?
        get() = prefs.getString("base", null)
        set(value) = prefs.edit().putString("base", value).apply()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    // Reaching the NAS ------------------------------------------------------------------

    /** An address that answers the NAS's heartbeat, the last one first, else by the link. */
    private suspend fun reach(): String = withContext(Dispatchers.IO) {
        lastBase?.let { if (alive(it, 3)) return@withContext it }
        val value = link.value
        val candidates = if (value.contains("://") || value.contains('.') || value.contains(':')) {
            listOf(if (value.contains("://")) value.trimEnd('/') else "https://$value")
        } else lookup(value)
        if (candidates.isEmpty()) throw NasException(NasFailure.NOT_FOUND, "UGREENlink knows no $value")
        // The home network's first, together, then the rest: the relay is the slowest.
        val (near, far) = candidates.partition { it.contains(".lan.") }
        val base = firstAlive(near, 3) ?: firstAlive(far, 8) ?: throw NasException(NasFailure.UNREACHABLE, "no address answered")
        lastBase = base
        base
    }

    private suspend fun firstAlive(bases: List<String>, seconds: Long): String? = coroutineScope {
        bases.map { base -> async { base.takeIf { alive(it, seconds) } } }.awaitAll().firstOrNull { it != null }
    }

    private fun alive(base: String, seconds: Long): Boolean = runCatching {
        val quick = client.newBuilder().connectTimeout(seconds, TimeUnit.SECONDS).readTimeout(seconds, TimeUnit.SECONDS).build()
        quick.newCall(Request.Builder().url("$base/ugreen/v1/verify/heartbeat").build()).execute().use { response ->
            response.isSuccessful && response.body?.string()?.let { json.parseToJsonElement(it) as? JsonObject }?.get("code")?.jsonPrimitive?.int == 200
        }
    }.getOrDefault(false)

    /** Where UGREENlink says the NAS of [id] is. */
    private fun lookup(id: String): List<String> {
        val body = buildJsonObject { put("alias", id) }.toString().toRequestBody(jsonType)
        val request = Request.Builder().url(NasProtocol.LOOKUP).post(body).header("lang", "zh-CN").build()
        val text = try {
            client.newCall(request).execute().use { it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw NasException(NasFailure.UNREACHABLE, "UGREENlink not reached", e)
        }
        val answer = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (answer?.get("code")?.jsonPrimitive?.content != "200") throw NasException(NasFailure.NOT_FOUND, "UGREENlink: $text")
        return NasProtocol.candidates(answer)
    }

    // Signing in ------------------------------------------------------------------------

    private suspend fun signedIn(): Session = session ?: signIn()

    /** Signs in as the web app does: the password under the key /verify/check gives, then /verify/login. */
    private suspend fun signIn(): Session = withContext(Dispatchers.IO) {
        val base = reach()
        val check = Request.Builder().url("$base/ugreen/v1/verify/check")
            .post(buildJsonObject { put("username", user.value) }.toString().toRequestBody(jsonType)).build()
        val checkKey = client.newCall(check).execute().use { it.header("x-rsa-token") }
            ?: throw NasException(NasFailure.OTHER, "no key to sign in with")
        val login = buildJsonObject {
            put("username", user.value)
            put("password", NasProtocol.encrypt(password.value, NasProtocol.publicKey(checkKey)))
            put("keepalive", true)
            put("otp", true)
            put("is_simple", true)
        }
        val answer = client.newCall(Request.Builder().url("$base/ugreen/v1/verify/login").post(login.toString().toRequestBody(jsonType)).build())
            .execute().use { parse(it.body?.string()) }
        val data = answer.obj("data")
        val token = data?.text("token")
        val key = data?.text("public_key")
        if (answer.code() != 200 || token == null || key == null) {
            Log.w(TAG, "nas: sign-in refused (${answer.code()}) ${answer.text("msg")}")
            throw NasException(NasFailure.SIGN_IN, answer.text("msg") ?: "sign-in refused")
        }
        Session(base, data.text("uid").orEmpty(), token, data.text("static_token") ?: token, NasProtocol.publicKey(key)).also { session = it }
    }

    private fun parse(text: String?): JsonObject =
        runCatching { json.parseToJsonElement(text.orEmpty()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.code() = (get("code") as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

    /** The headers and cookie every signed-in request carries; the token is encrypted afresh each time. */
    private fun Request.Builder.signed(s: Session): Request.Builder {
        val token = NasProtocol.encrypt(s.token, s.key)
        return header("x-ugreen-token", token)
            .header("x-ugreen-security-key", NasProtocol.md5(s.token))
            .header("client-id", CLIENT_ID)
            // The photo app's API version the web app asks for; its lists' shapes follow it.
            .header("Album-Api-Version", "6")
            .header("Cookie", "token_uid=${s.uid}; token=$token")
    }

    /** A signed-in call of [path] with [body] (a POST) or none (a GET with [query]); signed in again once when the session has gone. */
    private suspend fun call(path: String, body: JsonObject? = null, query: Map<String, String> = emptyMap()): JsonObject? = lock.withLock {
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                val s = signedIn()
                val url = "${s.base}/ugreen/$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                val request = Request.Builder().url(url).signed(s)
                    .apply { if (body != null) post(body.toString().toRequestBody(jsonType)) }.build()
                val answer = try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw HttpException(response.code, "$path answered ${response.code}")
                        parse(response.body?.string())
                    }
                } catch (e: IOException) {
                    // The address may have changed (left home): look again next time.
                    session = null
                    lastBase = null
                    if (attempt == 1) throw NasException(NasFailure.UNREACHABLE, "$path not reached", e)
                    return@repeat
                }
                if (answer.code() == 200) return@withContext answer.obj("data")
                Log.w(TAG, "nas: $path answered ${answer.code()} ${answer.text("msg")}")
                session = null
                if (attempt == 1) throw NasException(NasFailure.OTHER, answer.text("msg") ?: "$path refused")
            }
            null
        }
    }

    // The photos ------------------------------------------------------------------------

    /** A page of the photos and videos in the timeline, newest first, and how many there are. */
    suspend fun timeline(offset: Int, limit: Int = PAGE): Pair<List<NasPhoto>, Int?> {
        val data = call("v5/photo/picture/timeline/list", buildJsonObject {
            put("limit", limit)
            put("offset", offset)
            put("sort_order", 0)
            put("sort_by", 0)
            putJsonObject("type_option") { put("all", 1); put("image", 0); put("video", 0); put("gif", 0); put("live", 0); put("raw", 0) }
            // As the web app sends it by default: the owner's files and those shared, and no EXIF filters,
            // each filter there and empty; the NAS answers 参数错误 to an empty advanced_option.
            putJsonObject("range_option") { put("personal", 1); put("share", 1); put("share_manage", 0); put("share_download", 0); put("share_view_only", 0) }
            putJsonObject("advanced_option") { listOf("aperture", "camera", "lens", "focal", "exposure", "iso").forEach { putJsonArray(it) {} } }
            put("total", 1)
        })
        return NasProtocol.photos(data) to (data?.get("total") as? JsonPrimitive)?.content?.toIntOrNull()
    }

    /** The owner's albums: their own, those kept by a rule, and those shared with them. */
    suspend fun albums(): List<NasAlbum> = NasProtocol.albums(call("v5/photo/album/list", buildJsonObject {
        put("sort_by", 4)
        put("sort_order", 0)
        put("album_select", 1)
        putJsonArray("share_select") { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }
        putJsonArray("album_type") { add(JsonPrimitive(1)); add(JsonPrimitive(2)); add(JsonPrimitive(3)) }
        put("limit", 200)
        put("offset", 0)
        put("get_show_type", 1)
    }))

    /** A page of [album]'s photos and videos, newest first. */
    suspend fun albumPhotos(album: NasAlbum, offset: Int, limit: Int = PAGE): Pair<List<NasPhoto>, Int?> {
        val data = call("v5/photo/album/picture/list", buildJsonObject {
            put("album_uuid", album.uuid)
            put("album_type", album.type)
            put("limit", limit)
            put("offset", offset)
            put("sort_by", 0)
            put("sort_order", 0)
            putJsonObject("type_option") { put("all", 1); put("image", 0); put("video", 0); put("gif", 0); put("live", 0) }
            putJsonObject("provider_option") { put("provider_mine", 1); put("provider_other", 1); put("provider_uids", JsonArray(emptyList())) }
            put("total", 1)
        })
        return NasProtocol.photos(data, album) to (data?.get("total") as? JsonPrimitive)?.content?.toIntOrNull()
    }

    /**
     * Where every photo with a place was taken, as the web app's map reads them: the owner's
     * files and those shared, [SPOTS] at a time until a page comes short.
     */
    suspend fun spots(): List<NasSpot> = buildList {
        while (true) {
            val page = NasProtocol.spots(call("v5/photo/ai/map/gps/list", buildJsonObject {
                put("offset", size)
                put("limit", SPOTS)
                put("total", 0)
                putJsonObject("range_option") { put("personal", 1); put("share", 1); put("share_manage", 0); put("share_download", 0); put("share_view_only", 0) }
            }))
            addAll(page)
            if (page.size < SPOTS) break
        }
    }

    /** A page of the photos with these [ids], a heap of the map's, as the NAS orders them. */
    suspend fun spotPhotos(ids: List<Long>, offset: Int, limit: Int = PAGE): List<NasPhoto> =
        NasProtocol.photos(call("v5/photo/ai/map/gps/photo", buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
            put("limit", limit)
            put("offset", offset)
            put("total", 1)
        }))

    /** The small thumbnail of the photo at [spot]. */
    suspend fun thumbnail(spot: NasSpot): Bitmap? =
        thumbnail(NasPhoto(spot.id, "", 0, null, null, video = false, live = false, duration = null, taken = null, uploaded = spot.uploaded))

    /** The stream of [photo] at [size]: a thumbnail, or the original. It wants the session's cookie and static token, no headers. */
    private suspend fun stream(photo: NasPhoto, size: Int): Request {
        val s = lock.withLock { withContext(Dispatchers.IO) { signedIn() } }
        val url: HttpUrl = "${s.base}/ugreen/v5/photo/picture/stream".toHttpUrl().newBuilder()
            .addQueryParameter("id", photo.id.toString())
            .addQueryParameter("size_type", size.toString())
            .apply { photo.album?.let { addQueryParameter("source_album_uuid", it.uuid); addQueryParameter("source_album_type", it.type.toString()) } }
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("upload_time", photo.uploaded.toString())
            .addQueryParameter("ugk", s.static)
            .build()
        return Request.Builder().url(url).header("Cookie", "token_uid=${s.uid}; token=${NasProtocol.encrypt(s.token, s.key)}").build()
    }

    /** [photo]'s original as a player asks for it: its address, and the cookie it wants. */
    suspend fun original(photo: NasPhoto): Pair<String, Map<String, String>> {
        val request = stream(photo, NasProtocol.ORIGINAL)
        return request.url.toString() to mapOf("Cookie" to request.header("Cookie").orEmpty())
    }

    private val thumbs = LruCache<String, Bitmap>(400)

    /** [photo]'s thumbnail, [size] the stream's, small for the grid and big for the viewer; null when it couldn't be had. */
    suspend fun thumbnail(photo: NasPhoto, size: Int = NasProtocol.SMALL): Bitmap? {
        val key = "${photo.key}@$size"
        thumbs.get(key)?.let { return it }
        return runCatching {
            val request = stream(photo, size)
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }
        }.onFailure { Log.w(TAG, "nas: thumbnail not read", it) }.getOrNull()?.also { thumbs.put(key, it) }
    }

    /** The start of [photo]'s original, enough for its EXIF. */
    suspend fun head(photo: NasPhoto): ByteArray? = runCatching {
        val request = stream(photo, NasProtocol.ORIGINAL).newBuilder().header("Range", "bytes=0-${HEAD - 1}").build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.byteStream()?.use { input ->
                    val out = ByteArray(HEAD)
                    var read = 0
                    while (read < HEAD) {
                        val n = input.read(out, read, HEAD - read)
                        if (n < 0) break
                        read += n
                    }
                    out.copyOf(read)
                }
            }
        }
    }.getOrNull()

    /**
     * Saves [photos]' originals to the gallery's Here album, one after another, one already
     * there kept rather than saved twice. [progress] is how many are done and how far into
     * the one under way, 0 to 1.
     */
    suspend fun download(photos: List<NasPhoto>, progress: (Int, Float) -> Unit): List<Uri> =
        photos.mapIndexed { index, photo ->
            progress(index, 0f)
            val request = stream(photo, NasProtocol.ORIGINAL)
            withContext(Dispatchers.IO) {
                existing(photo) ?: save(photo) { out ->
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw HttpException(response.code, "original answered ${response.code}")
                        val body = response.body ?: throw IOException("original without a body")
                        val total = body.contentLength().takeIf { it > 0 } ?: photo.size
                        val job = currentCoroutineContext()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(1 shl 16)
                            var done = 0L
                            while (true) {
                                if (!job.isActive) throw kotlinx.coroutines.CancellationException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                progress(index, if (total > 0) done.toFloat() / total else 0f)
                            }
                        }
                    }
                }
            }
        }.also { progress(photos.size, 0f) }

    private fun collection(photo: NasPhoto) =
        if (photo.video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun existing(photo: NasPhoto): Uri? {
        val collection = collection(photo)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(photo.name, photo.size.toString(), ALBUM), null,
        )?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(collection, it.getLong(0)) }
        return null
    }

    private suspend fun save(photo: NasPhoto, write: suspend (OutputStream) -> Unit): Uri {
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

    private fun deleteBody(photos: List<NasPhoto>) = buildJsonObject {
        putJsonArray("picture_ids") { photos.forEach { add(JsonPrimitive(it.id)) } }
        putJsonArray("folder_ids") {}
        photos.firstOrNull()?.album?.let { put("source_album_type", it.type); put("source_album_uuid", it.uuid) }
    }

    /** Whether deleting [photos] moves them to the NAS's recycle bin or deletes them for good, as the NAS says before. */
    suspend fun deleteKind(photos: List<NasPhoto>): NasDeleteKind =
        NasProtocol.deleteKind(call("v5/photo/picture/file/remove_operability", JsonObject(deleteBody(photos) + ("show_disabled_num" to JsonPrimitive(3)))))

    /** Deletes [photos] on the NAS, a hundred at a time as its web app does; how many it couldn't. */
    suspend fun delete(photos: List<NasPhoto>): Int =
        photos.chunked(100).sumOf { batch -> NasProtocol.deleteMissed(call("v5/photo/picture/file/remove", deleteBody(batch))) }
            .also { thumbs.evictAll() }

    companion object {
        const val PAGE = 200
        private const val SPOTS = 5000
        private const val HEAD = 256 * 1024
        private const val CLIENT_ID = "here-android"
        private val ALBUM = Environment.DIRECTORY_PICTURES + "/Here/"
    }
}
