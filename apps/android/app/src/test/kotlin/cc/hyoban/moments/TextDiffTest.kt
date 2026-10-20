package cc.hyoban.moments

import cc.hyoban.moments.ui.publish.DiffPart
import cc.hyoban.moments.ui.publish.DiffPart.Kind.ADDED
import cc.hyoban.moments.ui.publish.DiffPart.Kind.REMOVED
import cc.hyoban.moments.ui.publish.DiffPart.Kind.SAME
import cc.hyoban.moments.ui.publish.diffText
import kotlin.test.Test
import kotlin.test.assertEquals

/** A change's words, marked where they were taken out and put in. */
class TextDiffTest {
    @Test
    fun `Chinese is compared character by character`() {
        assertEquals(
            listOf(DiffPart(SAME, "在码头等"), DiffPart(REMOVED, "车"), DiffPart(ADDED, "船，船晚点了"), DiffPart(SAME, "。")),
            diffText("在码头等车。", "在码头等船，船晚点了。"),
        )
    }

    @Test
    fun `other words are compared whole`() {
        assertEquals(
            listOf(DiffPart(SAME, "drove "), DiffPart(REMOVED, "3951"), DiffPart(ADDED, "4000"), DiffPart(SAME, " km")),
            diffText("drove 3951 km", "drove 4000 km"),
        )
    }

    @Test
    fun `words written or cleared are all of one kind`() {
        assertEquals(listOf(DiffPart(ADDED, "新的")), diffText("", "新的"))
        assertEquals(listOf(DiffPart(REMOVED, "旧的")), diffText("旧的", ""))
        assertEquals(listOf(DiffPart(SAME, "一样")), diffText("一样", "一样"))
    }
}
