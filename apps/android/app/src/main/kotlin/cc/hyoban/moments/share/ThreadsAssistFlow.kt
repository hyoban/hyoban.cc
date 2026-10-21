package cc.hyoban.moments.share

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistFlow
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistStop

/** Fills only the photo composer inspected on Threads 452.0.0.50.64. Never clicks Post. */
internal class ThreadsAssistFlow(private val text: String, private val photoCount: Int) : AssistFlow {
    private enum class Step { COMPOSER, VERIFY, FINISHED }
    private var step = Step.COMPOSER
    private var verifiedOnce = false
    override val stage: String get() = step.name

    override fun next(nodes: List<AssistNode>): AssistAction {
        if (step == Step.FINISHED) return AssistAction.Wait
        val visible = nodes.filter { it.enabled }
        // A label like "发布" can mean either opening a composer or actually posting.
        // Only recognize a complete composer; never click a text-matched share-menu item.
        if (visible.none { it.viewId == "navigation_bar_title" } ||
            visible.none { it.text in TITLES } ||
            visible.none { it.viewId == "new_thread_screen_post_button" }) {
            verifiedOnce = false
            return AssistAction.Wait
        }
        val editors = visible.filter { it.editable }
        val editor = editors.singleOrNull()?.takeIf { it.viewId == "new_thread_screen_composer" }
        if (editors.isEmpty()) {
            verifiedOnce = false
            return AssistAction.Wait
        }
        if (editor == null) return stop()
        // Threads virtualizes the horizontal photo row: neither the visible thumbnails nor
        // its collection item count proves the total number of photos. Only fill the body;
        // never alter attachments or claim their complete count has been checked.
        val visiblePhotos = visible.count { it.description in REMOVE_ATTACHMENT }
        if (visible.none { it.viewId == "IgLazyRow" } || visiblePhotos == 0) {
            verifiedOnce = false
            return AssistAction.Wait
        }
        if (visiblePhotos > photoCount) return stop()
        if (step == Step.COMPOSER) {
            if (editor.text.isNotEmpty() && editor.text != text) return stop()
            step = Step.VERIFY
            if (editor.text != text) return AssistAction.SetText(editor.id, text)
        }
        if (editor.text == text) {
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

    private fun stop(): AssistAction.Stop {
        step = Step.FINISHED
        return AssistAction.Stop(AssistStop.CHANGED)
    }

    companion object {
        private val TITLES = setOf("新建串文", "新建串文帖", "新串文", "New thread")
        private val REMOVE_ATTACHMENT = setOf("移除附件", "移除附加檔案", "Remove attachment")
    }
}
