package cc.hyoban.moments.data.api

import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

// The API (apps/api in the repository). Its public read: the change feed, the published
// Moments a page at a time and the places and trips beside them, with no token, so no
// drafts. Its writes, with the owner's token: a photo's original, then everything the
// phone changed as one.

/**
 * A page of the change feed: what changed after a revision, and where the next page
 * starts. The places and trips come on the first page only, every one of them on a feed
 * from the start.
 */
@Serializable
data class Changes(
    val revision: Long,
    val moments: List<MomentRecord>,
    /** Moments removed since, by uid. */
    val deleted: List<String>,
    val places: Map<String, Place> = emptyMap(),
    val deletedPlaces: List<String> = emptyList(),
    val trips: List<TripRecord> = emptyList(),
    val deletedTrips: List<String> = emptyList(),
    /** Pass as `cursor` for the next page; null on the last one. */
    val next: String? = null,
)

/** Everything the phone changed, for `POST /v1/changes`: the places only it knows, the Moments to publish, the uids to delete. */
@Serializable
data class ChangeSet(val places: Map<String, Place>, val moments: List<MomentRecord>, val deleted: List<String>)

/**
 * What the API did with a [ChangeSet]: the id each place sent is known by, the Moments as
 * published, the uids deleted, and the revision the write took, null when it changed nothing.
 */
@Serializable
data class AppliedChanges(
    val places: Map<String, String>,
    val moments: List<MomentRecord>,
    val deleted: List<String>,
    val revision: Long? = null,
)

/** What the app writes the records to, with the owner's token; the tests stand in for the API with their own. */
interface MomentsWriter {
    /** The MD5 of the original kept for media item [mediaUid] of Moment [momentUid], or null when none is. */
    suspend fun uploadedImage(momentUid: String, mediaUid: String): String?

    /** Keeps a photo's original for media item [mediaUid] of Moment [momentUid], until it publishes. */
    suspend fun uploadImage(momentUid: String, mediaUid: String, file: File, contentType: String)

    /**
     * Applies all of [changes] in one write, each Moment published, its originals uploaded
     * first; the API takes all of it or none.
     */
    suspend fun applyChanges(changes: ChangeSet): AppliedChanges
}

/** The API turned a write down, or there was no token to write with: what it said. */
class ApiException(message: String) : IOException(message)

/** What the app reads the records from; the tests stand in for the API with their own. */
interface MomentsSource {
    /** What changed after revision [since], or from [cursor] when it continues a feed. */
    suspend fun changes(since: Long, cursor: String?): Changes
}

/**
 * The API at [baseUrl], with the owner's [token] for writes, each read again for each
 * request so a change in the settings takes at once.
 */
class MomentsApi(
    private val baseUrl: () -> String,
    private val token: () -> String? = { null },
    private val client: OkHttpClient = Http.client,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MomentsSource, MomentsWriter {
    override suspend fun changes(since: Long, cursor: String?): Changes =
        get(if (cursor != null) "/v1/changes?cursor=$cursor&limit=500" else "/v1/changes?since=$since&limit=500", Changes.serializer())

    override suspend fun uploadedImage(momentUid: String, mediaUid: String): String? = withContext(io) {
        val path = "/v1/moments/$momentUid/media/$mediaUid/image"
        client.newCall(authorized(path).head().build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.header("ETag")?.trim('"')
                else -> throw ApiException("HEAD $path answered ${response.code}")
            }
        }
    }

    override suspend fun uploadImage(momentUid: String, mediaUid: String, file: File, contentType: String) {
        write("PUT", "/v1/moments/$momentUid/media/$mediaUid/image", file.asRequestBody(contentType.toMediaType()))
    }

    override suspend fun applyChanges(changes: ChangeSet): AppliedChanges =
        decode(write("POST", "/v1/changes", encode(ChangeSet.serializer(), changes)), AppliedChanges.serializer())

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        decode(Http.getText(baseUrl() + path, client, io), serializer)

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        Spec.json.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> decode(text: String, serializer: KSerializer<T>): T = Spec.json.decodeFromString(serializer, text)

    /** A write with the token; its answer's body, or an [ApiException] with what the API said. */
    private suspend fun write(method: String, path: String, body: RequestBody): String = withContext(io) {
        val request = authorized(path).method(method, body).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorOf(text) ?: "$method $path answered ${response.code}")
            text
        }
    }

    /** A request to [path] with the owner's token, or an [ApiException] when there is none. */
    private fun authorized(path: String): Request.Builder {
        val token = token()?.takeIf { it.isNotBlank() } ?: throw ApiException("No API token")
        return Request.Builder().url(baseUrl() + path).header("Authorization", "Bearer $token")
    }

    /** The API's `{ "error": … }`, when that is what it answered. */
    private fun errorOf(text: String): String? = runCatching {
        Spec.json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
