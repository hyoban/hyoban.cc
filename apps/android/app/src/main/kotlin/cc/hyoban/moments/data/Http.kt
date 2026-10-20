package cc.hyoban.moments.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

// One HTTP client for the whole app: the API's records and routes, Coil's photos and
// Media3's clips and videos all go through it, so they share its connections (HTTP/2 to
// the same few hosts) and its timeouts.

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** The body of a GET of [url] as text; an answer other than 2xx is an [IOException]. */
    suspend fun getText(url: String, client: OkHttpClient = this.client, io: CoroutineDispatcher = Dispatchers.IO): String =
        withContext(io) {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GET $url answered ${response.code}")
                response.body?.string() ?: throw IOException("GET $url answered without a body")
            }
        }

    /** A body and the ETag it came with. */
    data class Tagged(val text: String, val etag: String?)

    /**
     * The body of a GET of [url] with its ETag, or null when the server says the one
     * kept, of [etag], hasn't changed; an answer other than 2xx or 304 is an [IOException].
     */
    suspend fun getIfChanged(url: String, etag: String?, client: OkHttpClient = this.client, io: CoroutineDispatcher = Dispatchers.IO): Tagged? =
        withContext(io) {
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@use null
                if (!response.isSuccessful) throw IOException("GET $url answered ${response.code}")
                Tagged(response.body?.string() ?: throw IOException("GET $url answered without a body"), response.header("ETag"))
            }
        }
}
