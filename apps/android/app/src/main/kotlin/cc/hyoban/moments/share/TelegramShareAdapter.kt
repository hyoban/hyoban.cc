package cc.hyoban.moments.share

import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.AssistAdapter
import cc.hyoban.moments.accessibility.AssistRequest

/** Telegram owns its version policy, page recognition, flow, and user-facing messages. */
internal object TelegramShareAdapter : AssistAdapter {
    override val id = "telegram-share"
    override val packageName = "org.telegram.messenger"
    override val supportedVersions = setOf("13.1.0")
    override val timeoutMs = 40_000L
    override val readyMessage = R.string.telegram_assist_ready
    override val stoppedMessage = R.string.telegram_assist_stopped

    fun request(username: String, caption: String, photoCount: Int): AssistRequest {
        require(photoCount > 0)
        return AssistRequest(this, TelegramAssistFlow(username, caption, photoCount))
    }
}
