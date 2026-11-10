package cc.hyoban.moments.camera

import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the a7C II speaks over Bluetooth LE, written from the protocol as public notes on Sony's
 * cameras describe it (see the README's Camera section): two services, one for the camera's
 * Wi‑Fi and status, one for the location it writes into its photos.
 */
internal object SonyBle {
    /** The services are in Sony's own base; their characteristics are short ids in the Bluetooth base. */
    private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    val CONTROL_SERVICE: UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")
    val LOCATION_SERVICE: UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")

    /** Written 0x01: the camera starts its own Wi‑Fi. */
    val WIFI_ON: UUID = uuid("CC08")
    /** The camera's network name and password, read once its Wi‑Fi is up. */
    val WIFI_SSID: UUID = uuid("CC06")
    val WIFI_PASSWORD: UUID = uuid("CC07")
    /** The status notifications the camera wants subscribed before it keeps its Wi‑Fi up. */
    val STATUS: List<UUID> = listOf("CC09", "CC10", "CC0F", "CC03", "CC0E").map { uuid(it) }

    /** Notified when the camera stops taking locations. */
    val LOCATION_STATUS: UUID = uuid("DD01")
    val LOCATION_DATA: UUID = uuid("DD11")
    val LOCATION_INFO: UUID = uuid("DD21")
    val LOCATION_LOCK: UUID = uuid("DD30")
    val LOCATION_ENABLE: UUID = uuid("DD31")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The write that holds the lock answers this when 位置信息链接 is off on the camera. */
    const val STATUS_LOCATION_OFF = 157
    /** … and this when another phone or app holds it. */
    const val STATUS_LOCATION_TAKEN = 159

    /** Whether the camera wants the time zone with each location ([LOCATION_INFO]'s fifth byte). */
    fun takesTimeZone(info: ByteArray?): Boolean = info != null && info.size > 4 && info[4].toInt() and 0x02 != 0

    /**
     * The location the camera writes into the photos it takes: degrees times 10⁷ and the fix's
     * UTC time, big-endian, in 91 bytes, or 95 with the zone's offset and its daylight saving
     * when the camera takes them.
     */
    fun locationPacket(latitude: Double, longitude: Double, time: Instant, zone: ZoneId, withZone: Boolean): ByteArray {
        val size = if (withZone) 95 else 91
        val out = ByteBuffer.allocate(size)
        out.putShort((size - 2).toShort())
        out.put(byteArrayOf(0x08, 0x02, 0xFC.toByte(), if (withZone) 0x03 else 0x00, 0x00, 0x00, 0x10, 0x10, 0x10))
        out.putInt(Math.round(latitude * 1e7).toInt())
        out.putInt(Math.round(longitude * 1e7).toInt())
        val utc = time.atOffset(ZoneOffset.UTC)
        out.putShort(utc.year.toShort())
        out.put(utc.monthValue.toByte()); out.put(utc.dayOfMonth.toByte())
        out.put(utc.hour.toByte()); out.put(utc.minute.toByte()); out.put(utc.second.toByte())
        if (withZone) {
            val rules = zone.rules
            val dst = rules.getDaylightSavings(time).toMinutes().toInt()
            val standard = rules.getStandardOffset(time).totalSeconds / 60
            out.position(91)
            out.putShort(standard.toShort())
            out.putShort(dst.toShort())
        }
        return out.array()
    }

    /** A network name or password as the camera hands it over: three bytes of header, then ASCII. */
    fun credential(value: ByteArray?): String? {
        if (value == null || value.size <= 3) return null
        return String(value, 3, value.size - 3, Charsets.US_ASCII).trimEnd('\u0000').takeIf { it.isNotEmpty() }
    }
}
