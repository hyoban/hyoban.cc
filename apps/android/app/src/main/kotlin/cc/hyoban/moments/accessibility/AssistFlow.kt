package cc.hyoban.moments.accessibility

/** Visible node data, scoped to the active task's app and discarded after each inspection. */
internal data class AssistNode(
    val id: Int,
    val text: String = "",
    val description: String = "",
    val hint: String = "",
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val inList: Boolean = false,
    val hasToggle: Boolean = false,
    val checked: Boolean = false,
    val viewId: String = "",
)

/** Adapters identify nodes; only the service executes operations. No arbitrary coordinates. */
internal sealed interface AssistAction {
    data object Wait : AssistAction
    data class Click(val node: Int, val gestureFallback: Boolean = false, val expectedText: String? = null) : AssistAction
    data class SetText(val node: Int, val text: String) : AssistAction
    data object Ready : AssistAction
    data class Stop(val reason: AssistStop) : AssistAction
}

internal interface AssistFlow {
    val stage: String
    fun next(nodes: List<AssistNode>): AssistAction
}

internal interface AssistAdapter {
    val id: String
    val packageName: String
    val timeoutMs: Long
    val readyMessage: Int
    val stoppedMessage: Int
}

/** Each explicit share creates a fresh flow; it is never persisted or automatically retried. */
internal data class AssistRequest(val adapter: AssistAdapter, val flow: AssistFlow)

internal enum class AssistStop { TIMEOUT, CHANGED, CANCELLED }

internal sealed interface AssistStatus {
    data object Idle : AssistStatus
    data object Running : AssistStatus
    data object Ready : AssistStatus
    data class Stopped(val reason: AssistStop) : AssistStatus
}

internal data class AssistState(val adapterId: String? = null, val status: AssistStatus = AssistStatus.Idle) {
    fun forAdapter(id: String): AssistStatus = if (id == adapterId) status else AssistStatus.Idle
}
