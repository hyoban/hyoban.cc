package cc.hyoban.moments.data.media

import java.io.File
import java.io.RandomAccessFile

/**
 * Where a photo or video was taken, as its file says it, taken out before it leaves the
 * phone, as @hyoban/data-spec/location does for the API: the GPS of its EXIF (JPEG, HEIC and
 * AVIF, WebP, PNG), the coordinates of its XMP, and the location boxes of an MP4 or
 * QuickTime file, or of the video a motion photo carries after its still. Each is blanked
 * where it is, so the file keeps its size, its pixels, its orientation and the rest of its
 * metadata. The two are kept the same; the API turns down an image that still has any.
 */
object Location {
    /** Bytes to write over a file at an offset, to blank one record of where it was taken. */
    data class Edit(val at: Long, val bytes: ByteArray)

    /**
     * [file] as it may leave the phone: itself when it says nothing of where it was taken,
     * else a copy without it in [directory], which the caller deletes once it is sent.
     */
    fun stripped(file: File, video: Boolean, directory: File? = null): File {
        val edits = if (video) {
            RandomAccessFile(file, "r").use { findInBoxes(FileBytes(it), 0, it.length()) }
        } else {
            find(file.readBytes())
        }
        if (edits.isEmpty()) return file

        val copy = File.createTempFile("public-", ".${file.extension}", directory)
        file.copyTo(copy, overwrite = true)
        RandomAccessFile(copy, "rw").use { out ->
            for (edit in edits) {
                out.seek(edit.at)
                out.write(edit.bytes)
            }
        }
        return copy
    }

    /** The edits that blank everything in an image's [file] that says where it was taken. */
    fun find(file: ByteArray): List<Edit> {
        val bytes = ArrayBytes(file)
        val boxes = indicesOf(file, FTYP).map { it - 4 }
            .filter { it >= 0 && bytes.u32(it.toLong()) >= 8 }
            .flatMap { findInBoxes(bytes, it.toLong(), file.size.toLong()) }

        return findExifGps(file) + findXmpGps(file) + boxes
    }

    /** Blanks, in place, everything in an image's [file] that says where it was taken; how many records it blanked. */
    fun strip(file: ByteArray): Int {
        val edits = find(file)
        for (edit in edits) edit.bytes.copyInto(file, edit.at.toInt())
        return edits.size
    }

    private val EXIF = ascii("Exif\u0000\u0000")
    private val TIFF_LITTLE = ascii("II*\u0000")
    private val TIFF_BIG = ascii("MM\u0000*")
    private val PNG_EXIF = ascii("eXIf")
    private val WEBP_EXIF = ascii("EXIF")
    private const val GPS_IFD = 0x8825
    /** Bytes per value of each TIFF field type, by its number. */
    private val TYPE_SIZES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

    /**
     * The EXIF blocks: after `Exif\0\0` in a JPEG's APP1, a HEIC's Exif item or a WebP's
     * chunk, or straight after a PNG's eXIf chunk type or a WebP's EXIF chunk header. In each,
     * the GPS IFD's values and then the IFD itself are zeroed, leaving an IFD of no fields.
     */
    private fun findExifGps(file: ByteArray): List<Edit> {
        val starts = buildSet {
            indicesOf(file, EXIF).forEach { add(it + EXIF.size) }
            indicesOf(file, PNG_EXIF).forEach { add(it + PNG_EXIF.size) }
            indicesOf(file, WEBP_EXIF).forEach { add(it + WEBP_EXIF.size + 4) }
        }
        return starts.filter { isAt(file, TIFF_LITTLE, it) || isAt(file, TIFF_BIG, it) }
            .flatMap { findTiffGps(file, it) }
    }

