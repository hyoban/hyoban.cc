package cc.hyoban.moments

import cc.hyoban.moments.data.spec.Entries
import cc.hyoban.moments.data.spec.MomentCost
import cc.hyoban.moments.plugins.costOf
import cc.hyoban.moments.ui.format.formatAmount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FoodTest {
    @Test
    fun `a meal's dishes split at commas and new lines, its amount only when it reads as one`() {
        assertEquals(
            MomentCost(68.5, listOf("沙茶面", "卡旺卡", "烧仙草"), "good", null, 2, MomentCost.FOOD),
            costOf(true, " 沙茶面，卡旺卡、\n烧仙草, ", "good", "68.5", "CNY", "2", null),
        )
        assertEquals(MomentCost(null, listOf("沙茶面"), kind = MomentCost.FOOD), costOf(true, "沙茶面", null, "", "CNY", "2", null))
        assertNull(costOf(true, "  ", null, "", "CNY", "", null))
    }

    @Test
    fun `any other cost, in its currency, yuan written as none`() {
        assertEquals(MomentCost(120.0, listOf("门票", "讲解"), "bad", null, 2), costOf(false, "门票、讲解", "bad", "120", "CNY", "2", null))
        assertEquals(MomentCost(80.0, currency = "HKD"), costOf(false, "", null, "80", "HKD", "", null))
    }

    @Test
    fun `what it was typed back the same`() {
        val saved = MomentCost(68.0, listOf("打车"))
        assertEquals(saved, costOf(false, saved.items.joinToString("、"), null, formatAmount(68.0), "CNY", "", saved))
        assertEquals("12.5", formatAmount(12.5))
    }

    @Test
    fun `a meal kept the old way is read as a cost of the kind food, and written as one`() {
        val entry = Json.parseToJsonElement(
            """{"uid":"01JT5R1PW0NG5E9BJRF8SHQGG5","at":"2025-05-01T19:00:00+08:00","parts":{"text":{"v":1,"text":"面"},""" +
                """"food":{"v":1,"dishes":["面"],"verdict":"good","cost":26,"people":1}},"createdAt":"2025-05-01T19:00:00+08:00","updatedAt":"2025-05-01T19:00:00+08:00"}""",
        ).jsonObject
        val moment = Entries.toMoment(entry)!!
        assertEquals(MomentCost(26.0, listOf("面"), "good", null, 1, MomentCost.FOOD), moment.cost)
        val parts = Entries.fromMoment(moment).getValue("parts").jsonObject
        assertEquals(setOf("text", "cost"), parts.keys)
        val cost = parts.getValue("cost").jsonObject
        assertEquals(JsonPrimitive("food"), cost["kind"])
        assertEquals(26.0, (cost["amount"] as JsonPrimitive).content.toDouble())
    }
}
