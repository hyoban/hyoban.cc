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

// The API (apps/api in the repository). Its public reads: the published Moments as a
// change feed, and the places and trips whole, with no token, so no drafts. Its writes,
// with the owner's token: a photo's original, then everything the phone changed as one.

/** A page of the change feed: what changed after a revision, and where the next page starts. */
@Serializable
data class MomentChanges(
    val revision: Long,
    val moments: List<MomentRecord>,
    /** Moments removed since, by uid. */
    val deleted: List<String>,
    /** Pass as `cursor` for the next page; null on the last one. */
    val next: String? = null,
)

@Serializable
data class PlacesResponse(val revision: Long, val places: Map<String, Place>)

@Serializable
data class TripsResponse(val revision: Long, val trips: List<TripRecord>)

/** Everything the phone changed, for `POST /v1/changes`: the places only it knows, the Moments to publish, the uids to delete. */
@Serializable
data class ChangeSet(val places: Map<String, Place>, val moments: List<MomentRecord>, val deleted: List<String>)

/** What the API did with a [ChangeSet]: the id each place sent is known by, the Moments as published, the uids deleted. */
@Serializable
data class AppliedChanges(val places: Map<String, String>, val moments: List<MomentRecord>, val deleted: List<String>)

/** What the app writes the records to, with the owner's token; the tests stand in for the API with their own. */
interface MomentsWriter {
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
    /** The Moments changed after revision [since], or from [cursor] when it continues a feed. */
    suspend fun moments(since: Long, cursor: String?): MomentChanges

    suspend fun places(): PlacesResponse

    suspend fun trips(): TripsResponse
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
    override suspend fun moments(since: Long, cursor: String?): MomentChanges =
        get(if (cursor != null) "/v1/moments?cursor=$cursor&limit=500" else "/v1/moments?since=$since&limit=500", MomentChanges.serializer())

    override suspend fun places(): PlacesResponse = get("/v1/places", PlacesResponse.serializer())

    override suspend fun trips(): TripsResponse = get("/v1/trips", TripsResponse.serializer())

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
        val token = token()?.takeIf { it.isNotBlank() } ?: throw ApiException("No API token")
        val request = Request.Builder().url(baseUrl() + path).method(method, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(errorOf(text) ?: "$method $path answered ${response.code}")
            text
        }
    }

    /** The API's `{ "error": … }`, when that is what it answered. */
    private fun errorOf(text: String): String? = runCatching {
        Spec.json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
