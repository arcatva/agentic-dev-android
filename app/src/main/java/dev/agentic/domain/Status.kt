package dev.agentic.domain

import dev.agentic.data.net.WorkflowRun

/** Distinct visuals a session status can map to. Using an enum instead of the raw string means
 *  AnimatedContent only re-animates on a meaningful visual transition. */
enum class StatusVisual { DONE, FAILED, KILLED, RUNNING, IDLE, PENDING }

/** Status strings that mean a session has reached a terminal state. */
val TERMINAL = setOf("done", "failed", "killed")

// Status synonyms, normalised (trim + lowercase), shared by sessions AND workflow runs/agents. The
// workflow journal uses "complete"/"completed"/"cancelled" where sessions use "done"/"killed", so the
// mapping accepts both — sessions, runs and agents then render an identical indicator.
val DONE_STATES = setOf("done", "complete", "completed")
val FAILED_STATES = setOf("failed", "error")
val KILLED_STATES = setOf("killed", "cancelled", "canceled")
val RUNNING_STATES = setOf("running")

/** Status strings that mean a workflow run has completed (success, failure, or cancel). */
val WORKFLOW_DONE = DONE_STATES + FAILED_STATES + KILLED_STATES

/** Map a raw backend [status] string and optional [awaitingInput] flag to the display enum. Accepts
 * session and workflow synonyms (case-insensitive) so a session, a run and an agent in the same state
 * render the same indicator.
 * - done / complete / completed     → DONE
 * - failed / error                  → FAILED
 * - killed / cancelled / canceled   → KILLED
 * - running + awaitingInput=true    → IDLE  (streaming session waiting for user input)
 * - running                         → RUNNING
 * - anything else (pending/…)       → PENDING
 */
fun statusVisual(status: String, awaitingInput: Boolean?): StatusVisual {
    val s = status.trim().lowercase()
    return when {
        s in DONE_STATES -> StatusVisual.DONE
        s in FAILED_STATES -> StatusVisual.FAILED
        s in KILLED_STATES -> StatusVisual.KILLED
        s in RUNNING_STATES && awaitingInput == true -> StatusVisual.IDLE
        s in RUNNING_STATES -> StatusVisual.RUNNING
        else -> StatusVisual.PENDING
    }
}

/** Returns true when this workflow run has not yet reached a terminal state. */
fun WorkflowRun.isActive(): Boolean = status.trim().lowercase() !in WORKFLOW_DONE
