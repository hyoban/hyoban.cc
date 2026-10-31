package cc.hyoban.moments

import cc.hyoban.moments.accessibility.AssistAction
import cc.hyoban.moments.accessibility.AssistAdapter
import cc.hyoban.moments.accessibility.AssistFlow
import cc.hyoban.moments.accessibility.AssistNode
import cc.hyoban.moments.accessibility.AssistRequest
import cc.hyoban.moments.accessibility.AssistSession
import cc.hyoban.moments.accessibility.AssistState
import cc.hyoban.moments.accessibility.AssistStatus
import cc.hyoban.moments.accessibility.AssistStop
import kotlin.test.Test
import kotlin.test.assertEquals

class AssistSessionTest {
    private class Adapter(override val id: String, override val packageName: String) : AssistAdapter {
        override val timeoutMs = 40_000L
        override val readyMessage = 0
        override val stoppedMessage = 0
    }

    private class Flow(var action: AssistAction = AssistAction.Wait) : AssistFlow {
        override val stage = "TEST"
        var calls = 0
        override fun next(nodes: List<AssistNode>): AssistAction { calls++; return action }
    }

    private val first = Adapter("first", "app.first")
    private val second = Adapter("second", "app.second")

    @Test fun `only the requested app reaches its adapter and leaving it cancels`() {
        val flow = Flow(AssistAction.Click(1))
        val session = AssistSession(AssistRequest(first, flow), 0)
        assertEquals(AssistAction.Wait, session.next("our.app", emptyList(), 0))
        assertEquals(AssistAction.Wait, session.next(second.packageName, emptyList(), 1))
        assertEquals(0, flow.calls)
        assertEquals(AssistAction.Click(1), session.next(first.packageName, emptyList(), 2))
        assertEquals(AssistAction.Wait, session.next(null, null, 3))
        assertEquals(AssistAction.Stop(AssistStop.CANCELLED), session.next(second.packageName, emptyList(), 4))
        assertEquals(AssistAction.Wait, session.next(first.packageName, emptyList(), 5))
        assertEquals(1, flow.calls)
    }

    @Test fun `deadline applies even when no target window ever appears`() {
        val flow = Flow()
        val session = AssistSession(AssistRequest(second, flow), 100)
        assertEquals(AssistAction.Wait, session.next(null, null, 40_099))
        assertEquals(AssistAction.Stop(AssistStop.TIMEOUT), session.next(null, null, 40_100))
        assertEquals(AssistAction.Wait, session.next(second.packageName, emptyList(), 40_101))
        assertEquals(0, flow.calls)
    }

    @Test fun `ready failure and explicit cancellation are terminal`() {
        for (action in listOf(AssistAction.Ready, AssistAction.Stop(AssistStop.CHANGED))) {
            val flow = Flow(action)
            val session = AssistSession(AssistRequest(first, flow), 0)
            assertEquals(action, session.next(first.packageName, emptyList(), 1))
            assertEquals(AssistAction.Wait, session.next(first.packageName, emptyList(), 2))
            assertEquals(1, flow.calls)
        }
        val flow = Flow()
        val session = AssistSession(AssistRequest(first, flow), 0)
        session.stop(AssistStop.CANCELLED)
        assertEquals(AssistAction.Wait, session.next(first.packageName, emptyList(), 1))
        assertEquals(0, flow.calls)
    }

    @Test fun `a different app uses the same lifecycle without inheriting the previous result`() {
        val ready = AssistSession(AssistRequest(first, Flow(AssistAction.Ready)), 0)
        ready.next(first.packageName, emptyList(), 1)
        val state = AssistState(first.id, ready.status)
        assertEquals(AssistStatus.Ready, state.forAdapter(first.id))
        assertEquals(AssistStatus.Idle, state.forAdapter(second.id))

        val otherFlow = Flow(AssistAction.SetText(2, "second app"))
        val other = AssistSession(AssistRequest(second, otherFlow), 100)
        assertEquals(AssistAction.Wait, other.next(first.packageName, emptyList(), 101))
        assertEquals(AssistAction.SetText(2, "second app"), other.next(second.packageName, emptyList(), 102))
        assertEquals(AssistStatus.Running, other.status)
        assertEquals(1, otherFlow.calls)
    }
}
