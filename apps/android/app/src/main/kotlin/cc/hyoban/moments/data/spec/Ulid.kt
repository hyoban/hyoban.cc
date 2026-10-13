package cc.hyoban.moments.data.spec

import java.security.SecureRandom

/** ULIDs name records: 48 bits of time and 80 random bits in Crockford base32. */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    val PATTERN = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    fun new(time: Long = System.currentTimeMillis()): String {
        require(time in 0 until (1L shl 48)) { "Invalid ULID time: $time" }
        val builder = StringBuilder(26)
        var remaining = time
        repeat(10) {
            builder.insert(0, ALPHABET[(remaining % 32).toInt()])
            remaining /= 32
        }
        repeat(16) { builder.append(ALPHABET[random.nextInt(32)]) }
        return builder.toString()
    }

    /** The time a ULID was made for, in milliseconds since the epoch. */
    fun timeOf(id: String): Long {
        require(PATTERN.matches(id)) { "Invalid ULID: $id" }
        return id.take(10).fold(0L) { time, character -> time * 32 + ALPHABET.indexOf(character) }
    }
}
