package cc.hyoban.moments.share

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistFlow
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistStop

/**
 * Fills only the caption of a feed post's last page (新帖子, inspected on Instagram 412), which
 * the user reaches by editing the photos and tapping 继续 by hand. Never clicks Share.
 */
internal class InstagramAssistFlow(private val text: String) : AssistFlow {
    private enum class Step { CAPTION, VERIFY, FINISHED }
    private var step = Step.CAPTION
    private var verifiedOnce = false
    override val stage: String get() = step.name

    override fun next(nodes: List<AssistNode>): AssistAction {
        if (step == Step.FINISHED) return AssistAction.Wait
        val visible = nodes.filter { it.enabled }
        // The photo editor before it has no Share button and no caption; wait through it.
        if (visible.none { it.id("action_bar_textview_title") && it.text in TITLES } ||
            visible.none { it.id("share_footer_button") }) {
            verifiedOnce = false
            return AssistAction.Wait
        }
        val editors = visible.filter { it.editable }
        if (editors.isEmpty()) {
            verifiedOnce = false
            return AssistAction.Wait
        }
        val editor = editors.singleOrNull()?.takeIf { it.id("caption_input_text_view") } ?: return stop()
        // An empty caption reads out its hint, 添加配文…, as its text.
        val caption = if (editor.text == editor.hint) "" else editor.text
        if (step == Step.CAPTION) {
            if (caption.isNotEmpty() && caption != text) return stop()
            step = Step.VERIFY
            if (caption != text) return AssistAction.SetText(editor.id, text)
        }
        if (caption == text) {
            if (verifiedOnce) {
                step = Step.FINISHED
                return AssistAction.Ready
            }
            verifiedOnce = true
        } else {
            verifiedOnce = false
        }
        return AssistAction.Wait
    }

    /** Instagram's views carry their full resource name, `com.instagram.android:id/<name>`. */
    private fun AssistNode.id(name: String) = viewId.substringAfter(":id/") == name

    private fun stop(): AssistAction.Stop {
        step = Step.FINISHED
        return AssistAction.Stop(AssistStop.CHANGED)
    }

    companion object {
        private val TITLES = setOf("新帖子", "新貼文", "New post")
    }
}
