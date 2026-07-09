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
 * Polls workflow-run and outbox endpoints on behalf of the UI.
 *
 * Both [runsStream] and [outboxStream] use [pollFlow] at 2500 ms and apply a last-good-on-error
 * strategy: a failed tick re-emits the previous successful value so the UI never sees an empty
 * list due to a transient network blip.
 *
 * POLL STOP RULE: the Flow stops when the collector cancels (i.e. when the ViewModel's
 * coroutine scope is cancelled or the collector calls `cancel()`). There is intentionally NO
 * session-status stop baked in here — the ViewModel controls subscription lifetime. This is a
 * deliberate MVVM deviation from the old SessionScreen (line 558-568) which combined the poll
 * and stop-when-all-done logic in one loop.
 */
class WorkflowsRepository(
    private val api: AgenticApi,
    private val scope: CoroutineScope,
) {
    /**
     * Cold flow that polls `api.workflows(id)` every 2500 ms. Keeps the last successful list
     * when a tick throws (network blips do not surface to the UI as empty).
     */
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

    /**
     * Best-effort fetch of the agent transcript. Returns empty string on any error (matches old
     * WorkflowScreen line 246 behaviour — no error UI for a transcript load failure).
     */
    suspend fun agentTranscript(id: String, runId: String, agentId: String): String =
        runCatching { api.workflowAgent(id, runId, agentId) }.getOrElse {
            AppLog.v("WFlow", "agentTranscript failed: ${it.message}")
            ""
        }

    /**
     * Cold flow that polls `api.outbox(id)` every 2500 ms and maps each [dev.agentic.data.net.SharedFile]
     * to an [AttachmentNode]. `SharedFile.mtime` is a Double (fractional millis from Node.js
     * `statSync.mtimeMs`) and is converted with [Double.toLong] (matches SessionScreen line 561).
     * Last-good-on-error strategy applies identically to [runsStream].
     */
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
