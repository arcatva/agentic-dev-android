package dev.agentic.domain

/**
 * Domain ports (hexagonal): the read-only shapes domain logic operates on. The wire DTOs in
 * :core:network IMPLEMENT these, so the dependency arrow points network -> domain — never the
 * reverse. Domain owns the vocabulary; transports adapt to it.
 */
interface SessionSnapshot {
    val id: String
    val prompt: String
    val status: String
    val errorKind: String?
    val awaitingInput: Boolean?
    val workflowRunning: Boolean
    val unreadEventId: Long
    val ackedEventId: Long
}

/** Minimal view of a workflow run needed to decide liveness. */
interface WorkflowRunState {
    val status: String
}

/** Minimal commit shape the graph layout engine needs. */
interface CommitLike {
    val sha: String
    val parents: List<String>
}
