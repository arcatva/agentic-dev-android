package dev.agentic.domain

/**
 * Test doubles for the domain ports — the tests document the PORT contract, not any transport's
 * DTO. (Domain is a pure leaf; its tests must not reach into :core:network.)
 */
data class TestSession(
    override val id: String = "s",
    override val prompt: String = "",
    override val status: String = "done",
    override val errorKind: String? = null,
    override val awaitingInput: Boolean? = null,
    override val workflowRunning: Boolean = false,
    override val unreadEventId: Long = 0,
    override val ackedEventId: Long = 0,
) : SessionSnapshot

data class TestCommit(
    override val sha: String,
    override val parents: List<String> = emptyList(),
) : CommitLike

data class TestRun(override val status: String) : WorkflowRunState
