package cc.hyoban.moments.nas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher

// What the UGREEN NAS's photo app (UGOS Pro's 相册) says and wants, apart from the network:
// its addresses from UGREENlink, the sign-in's RSA, and its photos and albums. Its API is
// the one its own web app uses, undocumented; README, 选照片 has what is known of it.

/** A photo or video in the NAS's photo app, as its lists give it. */
data class NasPhoto(
    val id: Long,
    val name: String,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val video: Boolean,
    /** A motion photo, its video inside the file (实况). */
    val live: Boolean,
    /** A video's length as the NAS writes it, 03:25. */
    val duration: String?,
    /** When it was taken, the time on the phone's clock then: the NAS keeps it so. */
    val taken: LocalDateTime?,
    val uploaded: Long,
    /** The album it is listed from, which its stream wants; none from the timeline. */
    val album: NasAlbum? = null,
) {
    val key get() = "nas/$id"
    val extension get() = name.substringAfterLast('.', "").lowercase()
    val mime: String get() = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic", "heif" -> "image/heic"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> if (video) "video/*" else "image/*"
    }
}

/** Where a photo was taken, as the NAS's map lists it: the photo by its id, and what its stream wants of it. */
data class NasSpot(val id: Long, val latitude: Double, val longitude: Double, val uploaded: Long)

/** An album of the NAS's photo app: its own, one kept by a rule, or one shared with the owner. */
data class NasAlbum(val uuid: String, val name: String, val type: Int, val count: Int?)

/** What a delete on the NAS does: move them to its recycle bin, or delete them for good where there is none. */
enum class NasDeleteKind { BIN, FOREVER }

object NasProtocol {
    /** Where UGREENlink says a NAS is, by its ID. */
    const val LOOKUP = "https://api.ugnas.com/api/p2p/v2/ta/nodeInfo/byAlias"

    /** The stream's sizes: the original, and the thumbnails the web app draws. */
    const val ORIGINAL = 0
    const val SMALL = 1
    const val BIG = 3

    /** The web app encrypts in chunks of this many characters, whatever the key's size. */
    private const val CHUNK = 117

    /**
     * The addresses to try for the NAS UGREENlink [lookup] describes, nearest first: its
     * names on the home network (UGREENlink's own DNS, with a certificate of its own), its
     * DDNS names, then UGREENlink's relay.
     */
    fun candidates(lookup: JsonObject): List<String> {
        val data = lookup.obj("data") ?: return emptyList()
        val net = data.obj("netInfo")
        val port = net?.int("httpsPort") ?: 9443
        val near = (net?.obj("smartdns") ?: net)?.let { (it.strings("lan") + it.strings("lanv6")) }.orEmpty()
        val ddns = net?.strings("ddns").orEmpty()
        val relay = listOfNotNull(data.string("alias")?.let { alias -> data.string("relayDomain")?.let { "https://$alias.$it" } })
        return (near + ddns).map { "https://$it:$port" } + relay
    }

    /** A public key as the NAS sends it: a PEM, itself in base64. */
    fun publicKey(base64Pem: String): PublicKey {
        val pem = String(Base64.getMimeDecoder().decode(base64Pem))
        val der = Base64.getDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim())
        // UGOS labels a SubjectPublicKeyInfo "RSA PUBLIC KEY"; a true one, the bare PKCS#1 key,
        // Java reads only inside a SubjectPublicKeyInfo, so it is wrapped when read as it is fails.
        val factory = KeyFactory.getInstance("RSA")
        return runCatching { factory.generatePublic(X509EncodedKeySpec(der)) }
            .getOrElse { factory.generatePublic(X509EncodedKeySpec(spki(der))) }
    }

    /** [pkcs1] in a SubjectPublicKeyInfo: rsaEncryption's identifier, then the key as a bit string. */
    private fun spki(pkcs1: ByteArray): ByteArray {
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val bits = der(0x03, byteArrayOf(0) + pkcs1)
        return der(0x30, algorithm + bits)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    /** The web app's encryptLong: [text] in 117-character chunks, each RSA (PKCS#1 v1.5), together in base64. */
    fun encrypt(text: String, key: PublicKey): String {
        val chunks = text.chunked(CHUNK).ifEmpty { listOf("") }
        val bytes = chunks.fold(ByteArray(0)) { all, chunk ->
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
            all + cipher.doFinal(chunk.toByteArray())
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The time a photo was taken: the NAS keeps the clock's reading as if it were UTC. */
    fun takenAt(seconds: Long?): LocalDateTime? =
        seconds?.takeIf { it > 0 }?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }

    /** The photos a timeline or an album's list holds. */
    fun photos(data: JsonObject?, album: NasAlbum? = null): List<NasPhoto> =
        data?.array("list")?.mapNotNull { (it as? JsonObject)?.let { item -> photo(item, album) } }.orEmpty()

    private fun photo(item: JsonObject, album: NasAlbum?): NasPhoto? {
        val id = item.long("picture_id") ?: item.long("id") ?: return null
        val name = item.string("file_name") ?: return null
        val video = item.int("show_type") == 1 || item.string("duration").orEmpty().isNotEmpty()
        return NasPhoto(
            id = id, name = name, size = item.long("size") ?: 0, width = item.int("width"), height = item.int("height"),
            video = video, live = !video && (item.int("livephoto_type") ?: 0) != 0,
            duration = item.string("duration")?.takeIf { it.isNotEmpty() },
            taken = takenAt(item.long("create_time_utc")), uploaded = item.long("upload_time") ?: 0, album = album,
        )
    }

    /** The places the map's list holds, those without a point left out; the NAS writes its coordinates as text. */
    fun spots(data: JsonObject?): List<NasSpot> = data?.array("list").orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.long("picture_id") ?: return@mapNotNull null
        val latitude = item.string("latitude")?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
        val longitude = item.string("longitude")?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        if (latitude == 0.0 && longitude == 0.0) return@mapNotNull null
        NasSpot(id, latitude, longitude, item.long("upload_time") ?: 0)
    }

    /** The albums an album list holds, those without a name or an id left out. */
    fun albums(data: JsonObject?): List<NasAlbum> =
        (data?.array("result") ?: data?.array("list")).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val uuid = item.string("album_uuid") ?: return@mapNotNull null
            val name = item.string("album_name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NasAlbum(uuid, name, item.int("album_type") ?: 1, item.int("picture_num") ?: item.int("total") ?: item.int("count"))
        }

    /** What the NAS said of a delete it was asked about. */
    fun deleteKind(data: JsonObject?): NasDeleteKind = if (data?.int("result") == 2) NasDeleteKind.FOREVER else NasDeleteKind.BIN

    /** How many of a delete's photos the NAS couldn't delete. */
    fun deleteMissed(data: JsonObject?): Int = data?.obj("error_info")?.int("fail_count") ?: 0

    internal fun JsonObject.obj(name: String) = this[name] as? JsonObject
    internal fun JsonObject.array(name: String) = this[name] as? JsonArray
    internal fun JsonObject.prim(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    internal fun JsonObject.string(name: String) = prim(name)?.contentOrNull
    internal fun JsonObject.int(name: String) = prim(name)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    internal fun JsonObject.long(name: String) = prim(name)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    private fun JsonObject.strings(name: String) = array(name)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
