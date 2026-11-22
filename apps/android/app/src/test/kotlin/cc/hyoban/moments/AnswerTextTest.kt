package cc.hyoban.moments

import cc.hyoban.moments.ui.trips.AnswerLine
import cc.hyoban.moments.ui.trips.AnswerRun
import cc.hyoban.moments.ui.trips.answerLines
import cc.hyoban.moments.ui.trips.runsOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** A model's answer read into lines to set: list items, headings and strong words, the marks taken out. */
class AnswerTextTest {
    @Test
    fun readsListsHeadingsAndStrongWords() {
        val lines = answerLines("## 具体到几家\n- 谭婆饺子粑**十二点**收摊\n2. 樊氏牛骨粉\n\n所以就这样")
        assertEquals(AnswerLine(listOf(AnswerRun("具体到几家")), heading = true), lines[0])
        assertEquals(AnswerLine(listOf(AnswerRun("谭婆饺子粑"), AnswerRun("十二点", strong = true), AnswerRun("收摊")), marker = "•"), lines[1])
        assertEquals("2.", lines[2].marker)
        assertEquals(true, lines[3].empty)
        assertEquals(null, lines[4].marker)
    }

    @Test
    fun leavesWordsThatOnlyLookLikeMarksAlone() {
        // A time, a range or a negative number at a line's start is no list item.
        assertEquals(null, answerLines("12:50 出发")[0].marker)
        assertEquals(null, answerLines("-5 度")[0].marker)
        assertEquals(null, answerLines("1.5 公里走过去")[0].marker)
        // Strong left open while it streams.
        assertEquals(listOf(AnswerRun("先说"), AnswerRun("结论", strong = true)), runsOf("先说**结论"))
    }
}
