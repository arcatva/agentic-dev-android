package dev.agentic.domain

import dev.agentic.data.net.Session
import org.junit.Assert.*
import org.junit.Test

class SessionIndicatorTest {
    private fun session(
        status: String = "done", errorKind: String? = null, awaitingInput: Boolean? = null,
        unreadEventId: Long = 0, ackedEventId: Long = 0, workflowRunning: Boolean = false,
    ) = Session(id = "s", status = status, errorKind = errorKind, awaitingInput = awaitingInput,
        unreadEventId = unreadEventId, ackedEventId = ackedEventId, workflowRunning = workflowRunning)

    @Test fun `workflow running shows running`() {
        assertEquals("running", indicatorStatus(session(status = "done", workflowRunning = true)))
        assertEquals(false, indicatorAwaitingInput(session(status = "done", workflowRunning = true)))
    }
    @Test fun `failed maps to failed`() = assertEquals("failed", indicatorStatus(session(status = "failed")))
    @Test fun `benign cap renders like done`() = assertEquals("done", indicatorStatus(session(status = "running", errorKind = "wall_timeout")))

    // Discord-style: unread = unreadEventId > ackedEventId (both server-side)
    @Test fun `unreadEventId greater than acked is unread`() = assertTrue(isSessionUnread(session(unreadEventId = 5, ackedEventId = 3)))
    @Test fun `unreadEventId equal to acked is read`() = assertFalse(isSessionUnread(session(unreadEventId = 5, ackedEventId = 5)))
    @Test fun `unreadEventId less than acked is read`() = assertFalse(isSessionUnread(session(unreadEventId = 3, ackedEventId = 5)))
    @Test fun `both zero is read`() = assertFalse(isSessionUnread(session(unreadEventId = 0, ackedEventId = 0)))
    @Test fun `unreadEventId zero with ack nonzero is read`() = assertFalse(isSessionUnread(session(unreadEventId = 0, ackedEventId = 5)))
}
