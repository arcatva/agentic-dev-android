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
 * UI state for the workflow detail screen.
 *
 * [runs] are driven by a continuous poll via [WorkflowsRepository.runsStream].
 * [selectedAgent], [agentTranscript], and [loadingTranscript] are event-driven local overlays
 * that are merged into [uiState] alongside the live runs stream.
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
 * - [uiState] is a `combine(...).stateIn(WhileSubscribed(5_000))` so the upstream poll only runs
 *   while the UI is subscribed — no eager infinite collector in init, tests terminate.
 * - [selectAgent] is a one-shot coroutine (bounded); it cancels any prior in-flight fetch
 *   (agent-switch race fix), sets loadingTranscript=true, fetches the transcript, then clears
 *   loading. Only the latest selection's result is applied (stale-guard via captured key).
 * - [selectMain] is synchronous (just updates the local MutableStateFlow).
 *
 * ui-2 live refresh: on every runsStream poll tick the combine lambda re-resolves the selected
 * agent from the freshly-received runs list so the header reflects the latest state/model.
 * Additionally, while the selected agent isActive, the combine triggers a background re-fetch of
 * its transcript (debounced via the selectionState tick: once per poll interval, not per emission).
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

    /**
     * Local event-driven selection state. Merged with the live runsStream in [uiState].
     * Holds everything except [runs] (which come from the repo stream).
     */
    private data class SelectionState(
        val selectedAgent: Pair<String, WorkflowAgent>? = null,
        val agentTranscript: String? = null,
        val loadingTranscript: Boolean = false,
    )

    private val selectionState = MutableStateFlow(SelectionState())

    /**
     * Guards against agent-switch race: cancel the in-flight transcript fetch before launching a
     * new one, and only apply the result if the selection hasn't changed since launch (stale guard).
     */
    private var transcriptJob: Job? = null

    /**
     * Single source of truth. WhileSubscribed means the upstream poll runs ONLY while the UI
     * observes — no infinite collector leaking past the screen, and tests terminate.
     *
     * ui-2: on each runsStream emission the combine re-resolves the selected agent from the fresh
     * runs list so its header (state/model) reflects live data. If the selected agent is still
     * active, kick off a background transcript refresh so it auto-updates while the run is live.
     */
    val uiState: StateFlow<WorkflowUiState> =
        combine(workflowsRepo.runsStream(id), selectionState) { runs, sel ->
            // Re-resolve the selected agent from the latest polled runs so state/model are live.
            val freshAgent = sel.selectedAgent?.let { (runId, agent) ->
                runs.firstOrNull { it.runId == runId }
                    ?.agents?.firstOrNull { it.agentId == agent.agentId }
                    ?.let { runId to it }
                    ?: sel.selectedAgent  // keep the stashed value if the run isn't in this tick
            }
            // If the selected agent is still active, trigger a background transcript refresh.
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

    /**
     * Select an agent to view its transcript.
     *
     * Cancels any in-flight transcript fetch (agent-switch race fix), immediately sets
     * [loadingTranscript]=true and [selectedAgent], then fetches the transcript as a bounded
     * one-shot coroutine. A stale-guard ensures only the LATEST selection's result is applied —
     * if a rapid switch races, the earlier fetch's result is discarded.
     */
    fun selectAgent(runId: String, agent: WorkflowAgent) {
        // Cancel prior in-flight fetch (race fix: only the latest result gets applied).
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
            // Stale guard: only apply if the selection hasn't changed.
            selectionState.update { current ->
                val currentKey = current.selectedAgent?.let { it.first to it.second.agentId }
                if (currentKey == capturedKey) {
                    current.copy(agentTranscript = transcript, loadingTranscript = false)
                } else {
                    current  // discard stale result
                }
            }
        }
    }

    /**
     * Background transcript refresh triggered on each poll tick while the selected agent isActive.
     * Does NOT set loadingTranscript (silent refresh). Only runs if there is no active user-initiated
     * fetch in flight (to avoid clobbering a fresh selectAgent() call).
     */
    private fun launchTranscriptRefresh(runId: String, agentId: String) {
        if (transcriptJob?.isActive == true) return  // user-initiated fetch in flight; skip
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

    /**
     * Resolve which run a clicked workflow card maps to. Prefers the card's exact [runId] (set by the
     * backend's `workflowRun` link via the card's tool_use id) so the precise run opens even when
     * several runs share a name. Falls back to the most recent run with a matching [name] for older
     * cards that never received a link. Returns null if nothing matches (the run may not be loaded yet).
     */
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

/** Returns true when this agent has not reached a terminal state. */
private fun WorkflowAgent.isActiveAgent(): Boolean =
    state.trim().lowercase() !in setOf("done", "complete", "completed", "failed", "error", "cancelled", "canceled", "killed")
