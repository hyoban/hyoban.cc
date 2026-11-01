package cc.hyoban.moments.data.things

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.Visibility
import cc.hyoban.moments.data.spec.entryUid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// The devices and subscriptions (packages/data-spec's `device` and `subscription` parts), each
// on an entry of its own: a device on the day it was bought, a subscription on the day it was
// last paid, or for software bought once the day it was first written down. Read as far as the
// 设备 and 订阅 pages need, and written back over the entry as it came, so what this version
// doesn't know of it stays.

/** Where a device stands: used, kept beside a newer one of its kind, not used, or sold. */
enum class DeviceStatus { IN_USE, BACKUP, IDLE, SOLD }

data class Device(
    val entry: JsonObject,
    val id: String,
    val name: String,
    val category: String,
    val bought: LocalDate,
    val price: Double,
    val soldOn: LocalDate?,
    val soldPrice: Double?,
    val idle: Boolean,
    val image: String?,
    val note: String?,
    val private: Boolean,
) {
    val uid: String get() = entry.entryUid

    /** Days owned, at least one: to the day it sold, else [today], as the site counts them. */
    fun days(today: LocalDate): Long = maxOf(1, ChronoUnit.DAYS.between(bought, soldOn ?: today))

    /** What owning it cost a day: the price less what it sold for, over the days owned. */
    fun dailyCost(today: LocalDate): Double = (price - (soldPrice ?: 0.0)) / days(today)
}

enum class Period(val value: String) {
    YEAR("year"),
    MONTH("month"),
    ONCE("once");

    companion object {
        fun of(value: String?): Period = entries.firstOrNull { it.value == value } ?: ONCE
    }
}

data class Subscription(
    val entry: JsonObject,
    val name: String,
    val link: String?,
    val price: Double,
    val currency: String,
    val period: Period,
    /** The day it was last paid, or for one bought once, first written down. */
    val paid: LocalDate,
    val renews: LocalDate?,
    val autoRenew: Boolean,
    val paidWith: String?,
    val ended: LocalDate?,
    val note: String?,
    val private: Boolean,
) {
    val uid: String get() = entry.entryUid

    /** When it next renews or runs out: one that renews by itself goes on from its day. */
    fun next(today: LocalDate): LocalDate? {
        var day = renews ?: return null
        if (!autoRenew || ended != null) return day
        while (day < today) day = if (period == Period.MONTH) day.plusMonths(1) else day.plusYears(1)
        return day
    }

    /** Ran out on its day without being renewed, and wasn't stopped on purpose. */
    fun lapsed(today: LocalDate): Boolean = ended == null && !autoRenew && renews != null && renews < today
}

object Things {
    /** The devices among [entries], newest bought first. */
    fun devices(entries: Collection<JsonObject>): List<Device> =
        entries.mapNotNull(::device).sortedWith(compareByDescending<Device> { it.bought }.thenBy { it.name })

    /** The subscriptions among [entries], in the order they were written down. */
    fun subscriptions(entries: Collection<JsonObject>): List<Subscription> =
        entries.mapNotNull(::subscription).sortedWith(compareBy<Subscription> { it.entry.int("order") ?: Int.MAX_VALUE }.thenBy { it.paid })

    /** Each device's standing, a backup being one still used while a newer one of its kind is. */
    fun status(device: Device, all: List<Device>): DeviceStatus = when {
        device.soldOn != null -> DeviceStatus.SOLD
        device.idle -> DeviceStatus.IDLE
        all.any { it !== device && it.category == device.category && it.soldOn == null && !it.idle && it.bought > device.bought } -> DeviceStatus.BACKUP
        else -> DeviceStatus.IN_USE
    }

    fun device(entry: JsonObject): Device? {
        val part = entry.part("device") ?: return null
        return Device(
            entry = entry,
            id = part.text("id") ?: return null,
            name = part.text("name") ?: return null,
            category = part.text("category").orEmpty(),
            bought = LocalDate.parse(entry.text("at")?.take(10) ?: return null),
            price = part.number("price") ?: 0.0,
            soldOn = (part["sold"] as? JsonObject)?.text("on")?.let(LocalDate::parse),
            soldPrice = (part["sold"] as? JsonObject)?.number("price"),
            idle = part.flag("idle"),
            image = part.text("image"),
            note = part.text("note"),
            private = Entries.isPrivate(entry),
        )
    }

