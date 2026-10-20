package cc.hyoban.moments.data.api

import cc.hyoban.moments.data.Http
import cc.hyoban.moments.data.spec.MomentRecord
import cc.hyoban.moments.data.spec.Place
import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.spec.TripRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

// The API's public reads (apps/api in the repository): the published Moments as a change
// feed, and the places and trips whole. No token, so no drafts.

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

/** What the app reads the records from; the tests stand in for the API with their own. */
interface MomentsSource {
    /** The Moments changed after revision [since], or from [cursor] when it continues a feed. */
    suspend fun moments(since: Long, cursor: String?): MomentChanges

    suspend fun places(): PlacesResponse

    suspend fun trips(): TripsResponse
}

/** The API at [baseUrl], read again for each request so a change in the settings takes at once. */
class MomentsApi(
    private val baseUrl: () -> String,
    private val client: OkHttpClient = Http.client,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MomentsSource {
    override suspend fun moments(since: Long, cursor: String?): MomentChanges =
        get(if (cursor != null) "/v1/moments?cursor=$cursor&limit=500" else "/v1/moments?since=$since&limit=500", MomentChanges.serializer())

    override suspend fun places(): PlacesResponse = get("/v1/places", PlacesResponse.serializer())

    override suspend fun trips(): TripsResponse = get("/v1/trips", TripsResponse.serializer())

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        Spec.json.decodeFromString(serializer, Http.getText(baseUrl() + path, client, io))
}
