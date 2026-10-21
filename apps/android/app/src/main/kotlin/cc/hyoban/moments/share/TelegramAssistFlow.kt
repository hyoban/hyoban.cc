package cc.hyoban.moments.share

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistFlow
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistStop

/** A one-shot flow for the share picker inspected on Telegram Android 13.1.0. */
internal class TelegramAssistFlow(
    private val username: String,
    private val caption: String,
    private val photoCount: Int,
) : AssistFlow {
    private enum class Step { PICKER, SEARCH, RESULTS, SELECTED, VERIFY, FINISHED }
    private var step = Step.PICKER
    private var recipientTitle: String? = null
    private var verifiedOnce = false
    override val stage: String get() = step.name

    override fun next(nodes: List<AssistNode>): AssistAction {
        if (step == Step.FINISHED) return AssistAction.Wait
        val visible = nodes.filter { it.enabled }
        val search = visible.filter { it.editable && it.isSearch() }.singleOrNull()
        when (step) {
            Step.PICKER -> {
                // Never attach to an existing selection, chat editor, or global search.
                if (visible.none { it.text in PICKER_TITLES }) return AssistAction.Wait
                if (visible.any { it.checked }) return stop(AssistStop.CHANGED)
                if (search != null) {
                    step = Step.SEARCH
                    return AssistAction.Click(search.id)
                }
            }
            Step.SEARCH -> if (search != null) {
                step = Step.RESULTS
                return AssistAction.SetText(search.id, username)
            }
            Step.RESULTS -> {
                if (search?.text != username) return AssistAction.Wait
                val matches = visible.filter { it.inList && it.hasToggle && usernameIn(it.text) == username }
                if (matches.size > 1) return stop(AssistStop.CHANGED)
                val match = matches.singleOrNull() ?: return AssistAction.Wait
                if (match.checked || visible.any { it.checked }) return stop(AssistStop.CHANGED)
                // The result names the public username; remember its title to verify the selection.
                recipientTitle = match.text.substringBefore(", @").trim().takeIf { it.isNotEmpty() }
                    ?: return stop(AssistStop.CHANGED)
                step = Step.SELECTED
                return AssistAction.Click(match.id, gestureFallback = true, expectedText = match.text)
            }
            Step.SELECTED, Step.VERIFY -> {
                if (!selectionMatches(visible)) {
                    verifiedOnce = false
                    return AssistAction.Wait
                }
                val field = visible.filter { it.editable && !it.isSearch() }.singleOrNull()
                if (field == null) {
                    verifiedOnce = false
                    return AssistAction.Wait
                }
                val existing = if (field.text == caption) caption else field.text.takeUnless { it in CAPTION_HINTS || it == field.hint }.orEmpty()
                if (step == Step.SELECTED) {
                    // Preserve an unexpected existing caption instead of replacing someone else's work.
                    if (existing.isNotEmpty() && existing != caption) return stop(AssistStop.CHANGED)
                    step = Step.VERIFY
                    if (existing != caption) return AssistAction.SetText(field.id, caption)
                }
                if (existing == caption) {
                    if (verifiedOnce) {
                        step = Step.FINISHED
                        return AssistAction.Ready
                    }
                    verifiedOnce = true
                } else {
                    verifiedOnce = false
                }
            }
            Step.FINISHED -> Unit
        }
        return AssistAction.Wait
    }

    private fun selectionMatches(nodes: List<AssistNode>): Boolean {
        val title = recipientTitle ?: return false
        return nodes.any { it.text == "1 recipient" || it.text == "1 位接收者" || it.text == "1 个接收者" } &&
            nodes.any { it.text == "To $title" || it.text == "发送至 $title" } &&
            nodes.any { it.text == "Send $photoCount photos" || (photoCount == 1 && it.text == "Send photo") ||
                it.text == "发送 $photoCount 张照片" }
    }

    private fun stop(reason: AssistStop): AssistAction.Stop {
        step = Step.FINISHED
        return AssistAction.Stop(reason)
    }

    companion object {
        private val PICKER_TITLES = setOf("Select Chat", "选择聊天", "选择对话")
        private val SEARCH_HINTS = setOf("Search Chats", "搜索聊天", "搜索对话")
        private val CAPTION_HINTS = setOf("Add a caption...", "Add a caption…", "添加说明…", "添加说明...")
        private fun AssistNode.isSearch() = description in SEARCH_HINTS || hint in SEARCH_HINTS
        private fun usernameIn(text: String): String? =
            Regex(", @([A-Za-z0-9_]+)(?:,|$)").find(text)?.groupValues?.get(1)?.lowercase()

        /** Accept a public username or its exact chat link, never an invite, message, or query. */
        fun username(input: String): String? {
            val name = input.trim().removePrefix("https://").removePrefix("http://")
                .removePrefix("t.me/").removePrefix("@").trimEnd('/')
            return name.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,31}")) }?.lowercase()
        }
    }
}
