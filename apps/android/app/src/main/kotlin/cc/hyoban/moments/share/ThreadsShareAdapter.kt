package cc.hyoban.moments.share

import cc.hyoban.moments.R
import cc.hyoban.moments.accessibility.AssistAdapter
import cc.hyoban.moments.accessibility.AssistRequest

internal object ThreadsShareAdapter : AssistAdapter {
    override val id = "threads-share"
    override val packageName = "com.instagram.barcelona"
    override val supportedVersions = setOf("452.0.0.50.64")
    override val timeoutMs = 40_000L
    override val readyMessage = R.string.threads_assist_ready
    override val stoppedMessage = R.string.threads_assist_stopped

    fun request(text: String, photoCount: Int): AssistRequest {
        require(photoCount > 0)
        return AssistRequest(this, ThreadsAssistFlow(text, photoCount))
    }
}
