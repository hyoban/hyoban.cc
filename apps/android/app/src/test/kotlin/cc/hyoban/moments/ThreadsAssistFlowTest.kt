package cc.hyoban.moments

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistSession
import cc.hyoban.moments.accessibility.AssistStop
import cc.hyoban.moments.share.ThreadsShareAdapter
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadsAssistFlowTest {
    private val text = "中文正文 🐈\n第二行 @someone #旅行"
    private fun session(body: String = text, photos: Int = 2) = AssistSession(ThreadsShareAdapter.request(body, photos), 0)
    private fun AssistSession.next(nodes: List<AssistNode>, now: Long = 0) = next(ThreadsShareAdapter.packageName, nodes, now)
    private fun composer(body: String = "", photos: Int = 2, row: Boolean = photos > 1) = listOf(
        AssistNode(1, viewId = "navigation_bar_title"),
        AssistNode(2, text = "新建串文"),
        AssistNode(3, text = body, editable = true, viewId = "new_thread_screen_composer"),
        AssistNode(4, viewId = "new_thread_screen_post_button"),
        AssistNode(5, text = "发布"),
    ) + listOfNotNull(AssistNode(6, viewId = "IgLazyRow").takeIf { row }) + (0 until photos).map { AssistNode(10 + it, description = "移除附件") }

    @Test fun `fills the verified composer and reads text back twice without ever clicking Post`() {
        val session = session()
        assertEquals(AssistAction.SetText(3, text), session.next(composer()))
        assertEquals(AssistAction.Wait, session.next(composer(text)))
        assertEquals(AssistAction.Ready, session.next(composer(text)))
        assertEquals(AssistAction.Wait, session.next(composer()))
    }

    @Test fun `never clicks a share menu Post label or writes into another editor`() {
        val session = session()
        assertEquals(AssistAction.Wait, session.next(listOf(AssistNode(5, text = "发布"))))
        assertEquals(AssistAction.Wait, session.next(composer().filter { it.viewId != "new_thread_screen_post_button" }))
        assertEquals(AssistAction.Wait, session.next(composer().filter { it.viewId != "navigation_bar_title" }))
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session.next(composer().map {
            if (it.id == 3) it.copy(viewId = "message_editor") else it
        }))
    }

    @Test fun `an existing draft or multiple editors are preserved`() {
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session().next(composer("An existing draft")))
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session().next(composer() + AssistNode(22, editable = true)))
    }

    @Test fun `virtualized photo rows allow body filling without claiming to verify the full album`() {
        val session = session(photos = 3)
        assertEquals(AssistAction.Wait, session.next(composer(photos = 0)))
        assertEquals(AssistAction.SetText(3, text), session.next(composer(photos = 2)))
        assertEquals(AssistAction.Wait, session.next(composer(text, photos = 1)))
        assertEquals(AssistAction.Ready, session.next(composer(text, photos = 2)))
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session(photos = 1).next(composer(photos = 2)))
    }

    @Test fun `truncated text and a missing photo row do not count as completed`() {
        val session = session()
        session.next(composer())
        assertEquals(AssistAction.Wait, session.next(composer(text.take(4))))
        assertEquals(AssistAction.Wait, session.next(composer(text)))
        assertEquals(AssistAction.Wait, session.next(composer(text, photos = 0)))
        assertEquals(AssistAction.Wait, session.next(composer(text)))
        assertEquals(AssistAction.Ready, session.next(composer(text)))
    }

    @Test fun `a single photo shown without a photo row is filled`() {
        val session = session(photos = 1)
        assertEquals(AssistAction.SetText(3, text), session.next(composer(photos = 1)))
        assertEquals(AssistAction.Wait, session.next(composer(text, photos = 1)))
        assertEquals(AssistAction.Ready, session.next(composer(text, photos = 1)))
    }

    @Test fun `single photos and already imported or empty text need no replacement`() {
        for (body in listOf(text, "")) {
            val session = session(body, photos = 1)
            assertEquals(AssistAction.Wait, session.next(composer(body, photos = 1)))
            assertEquals(AssistAction.Ready, session.next(composer(body, photos = 1)))
        }
    }
}
