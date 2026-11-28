package cc.hyoban.moments

import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.trips.TripRoutes
import com.sun.net.httpserver.HttpServer
import cc.hyoban.moments.data.api.UploadPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every request to the API carries the owner's token, reads too: it answers none without one. */
class MomentsApiTest {
    private val answers = mapOf(
        "/v2/rows" to """{"revision":1,"rows":[]}""",
        "/v2/routes" to """{"revision":1,"trips":{}}""",
    )
    private val seen = ConcurrentHashMap<String, String>()
    /** Reached once an upload is with the server, which then holds it as a stalled link would. */
    private val uploading = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/v2/entries/") { exchange ->
            uploading.countDown()
            released.await(30, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            seen[path] = exchange.requestHeaders.getFirst("Authorization") ?: ""
            val body = (answers[path] ?: """{"error":"Not found."}""").toByteArray()
            exchange.sendResponseHeaders(if (path in answers) 200 else 404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() {
        released.countDown()
        server.stop(0)
    }

    @Test
    fun `the rows and the routes are read with the token`() = runBlocking {
        val api = MomentsApi(baseUrl = { url }, token = { "secret" })
        api.rows(0, cursor = null)
        TripRoutes({ url }, File.createTempFile("routes", ".json").apply { deleteOnExit() }, token = { "secret" })
            .refresh(apiRevision = 1)

        assertEquals(
            mapOf("/v2/rows" to "Bearer secret", "/v2/routes" to "Bearer secret"),
            seen.toMap(),
        )
    }

    @Test
    fun `an upload stops as soon as whoever waits for it is cancelled`() = runBlocking {
        val api = MomentsApi(baseUrl = { url }, token = { "secret" })
        val file = File.createTempFile("original", ".jpg").apply {
            deleteOnExit()
            writeBytes(ByteArray(64 * 1024))
        }
        val upload = launch(Dispatchers.IO) { api.upload("m", "a", UploadPart.IMAGE, file, "image/jpeg") }
        assertTrue(uploading.await(10, TimeUnit.SECONDS), "the upload reached the server")
        val stopped = withTimeoutOrNull(2_000) { upload.cancelAndJoin() }
        assertNotNull(stopped, "a cancelled upload doesn't go on for its link's timeout")
    }
}
