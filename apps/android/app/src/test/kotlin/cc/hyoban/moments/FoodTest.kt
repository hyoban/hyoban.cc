package cc.hyoban.moments

import cc.hyoban.moments.data.spec.FoodBill
import cc.hyoban.moments.data.spec.MomentFood
import cc.hyoban.moments.ui.compose.foodOf
import cc.hyoban.moments.ui.format.formatAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FoodTest {
    @Test
    fun `dishes split at commas and new lines, a bill only with its yuan`() {
        assertEquals(
            MomentFood(listOf("沙茶面", "卡旺卡", "烧仙草"), "good", FoodBill(68.5, 2)),
            foodOf(" 沙茶面，卡旺卡、\n烧仙草, ", "good", "68.5", "2", null),
        )
        assertEquals(MomentFood(listOf("沙茶面")), foodOf("沙茶面", null, "", "2", null))
    }

    @Test
    fun `nothing said is no food`() {
        assertNull(foodOf("  ", null, "", "", null))
    }

    @Test
    fun `what it was typed back the same`() {
        val saved = MomentFood(listOf("沙茶面", "卡旺卡"), null, FoodBill(68.0))
        assertEquals(saved, foodOf(saved.dishes.joinToString("、"), null, formatAmount(68.0), "", saved))
        assertEquals("12.5", formatAmount(12.5))
    }
}
