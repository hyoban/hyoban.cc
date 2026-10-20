package cc.hyoban.moments.data.local

import java.security.SecureRandom

/**
 * ULIDs, the ids the site gives its Moments and their media: 26 characters of Crockford's
 * base 32, the time in milliseconds and then 80 random bits, so they sort by when they
 * were made.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun next(time: Long = System.currentTimeMillis()): String = of(time, ByteArray(10).also(random::nextBytes))

    fun of(time: Long, randomness: ByteArray): String {
        require(randomness.size == 10)
        val chars = CharArray(26)
        var t = time
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        // The 80 bits as sixteen groups of five, from the first byte's highest bit.
        var buffer = 0
        var bits = 0
        var index = 10
        for (byte in randomness) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                chars[index++] = ALPHABET[(buffer ushr bits) and 31]
            }
        }
        return String(chars)
    }
}
