package cc.hyoban.moments.data.api

import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.HttpException
import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
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

/**
 * Everything the phone changed: the places only it knows, the Moments to publish, the uids to
 * delete; and what only the second shape holds (`POST /v2/changes`): the entries beside the
 * Moments (a public bill alone), parts a Moment carries there (a public bill of its meal) by
 * its uid, and the public spans of the footprints, with those taken back.
 */
@Serializable
data class ChangeSet(
    val places: Map<String, Place>,
    val moments: List<MomentRecord>,
    val deleted: List<String>,
    val entries: List<JsonObject> = emptyList(),
    val parts: Map<String, Map<String, JsonObject>> = emptyMap(),
    val tracks: List<JsonObject> = emptyList(),
    val deletedTracks: List<String> = emptyList(),
    /** Trips changed on the phone, as collections. */
    val collections: List<JsonObject> = emptyList(),
) {
    /** Whether it holds anything the first shape can't send. */
    val needsSecondShape: Boolean get() = entries.isNotEmpty() || parts.isNotEmpty() || tracks.isNotEmpty() || deletedTracks.isNotEmpty() || collections.isNotEmpty()
}

/** A [ChangeSet] as `POST /v2/changes` takes it. */
@Serializable
private data class EntryChangeSet(
    val places: Map<String, Place>,
    val entries: List<JsonObject>,
    val deleted: List<String>,
    val tracks: List<JsonObject>,
    val deletedTracks: List<String>,
    val collections: List<JsonObject>,
)

@Serializable
private data class AppliedEntryChanges(
    val places: Map<String, String>,
    val entries: List<JsonObject>,
    val deleted: List<String>,
    val revision: Long? = null,
)

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

/** An original of a media item: a photo, a live photo's still, or a video or a live photo's motion. */
enum class UploadPart(val path: String) { IMAGE("image"), VIDEO("video") }

/** What the app writes the records to, with the owner's token; the tests stand in for the API with their own. */
interface MomentsWriter {
    /** The MD5 of the original [part] kept for media item [mediaUid] of Moment [momentUid], or null when none is. */
    suspend fun uploaded(momentUid: String, mediaUid: String, part: UploadPart): String?

    /**
     * Keeps an original [part] of media item [mediaUid] of Moment [momentUid], until it
     * publishes, telling [onSent] of each stretch of its bytes as they go out.
     */
    suspend fun upload(momentUid: String, mediaUid: String, part: UploadPart, file: File, contentType: String, onSent: (Long) -> Unit = {})

    /**
     * Applies all of [changes] in one write, each Moment published, its originals uploaded
     * first; the API takes all of it or none.
     */
    suspend fun applyChanges(changes: ChangeSet): AppliedChanges
}

/** The API turned a write down, answering [code]: what it said. */
class ApiException(code: Int, message: String) : HttpException(code, message)

/** There was no token to write with. */
class NoTokenException : IOException("No API token")

/**
 * A page of the second shape's feed (`GET /v2/changes`): the entries, collections and public
 * tracks that changed after a revision, the collections and tracks on the first page only.
 */
@Serializable
data class EntryChanges(
    val revision: Long,
    val entries: List<JsonObject>,
    val deleted: List<String>,
    val collections: List<JsonObject> = emptyList(),
    val deletedCollections: List<String> = emptyList(),
    val tracks: List<JsonObject> = emptyList(),
    val deletedTracks: List<String> = emptyList(),
    val next: String? = null,
)

/** What the app reads the records from; the tests stand in for the API with their own. */
interface MomentsSource {
    /** What changed after revision [since], or from [cursor] when it continues a feed. */
    suspend fun changes(since: Long, cursor: String?): Changes

    /** The second shape's feed, or null from an API without it. */
    suspend fun entryChanges(since: Long, cursor: String?): EntryChanges? = null
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

