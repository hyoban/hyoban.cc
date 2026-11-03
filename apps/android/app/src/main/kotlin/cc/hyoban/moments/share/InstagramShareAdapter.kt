package cc.hyoban.moments.share

import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.AssistAdapter
import cc.hyoban.moments.accessibility.AssistRequest

internal object InstagramShareAdapter : AssistAdapter {
    override val id = "instagram-share"
    override val packageName = "com.instagram.android"
    // The photos are edited and 继续 tapped by hand before the caption page comes.
    override val timeoutMs = 120_000L
    override val readyMessage = R.string.instagram_assist_ready
    override val stoppedMessage = R.string.instagram_assist_stopped

    fun request(text: String): AssistRequest {
        require(text.isNotBlank())
        return AssistRequest(this, InstagramAssistFlow(text))
    }
}
