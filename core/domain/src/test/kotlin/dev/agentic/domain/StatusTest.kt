package dev.agentic.domain

import dev.agentic.data.net.WorkflowRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {

    // ── statusVisual ──────────────────────────────────────────────────────────

    @Test fun running_awaiting_is_idle() =
        assertEquals(StatusVisual.IDLE, statusVisual("running", true))

    @Test fun running_busy_is_running() =
        assertEquals(StatusVisual.RUNNING, statusVisual("running", false))

    @Test fun running_null_awaiting_is_running() =
        assertEquals(StatusVisual.RUNNING, statusVisual("running", null))

    @Test fun done_maps_done() =
        assertEquals(StatusVisual.DONE, statusVisual("done", null))

    @Test fun failed_maps_failed() =
        assertEquals(StatusVisual.FAILED, statusVisual("failed", null))

    @Test fun killed_maps_killed() =
        assertEquals(StatusVisual.KILLED, statusVisual("killed", null))

    @Test fun pending_maps_pending() =
        assertEquals(StatusVisual.PENDING, statusVisual("pending", null))

    @Test fun unknown_status_maps_pending() =
        assertEquals(StatusVisual.PENDING, statusVisual("something-else", null))

    // workflow run/agent synonyms map to the SAME visuals as sessions (so indicators are consistent)

    @Test fun completed_maps_done() =
        assertEquals(StatusVisual.DONE, statusVisual("completed", null))

    @Test fun complete_maps_done() =
        assertEquals(StatusVisual.DONE, statusVisual("complete", null))

    @Test fun error_maps_failed() =
        assertEquals(StatusVisual.FAILED, statusVisual("error", null))

    @Test fun cancelled_maps_killed() =
        assertEquals(StatusVisual.KILLED, statusVisual("cancelled", null))

    @Test fun canceled_maps_killed() =
        assertEquals(StatusVisual.KILLED, statusVisual("canceled", null))

    @Test fun statusVisual_is_case_and_whitespace_insensitive() =
        assertEquals(StatusVisual.DONE, statusVisual("  Completed  ", null))

    // ── WorkflowRun.isActive ──────────────────────────────────────────────────

    @Test fun done_run_is_not_active() =
        assertFalse(WorkflowRun(runId = "1", status = "done").isActive())

    @Test fun running_run_is_active() =
        assertTrue(WorkflowRun(runId = "2", status = "running").isActive())

    @Test fun completed_run_is_not_active() =
        assertFalse(WorkflowRun(runId = "3", status = "completed").isActive())

    @Test fun failed_run_is_not_active() =
        assertFalse(WorkflowRun(runId = "4", status = "failed").isActive())

    @Test fun killed_run_is_not_active() =
        assertFalse(WorkflowRun(runId = "5", status = "killed").isActive())

    @Test fun isActive_is_case_insensitive() =
        assertFalse(WorkflowRun(runId = "6", status = "  DONE  ").isActive())
}