    /** With the token when there is one, so the owner's copy has what is private too. */
    override suspend fun entryChanges(since: Long, cursor: String?): EntryChanges? = withContext(io) {
        val path = if (cursor != null) "/v2/changes?cursor=$cursor&limit=500" else "/v2/changes?since=$since&limit=500"
        val request = Request.Builder().url(baseUrl() + path).apply {
            token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw ApiException(response.code, "GET $path answered ${response.code}")
                else -> decode(response.body!!.string(), EntryChanges.serializer())
            }
        }
    }

    override suspend fun uploaded(momentUid: String, mediaUid: String, part: UploadPart): String? = withContext(io) {
        val path = "/v1/moments/$momentUid/media/$mediaUid/${part.path}"
        client.newCall(authorized(path).head().build()).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.header("ETag")?.trim('"')
                else -> throw ApiException(response.code, "HEAD $path answered ${response.code}")
            }
        }
    }

    override suspend fun upload(momentUid: String, mediaUid: String, part: UploadPart, file: File, contentType: String, onSent: (Long) -> Unit) {
        write("PUT", "/v1/moments/$momentUid/media/$mediaUid/${part.path}", CountedFileBody(file, contentType.toMediaType(), onSent), uploads)
    }

    /**
     * The client for originals, with room to wait: on a slow link, a proxy's in particular,
     * a write can stall well past OkHttp's 10 seconds and still get through.
     */
    private val uploads by lazy { client.newBuilder().writeTimeout(UPLOAD_TIMEOUT).readTimeout(UPLOAD_TIMEOUT).build() }

    /** The client for the change set, whose answer waits on the API copying every original into place. */
    private val publishing by lazy { client.newBuilder().readTimeout(PUBLISH_TIMEOUT).build() }

    /**
     * Sends the change set in the second shape, the Moments as entries; an API from before it
     * (a 404) takes the first, unless the set holds what only the second can carry.
     */
    override suspend fun applyChanges(changes: ChangeSet): AppliedChanges {
        val second = EntryChangeSet(
            places = changes.places,
            entries = changes.moments.map { Entries.fromMoment(it, changes.parts[it.uid].orEmpty()) } + changes.entries,
            deleted = changes.deleted,
            tracks = changes.tracks,
            deletedTracks = changes.deletedTracks,
            collections = changes.collections,
        )
        val text = try {
            write("POST", "/v2/changes", encode(EntryChangeSet.serializer(), second), publishing)
        } catch (error: ApiException) {
            if (error.code != 404 || changes.needsSecondShape) throw error
            val first = ChangeSet(changes.places, changes.moments, changes.deleted)
            return decode(write("POST", "/v1/changes", encode(ChangeSet.serializer(), first), publishing), AppliedChanges.serializer())
        }
        val applied = decode(text, AppliedEntryChanges.serializer())
        return AppliedChanges(applied.places, applied.entries.mapNotNull(Entries::toMoment), applied.deleted, applied.revision)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        decode(Http.getText(baseUrl() + path, client, io), serializer)

    private fun <T> encode(serializer: KSerializer<T>, value: T): RequestBody =
        Spec.json.encodeToString(serializer, value).toRequestBody(JSON)

    private fun <T> decode(text: String, serializer: KSerializer<T>): T = Spec.json.decodeFromString(serializer, text)

    /** A write with the token; its answer's body, or an [ApiException] with what the API said. */
    private suspend fun write(method: String, path: String, body: RequestBody, client: OkHttpClient = this.client): String = withContext(io) {
        val request = authorized(path).method(method, body).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorOf(text) ?: "$method $path answered ${response.code}")
            text
        }
    }

    /** A request to [path] with the owner's token, or a [NoTokenException] when there is none. */
    private fun authorized(path: String): Request.Builder {
        val token = token()?.takeIf { it.isNotBlank() } ?: throw NoTokenException()
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

private val UPLOAD_TIMEOUT = java.time.Duration.ofSeconds(60)
private val PUBLISH_TIMEOUT = java.time.Duration.ofMinutes(3)

/** A file sent as it is, telling [onSent] of every [STRETCH] or so of it written to the network. */
private class CountedFileBody(private val file: File, private val type: okhttp3.MediaType, private val onSent: (Long) -> Unit) : RequestBody() {
    override fun contentType() = type

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.source().use { source ->
            var unsaid = 0L
            while (true) {
                val read = source.read(sink.buffer, SEGMENT)
                if (read < 0) break
                sink.emit()
                unsaid += read
                if (unsaid >= STRETCH) {
                    onSent(unsaid)
                    unsaid = 0
                }
            }
            if (unsaid > 0) onSent(unsaid)
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
        const val STRETCH = 256L * 1024
    }
}
