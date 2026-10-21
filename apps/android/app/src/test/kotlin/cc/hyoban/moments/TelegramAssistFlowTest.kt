package cc.hyoban.moments

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistStop
import cc.hyoban.moments.accessibility.AssistSession
import cc.hyoban.moments.share.TelegramShareAdapter
import cc.hyoban.moments.share.TelegramAssistFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TelegramAssistFlowTest {
    private val caption = "照片说明：中文、emoji 🐈\n第二行 & # @someone"
    private fun flow(text: String = caption) = AssistSession(TelegramShareAdapter.request("hyoban_travel", text, 2), 0)
    private fun AssistSession.next(nodes: List<AssistNode>, now: Long) = next(TelegramShareAdapter.packageName, nodes, now)
    private val search = AssistNode(2, description = "Search Chats", editable = true)
    private val picker = listOf(AssistNode(1, text = "Select Chat"), search)
    private val result = AssistNode(3, text = "Hyoban 的旅行日记, @hyoban_travel, 133 subscribers", inList = true, hasToggle = true)
    private fun results(vararg rows: AssistNode) = listOf(search.copy(text = "hyoban_travel")) + rows
    private fun selected(text: String = "Add a caption...", count: Int = 2, title: String = "Hyoban 的旅行日记", recipients: Int = 1) = listOf(
        AssistNode(1, text = "$recipients recipient${if (recipients == 1) "" else "s"}"),
        AssistNode(4, text = "To $title"),
        AssistNode(5, text = "Send $count photos"),
        AssistNode(6, text = text, editable = true),
        AssistNode(7, text = "Share in 1 chat"), // The final send control must never be an action target.
        search,
    )
    private fun select(flow: AssistSession) {
        assertEquals(AssistAction.Click(2), flow.next(picker, 0))
        assertEquals(AssistAction.SetText(2, "hyoban_travel"), flow.next(listOf(search), 1))
        assertEquals(AssistAction.Click(3, gestureFallback = true, expectedText = result.text), flow.next(results(result), 2))
    }

    @Test fun `fills the exact caption and ends only after reading it back twice`() {
        val flow = flow()
        select(flow)
        assertEquals(AssistAction.SetText(6, caption), flow.next(selected(), 3))
        assertEquals(AssistAction.Wait, flow.next(selected(caption), 4))
        assertEquals(AssistAction.Ready, flow.next(selected(caption), 5))
        assertEquals(AssistAction.Wait, flow.next(picker, 6), "a completed task must not act again")
    }

    @Test fun `does not act on a normal chat or an already selected recipient`() {
        assertEquals(AssistAction.Wait, flow().next(selected(), 0))
        assertIs<AssistAction.Stop>(flow().next(picker + AssistNode(8, checked = true), 0))
    }

    @Test fun `matches an exact username in a selectable result rather than display name or prefix`() {
        val flow = flow()
        flow.next(picker, 0)
        flow.next(listOf(search), 1)
        assertEquals(AssistAction.Wait, flow.next(results(result.copy(text = "hyoban_travel, @someone_else, 12 subscribers")), 2))
        assertEquals(AssistAction.Wait, flow.next(results(result.copy(text = "Travel, @hyoban_travel_fake, 12 subscribers")), 3))
        assertEquals(AssistAction.Wait, flow.next(results(result.copy(hasToggle = false)), 4))
        assertEquals(AssistAction.Click(3, gestureFallback = true, expectedText = result.text), flow.next(results(result), 5))
    }

    @Test fun `ambiguous or already checked search results stop without clicking`() {
        for (rows in listOf(arrayOf(result, result.copy(id = 8)), arrayOf(result.copy(checked = true)))) {
            val flow = flow()
            flow.next(picker, 0)
            flow.next(listOf(search), 1)
            assertEquals(AssistAction.Stop(AssistStop.CHANGED), flow.next(results(*rows), 2))
            assertEquals(AssistAction.Wait, flow.next(results(result), 3))
        }
    }

    @Test fun `recipient title photo count and single selection must all match before editing`() {
        val flow = flow()
        select(flow)
        assertEquals(AssistAction.Wait, flow.next(selected(title = "Other channel"), 3))
        assertEquals(AssistAction.Wait, flow.next(selected(count = 1), 4))
        assertEquals(AssistAction.Wait, flow.next(selected(recipients = 2), 5))
        assertEquals(AssistAction.SetText(6, caption), flow.next(selected(), 6))
    }

    @Test fun `never overwrites unexpected text or chooses between multiple caption fields`() {
        val flow = flow()
        select(flow)
        assertEquals(AssistAction.Wait, flow.next(selected() + AssistNode(9, editable = true), 3))
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), flow.next(selected("A draft edited by the user"), 4))
    }

    @Test fun `a caption already imported by Telegram needs no write`() {
        val flow = flow()
        select(flow)
        assertEquals(AssistAction.Wait, flow.next(selected(caption), 3))
        assertEquals(AssistAction.Ready, flow.next(selected(caption), 4))
    }

    @Test fun `truncated text is never reported as ready and the request expires`() {
        val flow = flow()
        select(flow)
        flow.next(selected(), 3)
        assertEquals(AssistAction.Wait, flow.next(selected(caption.take(8)), 4))
        assertEquals(AssistAction.Stop(AssistStop.TIMEOUT), flow.next(selected(caption), TelegramShareAdapter.timeoutMs))
        assertEquals(AssistAction.Wait, flow.next(picker, TelegramShareAdapter.timeoutMs + 1))
    }

    @Test fun `verification restarts when the recipient or caption field disappears`() {
        for (interrupted in listOf(selected(caption, recipients = 2), selected(caption).filter { it.id != 6 })) {
            val flow = flow()
            select(flow)
            assertEquals(AssistAction.Wait, flow.next(selected(caption), 3))
            assertEquals(AssistAction.Wait, flow.next(interrupted, 4))
            assertEquals(AssistAction.Wait, flow.next(selected(caption), 5))
            assertEquals(AssistAction.Ready, flow.next(selected(caption), 6))
        }
    }

    @Test fun `only public chat usernames are accepted`() {
        for (input in listOf("@hyoban_travel", "https://t.me/hyoban_travel", "t.me/HYOBAN_TRAVEL/")) {
            assertEquals("hyoban_travel", TelegramAssistFlow.username(input))
        }
        for (input in listOf("", "t.me/+invite", "https://evil.example/hyoban_travel", "t.me/hyoban_travel/123", "t.me/hyoban_travel?start=x", "Hyoban 的旅行日记")) {
            assertNull(TelegramAssistFlow.username(input))
        }
    }
}
