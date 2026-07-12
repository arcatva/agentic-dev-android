package dev.agentic.ui.workflow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.WorkflowAgent
import dev.agentic.data.net.WorkflowRun
import dev.agentic.data.repo.WorkflowsRepository
import dev.agentic.domain.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the workflow detail screen. [runs] are a continuous poll via
 * [WorkflowsRepository.runsStream]; [selectedAgent], [agentTranscript], [loadingTranscript] are
 * event-driven local overlays merged into [uiState].
 */
data class WorkflowUiState(
    val runs: List<WorkflowRun> = emptyList(),
    /** The currently selected agent: (runId, agent). Null when showing the main workflow view. */
    val selectedAgent: Pair<String, WorkflowAgent>? = null,
    val agentTranscript: String? = null,
    val loadingTranscript: Boolean = false,
)

/**
 * ViewModel for the workflow screen.
 *
 * Canonical pattern (mirrors HomeViewModel / SessionViewModel Phase 4a):
 * - [uiState] = combine(...).stateIn(WhileSubscribed(5_000)); upstream poll only runs while UI
 *   subscribed — no eager infinite collector in init, tests terminate.
 * - [selectAgent] cancels any prior in-flight fetch (race fix), sets loadingTranscript=true,
 *   fetches transcript, clears loading. Only latest selection's result applied (stale-guard).
 * - [selectMain] is synchronous (just updates the local MutableStateFlow).
 *
 * Live refresh: on every runsStream poll tick the combine lambda re-resolves the selected agent
 * from freshly-received runs so the header reflects latest state/model. While the selected agent
 * isActive, the combine also triggers a background re-fetch of its transcript (debounced via the
 * selectionState tick: once per poll interval, not per emission).
 */
class WorkflowViewModel(
    private val workflowsRepo: WorkflowsRepository,
    private val id: String,
) : ViewModel() {

    /** Secondary ctor for the NavHost call site (per-session SavedStateHandle). The wide-layout rail
     *  has no per-session nav entry, so it uses the primary ctor with an explicit id. */
    constructor(workflowsRepo: WorkflowsRepository, handle: SavedStateHandle) :
        this(workflowsRepo, requireNotNull(handle.get<String>("id")) { "WorkflowViewModel requires an 'id' arg" })

    /** The session id this VM polls — exposed so a host (AdaptiveHome) can key per-session state. */
    val sessionId: String get() = id

    /** Local event-driven selection state. Merged with live runsStream in [uiState]. */
    private data class SelectionState(
        val selectedAgent: Pair<String, WorkflowAgent>? = null,
        val agentTranscript: String? = null,
        val loadingTranscript: Boolean = false,
    )

    private val selectionState = MutableStateFlow(SelectionState())

    /** Guards agent-switch race: cancel in-flight transcript fetch before launching a new one, and
     *  only apply the result if the selection hasn't changed since launch (stale guard). */
    private var transcriptJob: Job? = null

    /** Single source of truth. WhileSubscribed means upstream poll runs ONLY while UI observes — no
     *  infinite collector leaking past the screen, tests terminate. Live: each runsStream emission
     *  re-resolves the selected agent from fresh runs so its header reflects live data, and kicks a
     *  background transcript refresh if still active. */
    val uiState: StateFlow<WorkflowUiState> =
        combine(workflowsRepo.runsStream(id), selectionState) { runs, sel ->
            val freshAgent = sel.selectedAgent?.let { (runId, agent) ->
                runs.firstOrNull { it.runId == runId }
                    ?.agents?.firstOrNull { it.agentId == agent.agentId }
                    ?.let { runId to it }
                    ?: sel.selectedAgent
            }
            if (freshAgent != null) {
                val (runId, agent) = freshAgent
                val agentFromRun = runs.firstOrNull { it.runId == runId }
                    ?.agents?.firstOrNull { it.agentId == agent.agentId }
                if (agentFromRun != null && agentFromRun.isActiveAgent()) {
                    launchTranscriptRefresh(runId, agentFromRun.agentId)
                }
            }
            WorkflowUiState(
                runs = runs,
                selectedAgent = freshAgent,
                agentTranscript = sel.agentTranscript,
                loadingTranscript = sel.loadingTranscript,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), WorkflowUiState())

    /** Select an agent to view its transcript. Cancels any in-flight fetch, sets
     *  [loadingTranscript]=true and [selectedAgent], fetches as a bounded one-shot. Stale-guard
     *  ensures only the LATEST selection's result is applied. */
    fun selectAgent(runId: String, agent: WorkflowAgent) {
        transcriptJob?.cancel()
        selectionState.update { it.copy(selectedAgent = runId to agent, loadingTranscript = true, agentTranscript = null) }
        val capturedKey = runId to agent.agentId
        transcriptJob = viewModelScope.launch {
            val transcript = workflowsRepo.agentTranscript(id, runId, agent.agentId)
            if (transcript != null) {
                AppLog.d("VM", "transcript loaded id=$id run=$runId agent=${agent.agentId} len=${transcript.length}")
            } else {
                AppLog.w("VM", "transcript fetch null id=$id run=$runId agent=${agent.agentId}")
            }
            selectionState.update { current ->
                val currentKey = current.selectedAgent?.let { it.first to it.second.agentId }
                if (currentKey == capturedKey) {
                    current.copy(agentTranscript = transcript, loadingTranscript = false)
                } else {
                    current
                }
            }
        }
    }

    /** Background transcript refresh on each poll tick while the selected agent isActive.
     *  Does NOT set loadingTranscript (silent). Skips if a user-initiated fetch is in flight. */
    private fun launchTranscriptRefresh(runId: String, agentId: String) {
        if (transcriptJob?.isActive == true) return
        val capturedKey = runId to agentId
        viewModelScope.launch {
            val transcript = workflowsRepo.agentTranscript(id, runId, agentId)
            selectionState.update { current ->
                val currentKey = current.selectedAgent?.let { it.first to it.second.agentId }
                if (currentKey == capturedKey && !current.loadingTranscript) {
                    current.copy(agentTranscript = transcript)
                } else {
                    current
                }
            }
        }
    }

    /** Resolve which run a clicked workflow card maps to. Prefers the card's exact [runId] (backend's
     *  workflowRun link) so the precise run opens even when several share a name. Falls back to the
     *  most recent matching [name] for older cards without a link. Null if nothing matches. */
    fun selectRun(runId: String?, name: String): String? {
        val runs = uiState.value.runs
        runId?.takeIf { it.isNotBlank() }
            ?.let { rid -> runs.firstOrNull { it.runId == rid } }
            ?.let { return it.runId }
        return runs.lastOrNull { it.name == name }?.runId
    }

    /** Return to the main workflow view, clearing agent selection and transcript. */
    fun selectMain() {
        transcriptJob?.cancel()
        transcriptJob = null
        selectionState.update { SelectionState() }
    }
}

/** True when this agent has not reached a terminal state. */
private fun WorkflowAgent.isActiveAgent(): Boolean =
    state.trim().lowercase() !in setOf("done", "complete", "completed", "failed", "error", "cancelled", "canceled", "killed")