    private fun findTiffGps(file: ByteArray, tiff: Int): List<Edit> {
        val little = file[tiff] == 0x49.toByte()
        fun inside(at: Long, length: Long) = at >= 0 && at + length <= file.size
        fun u16(at: Long): Int {
            val a = file[at.toInt()].toInt() and 0xff
            val b = file[at.toInt() + 1].toInt() and 0xff
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(at: Long): Long = if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        else (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        if (!inside(tiff.toLong(), 8)) return emptyList()
        val ifd0 = tiff + u32(tiff + 4L)
        if (!inside(ifd0, 2)) return emptyList()
        val fields = u16(ifd0)
        if (!inside(ifd0 + 2, fields * 12L)) return emptyList()

        for (index in 0 until fields) {
            val field = ifd0 + 2 + index * 12L
            if (u16(field) != GPS_IFD) continue

            val gps = tiff + u32(field + 8)
            if (!inside(gps, 2)) return emptyList()
            val count = u16(gps)
            val length = 2 + count * 12L + 4
            // Already blank, or not an IFD this file holds.
            if (count == 0 || !inside(gps, length)) return emptyList()

            val edits = mutableListOf<Edit>()
            for (entry in 0 until count) {
                val at = gps + 2 + entry * 12L
                val size = TYPE_SIZES.getOrElse(u16(at + 2)) { 1 } * u32(at + 4)
                val value = tiff + u32(at + 8)
                // A value of more than four bytes is kept elsewhere, at the offset the field gives.
                if (size > 4 && inside(value, size)) edits += Edit(value, ByteArray(size.toInt()))
            }
            return edits + Edit(gps, ByteArray(length.toInt()))
        }
        return emptyList()
    }

    private val XMP_START = ascii("<x:xmpmeta")
    private val XMP_END = ascii("</x:xmpmeta>")
    /** An `exif:GPS…` property's value, as an attribute in either quotes or as an element. */
    private val XMP_GPS = listOf(
        Regex("""\bexif:GPS[A-Za-z]*\s*=\s*"(?<value>[^"]*)""""),
        Regex("""\bexif:GPS[A-Za-z]*\s*=\s*'(?<value>[^']*)'"""),
        Regex("""<exif:(?<name>GPS[A-Za-z]*)\b[^>]*>(?<value>[^<]*)</exif:\k<name>>"""),
    )

    /** The XMP packets' `exif:GPS…` values, as spaces of the same length, which keeps each well formed. */
    private fun findXmpGps(file: ByteArray): List<Edit> = indicesOf(file, XMP_START).flatMap { start ->
        val end = indexOf(file, XMP_END, start)
        if (end < 0) return@flatMap emptyList()
        val packet = String(file, start, end - start, Charsets.ISO_8859_1)

        XMP_GPS.flatMap { pattern ->
            pattern.findAll(packet).mapNotNull { match ->
                val value = match.groups["value"]!!
                if (value.value.isBlank()) null
                else Edit((start + value.range.first).toLong(), ByteArray(value.value.length) { 0x20 })
            }
        }
    }

    private val FTYP = ascii("ftyp")
    private val FREE = ascii("free")
    /** The boxes that hold others on the way to a location: QuickTime's and ISO's metadata. */
    private val CONTAINERS = setOf("moov", "trak", "udta")
    /** The boxes that are only a location: QuickTime's `©xyz` and 3GPP's `loci`. */
    private val LOCATIONS = setOf("©xyz", "loci")
    /** The metadata keys Apple's cameras keep a location under. */
    private val LOCATION_KEYS = setOf(
        "com.apple.quicktime.location.ISO6709",
        "com.apple.quicktime.location.body",
        "com.apple.quicktime.location.name",
        "com.apple.quicktime.location.note",
        "com.apple.quicktime.location.role",
        "com.apple.quicktime.location.date",
    )

    /** What a box walk reads: a whole image in memory, or a video read box by box from its file. */
    private interface Bytes {
        fun read(at: Long, length: Int): ByteArray
        fun u32(at: Long): Long = read(at, 4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    }

    private class ArrayBytes(private val file: ByteArray) : Bytes {
        override fun read(at: Long, length: Int): ByteArray =
            if (at < 0 || at + length > file.size) ByteArray(length) else file.copyOfRange(at.toInt(), at.toInt() + length)
    }

    private class FileBytes(private val file: RandomAccessFile) : Bytes {
        override fun read(at: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            if (at < 0 || at + length > file.length()) return bytes
            file.seek(at)
            file.readFully(bytes)
            return bytes
        }
    }

    private class Box(val type: String, val body: Long, val next: Long)

    /** A box's type, where its contents start and where the next box starts; null when it doesn't fit. */
    private fun readBox(bytes: Bytes, at: Long, end: Long): Box? {
        var size = bytes.u32(at)
        var body = at + 8
        if (size == 1L) {
            if (at + 16 > end) return null
            size = (bytes.u32(at + 8) shl 32) or bytes.u32(at + 12)
            body = at + 16
        } else if (size == 0L) {
            size = end - at
        }
        val next = at + size
        if (size < body - at || next > end) return null
        return Box(String(bytes.read(at + 4, 4), Charsets.ISO_8859_1), body, next)
    }

    /**
     * The location boxes among those from [start] to [end], and those they hold: a location
     * box becomes a `free` box of zeros, and a metadata item under a location key has its
     * value zeroed.
     */
    private fun findInBoxes(bytes: Bytes, start: Long, end: Long): List<Edit> {
        val edits = mutableListOf<Edit>()
        var at = start
        var keys: List<String>? = null
        var items: Box? = null

        while (at + 8 <= end) {
            val box = readBox(bytes, at, end) ?: break
            when {
                box.type in LOCATIONS -> {
                    edits += Edit(at + 4, FREE)
                    edits += Edit(box.body, ByteArray((box.next - box.body).toInt()))
                }
                // ISO's meta is a full box, its children after a version and flags; QuickTime's isn't.
                box.type == "meta" -> edits += findInBoxes(bytes, if (bytes.u32(box.body) == 0L) box.body + 4 else box.body, box.next)
                box.type in CONTAINERS -> edits += findInBoxes(bytes, box.body, box.next)
                box.type == "keys" -> keys = readKeys(bytes, box)
                box.type == "ilst" -> items = box
            }
            at = box.next
        }

        val list = items
        if (keys != null && list != null) edits += findKeyedLocations(bytes, keys, list)
        return edits
    }

    /** The names of a QuickTime `keys` box, by their index from 1 (the first is at 0). */
    private fun readKeys(bytes: Bytes, box: Box): List<String> {
        val count = bytes.u32(box.body + 4)
        val keys = mutableListOf<String>()
        var at = box.body + 8
        while (keys.size < count && at + 8 <= box.next) {
            val size = bytes.u32(at)
            if (size < 8 || at + size > box.next) break
            keys += String(bytes.read(at + 8, (size - 8).toInt()), Charsets.UTF_8)
            at += size
        }
        return keys
    }

    /** The `data` boxes' values of the `ilst` items under a location key, zeroed; those already zero are left. */
    private fun findKeyedLocations(bytes: Bytes, keys: List<String>, items: Box): List<Edit> {
        val edits = mutableListOf<Edit>()
        var at = items.body
        while (at + 8 <= items.next) {
            val item = readBox(bytes, at, items.next) ?: break
            val key = keys.getOrNull((bytes.u32(at + 4) - 1).toInt())
            if (key in LOCATION_KEYS) {
                var child = item.body
                while (child + 8 <= item.next) {
                    val data = readBox(bytes, child, item.next) ?: break
                    // A data box's value comes after its type and locale.
                    val length = (data.next - data.body - 8).toInt()
                    if (data.type == "data" && length > 0 && bytes.read(data.body + 8, length).any { it != 0.toByte() }) {
                        edits += Edit(data.body + 8, ByteArray(length))
                    }
                    child = data.next
                }
            }
            at = item.next
        }
        return edits
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    private fun isAt(file: ByteArray, pattern: ByteArray, at: Int): Boolean {
        if (at < 0 || at + pattern.size > file.size) return false
        for (index in pattern.indices) if (file[at + index] != pattern[index]) return false
        return true
    }

    private fun indexOf(file: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        for (at in from..file.size - pattern.size) if (file[at] == pattern[0] && isAt(file, pattern, at)) return at
        return -1
    }

    private fun indicesOf(file: ByteArray, pattern: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        var at = indexOf(file, pattern)
        while (at >= 0) {
            found += at
            at = indexOf(file, pattern, at + 1)
        }
        return found
    }
}
