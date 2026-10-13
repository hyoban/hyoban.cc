package cc.hyoban.moments.data.spec

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Times as the spec writes them: `2026-10-01T17:40:00+08:00`, in the phone's zone. */
object Timestamps {
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun now(): String = format(OffsetDateTime.now())

    fun format(time: OffsetDateTime): String = time.truncatedTo(ChronoUnit.SECONDS).format(formatter)

    fun at(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), zone))

    /** Milliseconds since the epoch of a timestamp or, for a day, its midnight in the phone's zone. */
    fun toEpochMillis(occurredAt: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        if (occurredAt.length > 10) {
            OffsetDateTime.parse(occurredAt).toInstant().toEpochMilli()
        } else {
            java.time.LocalDate.parse(occurredAt).atStartOfDay(zone).toInstant().toEpochMilli()
        }
}
