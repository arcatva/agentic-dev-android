package dev.agentic.data.repo

import dev.agentic.data.log.AppLog
import dev.agentic.data.log.PollLogState
import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.WorkflowRun
import dev.agentic.data.util.pollFlow
import dev.agentic.domain.AttachmentNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Polls workflow-run and outbox endpoints on behalf of the UI. Both streams use [pollFlow] at 2500 ms
 * and keep-last-good-on-error so a transient blip never surfaces as an empty list.
 * The Flow stops when the collector cancels (the ViewModel controls subscription lifetime; no session-status stop is baked in here).
 */
class WorkflowsRepository(
    private val api: AgenticApi,
    private val scope: CoroutineScope,
) {
    /** Cold flow that polls `api.workflows(id)` every 2500 ms, keeping the last successful list on error. */
    fun runsStream(id: String): Flow<List<WorkflowRun>> = flow {
        val pollLog = PollLogState()
        val subject = "runs(id=${id.take(8)})"
        var lastGood: List<WorkflowRun> = emptyList()
        pollFlow(2_500L) {
            try {
                api.workflows(id).also { lastGood = it }
                    .also { pollLog.onTick("WFlow", subject, ok = true) }
            } catch (e: Exception) {
                pollLog.onTick("WFlow", subject, ok = false)
                lastGood
            }
        }.collect { emit(it) }
    }

    /** Best-effort: empty string on any error (no error UI for a transcript load failure). */
    suspend fun agentTranscript(id: String, runId: String, agentId: String): String =
        runCatching { api.workflowAgent(id, runId, agentId) }.getOrElse {
            AppLog.v("WFlow", "agentTranscript failed: ${it.message}")
            ""
        }

    /** Cold flow that polls `api.outbox(id)` every 2500 ms; SharedFile.mtime is Double (fractional ms from Node.js statSync.mtimeMs), converted with .toLong(). Last-good-on-error same as [runsStream]. */
    fun outboxStream(id: String): Flow<List<AttachmentNode>> = flow {
        val pollLog = PollLogState()
        val subject = "outbox(id=${id.take(8)})"
        var lastGood: List<AttachmentNode> = emptyList()
        pollFlow(2_500L) {
            try {
                api.outbox(id)
                    .map { AttachmentNode(path = it.path, at = it.mtime.toLong()) }
                    .also { lastGood = it }
                    .also { pollLog.onTick("WFlow", subject, ok = true) }
            } catch (e: Exception) {
                pollLog.onTick("WFlow", subject, ok = false)
                lastGood
            }
        }.collect { emit(it) }
    }
}