    fun subscription(entry: JsonObject): Subscription? {
        val part = entry.part("subscription") ?: return null
        return Subscription(
            entry = entry,
            name = part.text("name") ?: return null,
            link = part.text("link"),
            price = part.number("price") ?: 0.0,
            currency = part.text("currency") ?: "CNY",
            period = Period.of(part.text("period")),
            paid = LocalDate.parse(entry.text("at")?.take(10) ?: return null),
            renews = part.text("renews")?.let(LocalDate::parse),
            autoRenew = part.flag("autoRenew"),
            paidWith = part.text("paidWith"),
            ended = part.text("ended")?.let(LocalDate::parse),
            note = part.text("note"),
            private = Entries.isPrivate(entry),
        )
    }

    /** A device as an entry: [base]'s, with what the page edits written over it; a new one gets its uid from the day it was bought and its id. */
    fun deviceEntry(base: JsonObject?, edit: DeviceEdit, now: String): JsonObject {
        val id = edit.id
        val part = buildJsonObject {
            (base?.part("device") ?: JsonObject(emptyMap())).forEach { (key, value) -> put(key, value) }
            put("v", 1)
            put("id", id)
            put("name", edit.name)
            put("category", edit.category)
            put("price", edit.price)
            if (edit.soldOn != null) {
                put("sold", buildJsonObject {
                    put("on", edit.soldOn.toString())
                    put("price", edit.soldPrice ?: 0.0)
                })
            }
            if (edit.idle) put("idle", true)
            edit.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }.withoutAbsent(
            "sold".takeIf { edit.soldOn == null },
            "idle".takeIf { !edit.idle },
            "note".takeIf { edit.note.isNullOrBlank() },
        )
        val at = edit.bought.toString()
        return entryOf(base, Entries.stableUlid(at, "device:$id"), at, "device", part, edit.private, now)
    }

    /** A subscription as an entry, as [deviceEntry] does a device. */
    fun subscriptionEntry(base: JsonObject?, edit: SubscriptionEdit, now: String): JsonObject {
        val part = buildJsonObject {
            (base?.part("subscription") ?: JsonObject(emptyMap())).forEach { (key, value) -> put(key, value) }
            put("v", 1)
            put("name", edit.name)
            put("price", edit.price)
            put("currency", edit.currency)
            put("period", edit.period.value)
            edit.renews?.let { put("renews", it.toString()) }
            if (edit.autoRenew) put("autoRenew", true)
            edit.paidWith?.takeIf { it.isNotBlank() }?.let { put("paidWith", it) }
            edit.ended?.let { put("ended", it.toString()) }
            edit.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }.withoutAbsent(
            "renews".takeIf { edit.renews == null },
            "autoRenew".takeIf { !edit.autoRenew },
            "paidWith".takeIf { edit.paidWith.isNullOrBlank() },
            "ended".takeIf { edit.ended == null },
            "note".takeIf { edit.note.isNullOrBlank() },
        )
        val at = edit.paid.toString()
        val uid = base?.entryUid ?: Entries.stableUlid(at, "subscription:${edit.name}:${System.nanoTime()}")
        return entryOf(base, uid, at, "subscription", part, edit.private, now)
    }

    /** A kebab-case id from a name, as the spec's ids are; a name with no letters of that kind takes [fallback]. */
    fun idOf(name: String, fallback: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { fallback }

    private fun entryOf(base: JsonObject?, uid: String, at: String, plugin: String, part: JsonObject, private: Boolean, now: String) =
        buildJsonObject {
            base?.forEach { (key, value) -> put(key, value) }
            put("uid", base?.entryUid ?: uid)
            put("at", at)
            put("parts", buildJsonObject {
                (base?.get("parts") as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                put(plugin, part)
            })
            if (private) put("visibility", Visibility.PRIVATE.value)
            if (base == null) put("createdAt", now)
            put("updatedAt", now)
        }.withoutAbsent("visibility".takeIf { !private })

    private fun JsonObject.withoutAbsent(vararg keys: String?): JsonObject = JsonObject(this - keys.filterNotNull().toSet())

    private fun JsonObject.part(plugin: String): JsonObject? = (this["parts"] as? JsonObject)?.get(plugin) as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

data class DeviceEdit(
    val id: String,
    val name: String,
    val category: String,
    val bought: LocalDate,
    val price: Double,
    val soldOn: LocalDate?,
    val soldPrice: Double?,
    val idle: Boolean,
    val note: String?,
    val private: Boolean,
)

data class SubscriptionEdit(
    val name: String,
    val price: Double,
    val currency: String,
    val period: Period,
    val paid: LocalDate,
    val renews: LocalDate?,
    val autoRenew: Boolean,
    val paidWith: String?,
    val ended: LocalDate?,
    val note: String?,
    val private: Boolean,
)
