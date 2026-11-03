package cc.hyoban.moments

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistSession
import cc.hyoban.moments.accessibility.AssistStop
import cc.hyoban.moments.share.InstagramShareAdapter
import kotlin.test.Test
import kotlin.test.assertEquals

class InstagramAssistFlowTest {
    private val text = "中文说明 🐈\n第二行 #旅行"
    private fun session() = AssistSession(InstagramShareAdapter.request(text), 0)
    private fun AssistSession.next(nodes: List<AssistNode>, now: Long = 0) = next(InstagramShareAdapter.packageName, nodes, now)
    private fun editor() = listOf(
        AssistNode(1, description = "返回", viewId = "$ID/button_back"),
        AssistNode(2, text = "滤镜"),
    )
    // An empty caption reads out its hint as its text.
    private fun caption(body: String = "添加配文…") = listOf(
        AssistNode(1, description = "返回", viewId = "$ID/button_back"),
        AssistNode(2, text = "新帖子", viewId = "$ID/action_bar_textview_title"),
        AssistNode(3, text = body, hint = "添加配文…", editable = true, viewId = "$ID/caption_input_text_view"),
        AssistNode(4, description = "分享", viewId = "$ID/share_footer_button"),
    )

    @Test fun `waits through the photo editor, then fills the caption and reads it back twice without sharing`() {
        val session = session()
        assertEquals(AssistAction.Wait, session.next(editor()))
        assertEquals(AssistAction.SetText(3, text), session.next(caption()))
        assertEquals(AssistAction.Wait, session.next(caption(text)))
        assertEquals(AssistAction.Ready, session.next(caption(text)))
        assertEquals(AssistAction.Wait, session.next(caption(text)))
    }

    @Test fun `keeps a caption already written and never picks between editors`() {
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session().next(caption("别的说明")))
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session().next(caption() + AssistNode(5, editable = true)))
        assertEquals(AssistAction.Stop(AssistStop.CHANGED), session().next(caption().map {
            if (it.id == 3) it.copy(viewId = "$ID/comment_composer") else it
        }))
    }

    @Test fun `waits on a page that only looks like the caption page`() {
        val session = session()
        assertEquals(AssistAction.Wait, session.next(caption().filter { it.viewId != "$ID/share_footer_button" }))
        assertEquals(AssistAction.Wait, session.next(caption().map { if (it.id == 2) it.copy(text = "编辑") else it }))
    }

    @Test fun `stops after two minutes`() {
        assertEquals(AssistAction.Stop(AssistStop.TIMEOUT), session().next(editor(), now = 120_000))
    }

    private companion object {
        const val ID = "com.instagram.android:id"
    }
}
