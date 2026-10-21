package cc.hyoban.moments.accessibility

/** Shared lifecycle, independent of Android so timeouts and app boundaries can be tested. */
internal class AssistSession(val request: AssistRequest, private val startedAt: Long) {
    var status: AssistStatus = AssistStatus.Running
        private set
    private var sawTarget = false
    val packageName: String get() = request.adapter.packageName

    fun next(packageName: String?, nodes: List<AssistNode>?, now: Long): AssistAction {
        if (status != AssistStatus.Running) return AssistAction.Wait
        if (now - startedAt >= request.adapter.timeoutMs) return stop(AssistStop.TIMEOUT)
        if (packageName != null && packageName != this.packageName) {
            return if (sawTarget) stop(AssistStop.CANCELLED) else AssistAction.Wait
        }
        if (packageName == null || nodes == null) return AssistAction.Wait
        sawTarget = true
        return request.flow.next(nodes).also {
            when (it) {
                AssistAction.Ready -> status = AssistStatus.Ready
                is AssistAction.Stop -> status = AssistStatus.Stopped(it.reason)
                else -> Unit
            }
        }
    }

    fun stop(reason: AssistStop): AssistAction.Stop {
        status = AssistStatus.Stopped(reason)
        return AssistAction.Stop(reason)
    }
}
