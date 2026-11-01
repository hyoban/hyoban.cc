package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Spec
import cc.hyoban.moments.data.things.DeviceEdit
import cc.hyoban.moments.data.things.DeviceStatus
import cc.hyoban.moments.data.things.Period
import cc.hyoban.moments.data.things.SubscriptionEdit
import cc.hyoban.moments.data.things.Things
import cc.hyoban.moments.data.things.Vehicle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import cc.hyoban.moments.ui.feed.Charge
import cc.hyoban.moments.ui.feed.DayItem
import cc.hyoban.moments.ui.feed.ThingRecord
import cc.hyoban.moments.ui.feed.dayItems

/** The devices, subscriptions and the car's statistics, as the 设备, 订阅 and 车 pages read and write them. */
class ThingsTest {
    private fun json(text: String): JsonObject = Spec.json.parseToJsonElement(text).jsonObject

    private val phone = json(
        """{"uid":"01HBF5Q2H0AAAAAAAAAAAAAAAA","at":"2023-09-27","parts":{"device":{"v":1,"id":"iphone-15","name":"iPhone 15","category":"手机","price":5999,"sold":{"on":"2026-09-01","price":2069.5},"later":true}},"published":{"at":"2026-11-01T00:30:00Z"},"createdAt":"2026-11-01T00:30:00Z","updatedAt":"2026-11-01T00:30:00Z"}""",
    )
    private val newer = json(
        """{"uid":"01K5ZQ6Y00AAAAAAAAAAAAAAAA","at":"2026-09-23","parts":{"device":{"v":1,"id":"oppo","name":"OPPO","category":"手机","price":7199}},"createdAt":"2026-11-01T00:30:00Z","updatedAt":"2026-11-01T00:30:00Z"}""",
    )
    private val older = json(
        """{"uid":"01EZNHB900AAAAAAAAAAAAAAAA","at":"2021-03-01","parts":{"device":{"v":1,"id":"xiaomi-10","name":"小米 10","category":"手机","price":3999}},"createdAt":"2026-11-01T00:30:00Z","updatedAt":"2026-11-01T00:30:00Z"}""",
    )

    @Test
    fun `a device costs a day what it lost over the days it was owned, and stands beside the newer ones of its kind`() {
        val devices = Things.devices(listOf(phone, newer, older))
        val sold = devices.first { it.id == "iphone-15" }
        assertEquals(1070, sold.days(LocalDate.of(2026, 11, 1)))
        assertEquals((5999 - 2069.5) / 1070, sold.dailyCost(LocalDate.of(2026, 11, 1)))
        assertEquals(listOf("oppo", "iphone-15", "xiaomi-10"), devices.map { it.id })
        assertEquals(DeviceStatus.SOLD, Things.status(sold, devices))
        assertEquals(DeviceStatus.IN_USE, Things.status(devices.first { it.id == "oppo" }, devices))
        assertEquals(DeviceStatus.BACKUP, Things.status(devices.first { it.id == "xiaomi-10" }, devices))
    }

    @Test
    fun `a charge's time is read from its seconds, else from the minutes written before them`() {
        val charge = { part: String ->
            json("""{"uid":"01J00000000000000000000000","at":"2026-03-02T10:00+08:00","parts":{"charge":{"v":1,"vehicle":"car",$part}}}""")
        }
        assertEquals(25L, Vehicle.charges(listOf(charge("\"durationSeconds\":1500,\"durationMin\":30")), "car").single().durationMinutes)
        assertEquals(30L, Vehicle.charges(listOf(charge("\"durationMin\":30")), "car").single().durationMinutes)
        assertNull(Vehicle.charges(listOf(charge("\"energyWh\":1000")), "car").single().durationMinutes)
    }

