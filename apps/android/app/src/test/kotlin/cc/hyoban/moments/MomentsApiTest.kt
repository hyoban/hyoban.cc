package cc.hyoban.moments

import cc.hyoban.moments.data.api.MomentsApi
import cc.hyoban.moments.data.trips.TripRoutes
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every request to the API carries the owner's token, reads too: it answers none without one. */
class MomentsApiTest {
    private val answers = mapOf(
        "/v2/changes" to """{"revision":1,"entries":[],"deleted":[]}""",
        "/v2/routes" to """{"revision":1,"trips":{}}""",
    )
    private val seen = ConcurrentHashMap<String, String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
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
    fun stop() = server.stop(0)

    @Test
    fun `the feed and the routes are read with the token`() = runBlocking {
        val api = MomentsApi(baseUrl = { url }, token = { "secret" })
        api.entryChanges(0, cursor = null)
        TripRoutes({ url }, File.createTempFile("routes", ".json").apply { deleteOnExit() }, token = { "secret" })
            .refresh(apiRevision = 1)

        assertEquals(
            mapOf("/v2/changes" to "Bearer secret", "/v2/routes" to "Bearer secret"),
            seen.toMap(),
        )
    }
}
