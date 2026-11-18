package cc.hyoban.moments

import cc.hyoban.moments.nas.NasAlbum
import cc.hyoban.moments.nas.NasDay
import cc.hyoban.moments.nas.NasDeleteKind
import cc.hyoban.moments.nas.NasProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64
import javax.crypto.Cipher

class NasTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

    /** The key as the NAS sends it: a PEM, itself in base64. */
    private fun sent(pem: String) = Base64.getEncoder().encodeToString(pem.toByteArray())

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    private fun decrypt(base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        return bytes.toList().chunked(256).joinToString("") { block ->
            String(Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }.doFinal(block.toByteArray()))
        }
    }

    @Test
    fun encryptsInTheWebAppsChunks() {
        val key = NasProtocol.publicKey(sent(pem("PUBLIC KEY", pair.public.encoded)))
        val long = "a".repeat(300)
        val sealed = NasProtocol.encrypt(long, key)
        // 300 characters are three chunks of at most 117, each a 256-byte block.
        assertEquals(3 * 256, Base64.getDecoder().decode(sealed).size)
        assertEquals(long, decrypt(sealed))
        assertEquals("pässwörd", decrypt(NasProtocol.encrypt("pässwörd", key)))
    }

    @Test
    fun readsABarePkcs1Key() {
        // A SubjectPublicKeyInfo's last element is the PKCS#1 key, after its bit string's header.
        val spki = pair.public.encoded
        val pkcs1 = spki.copyOfRange(spki.size - 270, spki.size)
        val key = NasProtocol.publicKey(sent(pem("RSA PUBLIC KEY", pkcs1)))
        assertEquals("token", decrypt(NasProtocol.encrypt("token", key)))
    }

    @Test
    fun readsASubjectPublicKeyInfoLabelledRsaPublicKey() {
        // As UGOS sends it.
        val key = NasProtocol.publicKey(sent(pem("RSA PUBLIC KEY", pair.public.encoded)))
        assertEquals("password", decrypt(NasProtocol.encrypt("password", key)))
    }

    @Test
    fun triesTheHomeNetworkThenDdnsThenTheRelay() {
        val lookup = json(
            """{"code":200,"data":{"alias":"someone","relayDomain":"cn1.ug.link","netInfo":{
              "smartdns":{"lan":["10-0-0-2.someone.lan.ug.link"]},"ipv4":["10.0.0.2"],"ipv6":[],
              "ddns":["nas.example.com"],"httpsPort":9443}}}""",
        )
        assertEquals(
            listOf("https://10-0-0-2.someone.lan.ug.link:9443", "https://nas.example.com:9443", "https://someone.cn1.ug.link"),
            NasProtocol.candidates(lookup),
        )
        assertEquals(emptyList<String>(), NasProtocol.candidates(json("""{"code":404}""")))
    }

    @Test
    fun readsPhotosVideosAndMotionPhotos() {
        val data = json(
            """{"list":[
              {"picture_id":1,"file_name":"IMG_0001.jpg","width":4000,"height":3000,"size":1000,"show_type":2,"duration":"","create_time_utc":1700000000,"upload_time":5,"livephoto_type":2},
              {"picture_id":2,"file_name":"VID_0002.mp4","width":1920,"height":1080,"size":2000,"show_type":1,"duration":"03:25","create_time_utc":0,"upload_time":6,"livephoto_type":0},
              {"picture_id":3,"file_name":"IMG_0003.heic","size":3000,"show_type":0,"livephoto_type":0},
              {"file_name":"no id.jpg"}
            ],"total":3}""",
        )
        val album = NasAlbum("uuid-1", "Trip", 2, 3)
        val (photo, video, heic) = NasProtocol.photos(data, album)
        assertEquals(3, NasProtocol.photos(data).size)
        assertTrue(photo.live)
        assertFalse(photo.video)
        assertEquals("image/jpeg", photo.mime)
        assertEquals(album, photo.album)
        // The clock's reading kept as if it were UTC.
        assertEquals(LocalDateTime.of(2023, 11, 14, 22, 13, 20), photo.taken)
        assertTrue(video.video)
        assertFalse(video.live)
        assertEquals("03:25", video.duration)
        assertNull(video.taken)
        assertEquals("image/heic", heic.mime)
        assertEquals("nas/3", heic.key)
    }

    @Test
    fun readsTheTimelinesDaysNewestFirst() {
        val days = NasProtocol.days(json(
            """{"list":[{"year":2024,"month":12,"day":31,"total":3},{"year":2025,"month":1,"day":2,"total":0},""" +
                """{"year":2025,"month":2,"day":30,"total":1},{"year":"2025","month":"3","day":"1","total":"5"}]}""",
        ))
        assertEquals(listOf(NasDay(LocalDate.of(2025, 3, 1), 5), NasDay(LocalDate.of(2024, 12, 31), 3)), days)
        assertEquals(emptyList<NasDay>(), NasProtocol.days(null))
    }

    @Test
    fun readsAlbumsAndWhatADeleteDoes() {
        val albums = NasProtocol.albums(json("""{"result":[{"album_uuid":"a","album_name":"One","album_type":1,"picture_num":4},{"album_uuid":"b","album_name":""}]}"""))
        assertEquals(listOf(NasAlbum("a", "One", 1, 4)), albums)
        assertEquals(NasDeleteKind.BIN, NasProtocol.deleteKind(json("""{"result":1}""")))
        assertEquals(NasDeleteKind.FOREVER, NasProtocol.deleteKind(json("""{"result":2}""")))
        assertEquals(2, NasProtocol.deleteMissed(json("""{"error_info":{"fail_count":2}}""")))
        assertEquals(0, NasProtocol.deleteMissed(JsonObject(emptyMap())))
        assertEquals("098f6bcd4621d373cade4e832627b4f6", NasProtocol.md5("test"))
    }

    @Test
    fun keepsADaysPhotosAndTheMapsPlacesAsTheyWereRead() {
        val photos = listOf(
            cc.hyoban.moments.nas.NasPhoto(1, "IMG_1.HEIC", 2048, 4000, 3000, video = false, live = true, duration = null, taken = LocalDateTime.of(2025, 3, 8, 14, 5, 9), uploaded = 7),
            cc.hyoban.moments.nas.NasPhoto(2, "VID_2.MP4", 9, null, null, video = true, live = false, duration = "00:12", taken = null, uploaded = 8),
        )
        assertEquals(photos, NasProtocol.photosFrom(NasProtocol.photosText(photos)))
        val spots = listOf(cc.hyoban.moments.nas.NasSpot(3, 31.2304, 121.4737, 9))
        assertEquals(spots, NasProtocol.spotsFrom(NasProtocol.spotsText(spots)))
    }
}
