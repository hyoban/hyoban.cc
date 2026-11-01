package cc.hyoban.moments.data.things

import cc.hyoban.moments.data.spec.Entries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

// The car's statistics as the Zeekr import normalised them (packages/data-spec/src/vehicle.ts):
// a month's report and days, and the cumulative assisted driving, read as far as the 车 page
// shows them. Only an import writes them; the phone only reads.

data class VehicleDay(val date: LocalDate, val distanceMeters: Long)

data class AssistedDriving(
    val distanceMeters: Long?,
    val nzpMeters: Long?,
    val nzpPercent: Long?,
    val accLccMeters: Long?,
    val accLccPercent: Long?,
    val parkingCount: Long?,
    val parkingAverageSeconds: Long?,
    val daysOwned: Long?,
    val beatsPercent: Long?,
)

data class VehicleMonth(
    val deviceId: String,
    val month: YearMonth,
    val capturedAt: OffsetDateTime?,
    val distanceMeters: Long?,
    val durationSeconds: Long?,
    val tripCount: Long?,
    val longestTripMeters: Long?,
    val longestTripSeconds: Long?,
    val energyWh: Long?,
    val whPer100Km: Long?,
    val distanceBeatsPercent: Long?,
    val days: List<VehicleDay>,
    val assisted: AssistedDriving?,
)

/** A charge of the car, from its entry. */
data class VehicleCharge(val uid: String, val at: OffsetDateTime, val type: String?, val energyWh: Long?, val durationMinutes: Long?)

object Vehicle {
    fun months(records: Collection<JsonObject>, deviceId: String): List<VehicleMonth> =
        records.mapNotNull(::month).filter { it.deviceId == deviceId }.sortedByDescending { it.month }

    /** The latest cumulative observation of the car's assisted driving. */
    fun assisted(records: Collection<JsonObject>, deviceId: String): AssistedDriving? =
        records.filter { it.text("deviceId") == deviceId }
            .maxByOrNull { it.text("capturedAt").orEmpty() }
            ?.let { (it["statistics"] as? JsonObject)?.let(::assistedOf) }

    /** The car's charges among the entries, newest first. */
    fun charges(entries: Collection<JsonObject>, deviceId: String): List<VehicleCharge> =
        entries.mapNotNull { entry ->
            val part = (entry["parts"] as? JsonObject)?.get("charge") as? JsonObject ?: return@mapNotNull null
            if (part.text("vehicle") != deviceId) return@mapNotNull null
            val at = entry.text("at")?.takeIf { it.length > 10 }?.let(OffsetDateTime::parse) ?: return@mapNotNull null
            VehicleCharge(entry.text("uid").orEmpty(), at, part.text("type"), part.long("energyWh"), Entries.chargeMinutes(part)?.toLong())
        }.sortedByDescending { it.at }

    private fun month(record: JsonObject): VehicleMonth? {
        val report = record["report"] as? JsonObject
        return VehicleMonth(
            deviceId = record.text("deviceId") ?: return null,
            month = YearMonth.parse(record.text("month") ?: return null),
            capturedAt = record.text("capturedAt")?.let(OffsetDateTime::parse),
            distanceMeters = report?.long("distanceMeters"),
            durationSeconds = report?.long("durationSeconds"),
            tripCount = report?.long("tripCount"),
            longestTripMeters = report?.long("longestTripMeters"),
            longestTripSeconds = report?.long("longestTripDurationSeconds"),
            energyWh = report?.long("energyWh"),
            whPer100Km = (report?.get("reportedConsumption") as? JsonObject)?.long("whPer100Km"),
            distanceBeatsPercent = report?.long("distanceBeatsPercent"),
            days = (record["days"] as? JsonArray).orEmpty().mapNotNull { day ->
                (day as? JsonObject)?.let { VehicleDay(LocalDate.parse(it.text("date") ?: return@mapNotNull null), it.long("distanceMeters") ?: 0) }
            },
            assisted = (record["assistedDriving"] as? JsonObject)?.let(::assistedOf),
        )
    }

    private fun assistedOf(statistics: JsonObject): AssistedDriving {
        val nzp = statistics["nzp"] as? JsonObject
        val accLcc = statistics["accLcc"] as? JsonObject
        return AssistedDriving(
            distanceMeters = statistics.long("distanceMeters"),
            nzpMeters = nzp?.long("distanceMeters"),
            nzpPercent = nzp?.long("reportedDistancePercent"),
            accLccMeters = accLcc?.long("distanceMeters"),
            accLccPercent = accLcc?.long("reportedDistancePercent"),
            parkingCount = statistics.long("parkingCount"),
            parkingAverageSeconds = statistics.long("parkingAverageSeconds"),
            daysOwned = statistics.long("daysOwned"),
            beatsPercent = statistics.long("beatsPercent"),
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
}
