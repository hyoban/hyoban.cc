package cc.hyoban.moments.backup

import cc.hyoban.moments.data.HttpException
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

// The few WebDAV requests a backup needs, over the app's HTTP client: make the directory,
// put a file in it, list it, fetch a file from it and delete from it. Nothing else of the
// server is read.

/** A file directly in the directory: its name, and its size in bytes when the server says it. */
data class DavFile(val name: String, val size: Long?)

/** A WebDAV directory at [directory], signed in as [user] with [password]. */
class WebDav(private val client: OkHttpClient, directory: String, user: String, password: String) {
    private val base: HttpUrl = "${directory.trimEnd('/')}/".toHttpUrl()
    private val authorization = Credentials.basic(user, password, Charsets.UTF_8)

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization)

    private fun file(name: String) = base.newBuilder().addPathSegment(name).build()

    private fun send(request: Request, ok: (Int) -> Boolean = { it in 200..299 }): String =
        client.newCall(request).execute().use { response ->
            if (!ok(response.code)) throw HttpException(response.code, "${request.method} ${request.url} answered ${response.code}")
            response.body?.string().orEmpty()
        }

    /** Makes the directory, one already there being as good: servers answer 405 for that. */
    fun makeDirectory() {
        send(request(base).method("MKCOL", null).build()) { it in 200..299 || it == 405 }
    }

    fun put(name: String, file: File) {
        send(request(file(name)).put(file.asRequestBody(ZIP)).build())
    }

    /** Fetches the file [name] into [into]. */
    fun get(name: String, into: File) {
        val request = request(file(name)).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, "GET ${request.url} answered ${response.code}")
            into.outputStream().use { response.body!!.byteStream().copyTo(it) }
        }
    }

    /** The files directly in the directory. */
    fun list(): List<DavFile> {
        val body: RequestBody = PROPFIND.toRequestBody(XML)
        val xml = send(request(base).method("PROPFIND", body).header("Depth", "1").build())
        return files(xml.byteInputStream(), base.encodedPath)
    }

    fun delete(name: String) {
        send(request(file(name)).delete().build()) { it in 200..299 || it == 404 }
    }

    companion object {
        private val ZIP = "application/zip".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

        /**
         * The files a PROPFIND's [xml] lists: each response's href, absolute or a path, its last
         * segment decoded, with its length, leaving out [directory] itself and the directories in it.
         */
        fun files(xml: InputStream, directory: String): List<DavFile> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(xml)
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            val own = directory.trimEnd('/')
            return (0 until responses.length).mapNotNull { index ->
                val response = responses.item(index) as Element
                val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: return@mapNotNull null
                val isDirectory = response.getElementsByTagNameNS("DAV:", "collection").length > 0
                val path = href.substringAfter("://").let { if (href.contains("://")) "/" + it.substringAfter('/', "") else it }
                if (isDirectory || path.trimEnd('/') == own) return@mapNotNull null
                val name = URLDecoder.decode(path.trimEnd('/').substringAfterLast('/'), "UTF-8").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val size = response.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull()
                DavFile(name, size)
            }
        }
    }
}