    @Test
    fun `a record is stamped in UTC to the second`() {
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(cc.hyoban.moments.data.local.recordTime()))
    }

    @Test
    fun `a device written back keeps what this version doesn't know and drops what was taken off`() {
        val device = Things.device(phone)!!
        val edited = Things.deviceEntry(
            phone,
            DeviceEdit(device.id, "iPhone 15 128GB", "手机", device.bought, 5999.0, null, null, idle = true, note = "", private = true),
            "2026-11-02T00:00:00Z",
        )
        val part = edited.getValue("parts").jsonObject.getValue("device").jsonObject
        assertEquals(phone["uid"], edited["uid"])
        assertEquals("true", part["later"].toString())
        assertNull(part["sold"])
        assertEquals("true", part["idle"].toString())
        assertEquals("\"private\"", edited["visibility"].toString())
        assertEquals(phone["published"], edited["published"])
        assertEquals("\"2026-11-02T00:00:00Z\"", edited["updatedAt"].toString())
    }

    @Test
    fun `a subscription that renews by itself goes on from its day, one that doesn't runs out`() {
        val folo = Things.subscriptionEntry(
            null,
            SubscriptionEdit("Folo Plus", 79.99, "USD", Period.YEAR, LocalDate.of(2025, 11, 14), LocalDate.of(2026, 11, 14), false, "A", null, "", false),
            "2026-11-01T00:00:00Z",
        )
        val chatgpt = Things.subscriptionEntry(
            null,
            SubscriptionEdit("ChatGPT Plus", 200.0, "USD", Period.MONTH, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 9, 5), true, "B", null, "", false),
            "2026-11-01T00:00:00Z",
        )
        val today = LocalDate.of(2026, 11, 20)
        val (first, second) = Things.subscriptions(listOf(folo, chatgpt))
        assertEquals(LocalDate.of(2026, 11, 14), first.next(today))
        assertTrue(first.lapsed(today))
        assertEquals(LocalDate.of(2026, 12, 5), second.next(today))
        assertFalse(second.lapsed(today))
        assertEquals("\"A\"", folo.getValue("parts").jsonObject.getValue("subscription").jsonObject["paidWith"].toString())
    }

    @Test
    fun `the car's month and charges read from what the import wrote`() {
        val month = json(
            """{"deviceId":"zeekr-007gt","month":"2026-09","capturedAt":"2026-10-24T03:10:00+08:00","report":{"distanceMeters":1895000,"energyWh":294700,"reportedConsumption":{"whPer100Km":14800,"basis":"driving"}},"days":[{"date":"2026-09-30","distanceMeters":439000,"source":{}}],"assistedDriving":{"nzp":{"distanceMeters":1154000,"reportedDistancePercent":99},"accLcc":{}},"sources":[]}""",
        )
        val charge = json(
            """{"uid":"01K6C0AAAAAAAAAAAAAAAAAAAA","at":"2026-09-30T23:09+08:00","parts":{"charge":{"v":1,"type":"DC","energyWh":65700,"durationMin":38,"vehicle":"zeekr-007gt"}},"createdAt":"2026-09-30T23:09+08:00","updatedAt":"2026-09-30T23:09+08:00"}""",
        )
        val (read) = Vehicle.months(listOf(month), "zeekr-007gt")
        assertEquals(1895000, read.distanceMeters)
        assertEquals(14800, read.whPer100Km)
        assertEquals(439000, read.days.single().distanceMeters)
        assertEquals(99, read.assisted?.nzpPercent)
        val (only) = Vehicle.charges(listOf(charge, phone), "zeekr-007gt")
        assertEquals("DC", only.type)
        assertEquals(38, only.durationMinutes)
        assertTrue(Vehicle.months(listOf(month), "other").isEmpty())
    }

    @Test
    fun `a device or a subscription has its day alone, so it is the earliest of its day`() {
        val charge = Charge("c", "2026-10-05T08:55+08:00", dc = true, kwh = 71.8, minutes = 34)
        val renewal = ThingRecord("s", "2026-10-05", device = false, bought = false, name = "ChatGPT Plus", price = 200.0, currency = "USD")
        val items = dayItems(emptyList(), emptyList(), listOf(charge), listOf(renewal))
        assertEquals(listOf("charge-c", "thing-s"), items.map { it.key })
        assertTrue(items.last() is DayItem.ThingItem)
    }
}
