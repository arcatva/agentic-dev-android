package dev.agentic.domain


/** Distinct visuals a session status can map to (enum so AnimatedContent only re-animates on meaningful transitions). */
enum class StatusVisual { DONE, FAILED, KILLED, RUNNING, IDLE, PENDING }

/** Terminal session status strings. */
val TERMINAL = setOf("done", "failed", "killed")

// Status synonyms (trim+lowercase) shared by sessions AND workflow runs/agents — workflow journal uses "complete/completed/cancelled" where sessions use "done/killed".
val DONE_STATES = setOf("done", "complete", "completed")
val FAILED_STATES = setOf("failed", "error")
val KILLED_STATES = setOf("killed", "cancelled", "canceled")
val RUNNING_STATES = setOf("running")

/** Workflow run terminal set (success/failure/cancel). */
val WORKFLOW_DONE = DONE_STATES + FAILED_STATES + KILLED_STATES

/** Map raw [status] (+ optional awaitingInput) to a visual; accepts session+workflow synonyms so a session/run/agent in the same state render identical. */
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
fun WorkflowRunState.isActive(): Boolean = status.trim().lowercase() !in WORKFLOW_DONE
