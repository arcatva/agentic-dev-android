package dev.agentic.ui.newrequest

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.NewSessionReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.StagedUpload
import dev.agentic.data.net.Template
import dev.agentic.data.net.userMessage
import dev.agentic.data.repo.SessionsRepository
import dev.agentic.domain.PendingAttachment
import dev.agentic.ui.ModelCatalog
import dev.agentic.domain.UploadState
import dev.agentic.domain.applyTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-skill tri-state session override (only set by templates now — components are managed
 * globally on Settings): [Inherit] follows global; [ForceOn]/[ForceOff] override per-session.
 */
enum class Override { Inherit, ForceOn, ForceOff }

/**
 * Draft state for the Settings page's "Add MCP server" form. [transport] is "stdio" or "http";
 * fields not relevant to the selected transport are retained in state but ignored on add.
 */
data class McpDraft(
    val name: String = "",
    val transport: String = "stdio",
    // stdio fields
    val command: String = "",
    val args: String = "",       // space-separated, split on add
    val env: String = "",        // KEY=VALUE lines, split on add
    val url: String = "",
    val httpType: String = "http",  // "http" or "sse"
    val headers: String = "",       // KEY=VALUE lines, split on add
) {
    /** Validation error message, or null when the draft is submittable. */
    val validationError: String? get() = when {
        name.isBlank() -> "Name is required"
        name.trim() == "agentic" -> "Name must not be \"agentic\""
        transport !in setOf("stdio", "http") -> "Transport must be stdio or http"
        transport == "stdio" && command.isBlank() -> "Command is required for stdio transport"
        transport == "http" && url.isBlank() -> "URL is required for HTTP/SSE transport"
        transport == "http" && !isHttpUrl(url.trim()) -> "URL must start with http:// or https://"
        else -> null
    }
    val isValid: Boolean get() = validationError == null
}

/** Matches the backend's validate_mcp_def: http(s) scheme, non-empty host, no whitespace. */
private fun isHttpUrl(u: String): Boolean {
    val host = u.removePrefix("https://").removePrefix("http://")
    return host != u && host.isNotEmpty() && u.none { it.isWhitespace() }
}

/** Default session-CLAUDE.md text; tells each session how the agentic-dev worktree/PR workflow works. Branch-agnostic. */
val DEFAULT_CLAUDE_MD: String = """
    # Session workflow — agentic-dev

    You are one of several sessions running in agentic-dev. Each session works in its own git
    worktree on branch `agentic/<session>`, and other sessions may be editing the same repo
    concurrently in their own worktrees — treat the default branch as a moving target.

    A repo's own committed CLAUDE.md may OVERRIDE this workflow — if the repo you're working in
    specifies its own PR/merge rules (e.g. auto-merge after review), follow the repo's CLAUDE.md.

    ## Commit, then open a pull request (don't push to the default branch)
    - Do your work only on this session's `agentic/<session>` branch.
    - When a change is ready, commit it on the branch, push the branch, and open a pull request
      with `gh pr create` (it targets the repo's default branch). Do NOT merge it yourself and do
      NOT push straight to main/master — the user reviews and approves the PR.

    ## Conflicts are the user's call
    - If the branch conflicts with the default branch (or a rebase/merge fails), STOP — don't force
      a resolution.
    - Summarize what conflicts and ask the user how to proceed: have AI resolve it, or they will
      review/resolve manually. Wait for their decision before changing the conflicting files.
""".trimIndent()

/** Client-side attachment cap; rejects oversized picks before reading into a ByteArray (OOM guard).
 *  Unknown size (-1) proceeds — the server's upload_max_bytes is the second line of defense. */
private const val MAX_ATTACHMENT_BYTES: Long = 25L * 1024 * 1024

data class NewRequestUiState(
    val availableRepos: List<String> = emptyList(),
    // Skill catalog (kind=="skill") only — kept so applyTemplate can resolve a template's skill
    // list into hidden/forced-on at submit. Plugins and MCP have no per-session state.
    val availableSkillComponents: List<ComponentInfo> = emptyList(),
    val templates: List<Template> = emptyList(),
    val selectedRepos: List<String> = emptyList(),
    // Per-skill session overrides, set only by templates (see [Override]). On submit: ForceOff →
    // hiddenSkills, ForceOn → forcedOnSkills, Inherit → neither.
    val skillOverrides: Map<String, Override> = emptyMap(),
    val prompt: String = "",
    // Pre-filled with [DEFAULT_CLAUDE_MD] so the agent gets the worktree / PR workflow on launch;
    // blank → null (user cleared it → no extra guidance). Not touched by templates.
    val claudeMd: String = DEFAULT_CLAUDE_MD,
    // Defaults to the cached session-start default if already loaded, else null — init block
    // fetches the scoped catalog and fills it in. Using the cached default avoids a null→default
    // flicker when the screen is opened a second time.
    val model: String? = ModelCatalog.defaultSessionStartModelKey(),
    // Seed both mode=ultracode and effort=xhigh so the initial state mirrors tapping the Ultracode
    // notch (setMode + setEffort). A template or manual slider move still overrides these.
    val effort: String? = "xhigh",
    val mode: String? = "ultracode",
    val permissionMode: String? = null,
    // Files picked from the device to attach to this request. Each is uploaded to the pre-session
    // staging area; on submit the staged ones are listed in [NewSessionReq.stagedUploads] and
    // their paths embedded in the prompt's `[attached: ...]` marker.
    val attachments: List<PendingAttachment> = emptyList(),
    val submitting: Boolean = false,
    val createdId: String? = null,
    val error: String? = null,
)

class NewRequestViewModel(
    private val sessionsRepo: SessionsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewRequestUiState())
    val uiState: StateFlow<NewRequestUiState> = _uiState.asStateFlow()

    init {
        // Load catalogs concurrently — each is best-effort so failures don't block the others.
        viewModelScope.launch {
            try {
                val repoList = sessionsRepo.repos()
                _uiState.update {
                    it.copy(availableRepos = repoList.local + repoList.remote)
                }
            } catch (e: Exception) { AppLog.d("VM", "catalog repos load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                val allComponents = sessionsRepo.globalSettings()
                _uiState.update {
                    it.copy(availableSkillComponents = allComponents.filter { c -> c.kind == "skill" })
                }
            } catch (e: Exception) { AppLog.d("VM", "catalog components load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                val templates = sessionsRepo.templates()
                _uiState.update { it.copy(templates = templates) }
            } catch (e: Exception) { AppLog.d("VM", "catalog templates load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                sessionsRepo.modelCatalog()
            } catch (e: Exception) { AppLog.d("VM", "catalog modelCatalog load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                sessionsRepo.sessionStartModelCatalog()
                val defaultModel = ModelCatalog.defaultSessionStartModelKey()
                if (defaultModel != null) {
                    // Only set the default if the user hasn't already picked a model (manually
                    // or via a template) while the catalog was loading.
                    _uiState.update { if (it.model == null) it.copy(model = defaultModel) else it }
                }
            } catch (e: Exception) { AppLog.d("VM", "catalog sessionStartModelCatalog load failed: ${e.message}") }
        }
    }

    fun setRepos(repos: List<String>) { _uiState.update { it.copy(selectedRepos = repos) } }

    /** Set the tri-state override for a skill. Used only by template application and tests now. */
    fun setSkillOverride(id: String, override: Override) {
        _uiState.update { s -> s.copy(skillOverrides = s.skillOverrides + (id to override)) }
    }

    // Listed skills → Inherit, unlisted → ForceOff; empty list = no-op (don't accidentally hide all).
    internal fun setSkillsFromTemplate(skillNames: List<String>, available: List<ComponentInfo>) {
        val overrides = if (skillNames.isEmpty()) {
            emptyMap()
        } else {
            available.associate { s ->
                s.name to if (s.name in skillNames) Override.Inherit else Override.ForceOff
            }
        }
        _uiState.update { it.copy(skillOverrides = overrides) }
    }

    fun setPrompt(prompt: String) { _uiState.update { it.copy(prompt = prompt) } }
    fun setClaudeMd(claudeMd: String) { _uiState.update { it.copy(claudeMd = claudeMd) } }
    fun setModel(model: String?) { _uiState.update { it.copy(model = model) } }
    fun setEffort(effort: String?) { _uiState.update { it.copy(effort = effort) } }
    fun setMode(mode: String?) { _uiState.update { it.copy(mode = mode) } }
    fun setPermissionMode(permissionMode: String?) { _uiState.update { it.copy(permissionMode = permissionMode) } }

    /** Apply [template]: expand [vars] via domain [applyTemplate], then copy repos/model/effort/mode. */
    fun applyTemplate(t: Template, vars: Map<String, String>) {
        AppLog.d("VM", "applying template: ${t.name}")
        val s = _uiState.value
        _uiState.update {
            it.copy(
                prompt = applyTemplate(t.promptBody, vars),
                selectedRepos = t.repos,
                model = t.model,
                effort = t.effort,
                mode = t.mode,
                permissionMode = t.permissionMode,
            )
        }
        setSkillsFromTemplate(t.skills, s.availableSkillComponents)
    }

    // ── Attachments (pre-session staging) ───────────────────────────────────────

    /** In-flight staging-upload coroutines, keyed by [PendingAttachment.id] (URI string). Held so a
     *  removal can cancel mid-upload and so [submit] can join any still running before the prompt
     *  is composed. Synchronized: attachFiles spawns N coroutines that finish independently. */
    private val uploadJobs = mutableMapOf<String, Job>()

    /** Pick [uris] from the device and start staging them in parallel; each becomes a
     *  [PendingAttachment] (Pending → Uploading → Done/Failed). */
    fun attachFiles(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        AppLog.d("VM", "attaching ${uris.size} file(s)")
        val newOnes = uris.map { uri ->
            val (name, size) = queryDisplayNameAndSize(resolver, uri)
            PendingAttachment.of(uri.toString(), name, size)
        }
        _uiState.update { it.copy(attachments = it.attachments + newOnes) }
        for (att in newOnes) launchUpload(att, resolver)
    }

    /** Drop [id] from the pending list, cancelling its in-flight upload if any. Idempotent. */
    fun removePending(id: String) {
        AppLog.d("VM", "removing attachment: $id")
        synchronized(uploadJobs) { uploadJobs.remove(id) }?.cancel()
        _uiState.update { s -> s.copy(attachments = s.attachments.filterNot { it.id == id }) }
    }

    /** Spawn the staging-upload coroutine for [att], flipping Uploading → Done/Failed.
     *  Registered in [uploadJobs] BEFORE [Job.start] (LAZY) so the completion's remove can never
     *  run before the put under an immediate/synchronous completion (oversize reject, test dispatcher). */
    private fun launchUpload(att: PendingAttachment, resolver: ContentResolver) {
        AppLog.d("VM", "staging upload: ${att.displayName}")
        _uiState.update { s ->
            s.copy(attachments = s.attachments.map { if (it.id == att.id) it.copy(state = UploadState.Uploading) else it })
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val finalState: UploadState = if (att.sizeBytes > MAX_ATTACHMENT_BYTES) {
                // Reject before reading into memory (OOM guard). sizeBytes < 0 = unknown → proceed.
                UploadState.Failed("File too large (max ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB)")
            } else {
                val outcome = runCatching {
                    val bytes = readBytes(resolver, android.net.Uri.parse(att.localUri))
                    sessionsRepo.uploadStaging(bytes, att.displayName)
                }
                outcome.fold(
                    onSuccess = { r ->
                        when (r) {
                            is Outcome.Success -> {
                                AppLog.d("VM", "staging upload done: ${r.value.path}")
                                // Keep token + name so submit() can list this file in stagedUploads;
                                // path goes in the prompt marker.
                                UploadState.Done(r.value.path, r.value.token, r.value.name)
                            }
                            is Outcome.Failure -> {
                                AppLog.w("VM", "staging upload failed: ${r.error.userMessage()}")
                                UploadState.Failed(r.error.userMessage())
                            }
                        }
                    },
                    onFailure = { e ->
                        AppLog.w("VM", "staging upload failed: ${e.message}")
                        UploadState.Failed(e.message ?: "Couldn't read file")
                    },
                )
            }
            _uiState.update { s ->
                s.copy(attachments = s.attachments.map { if (it.id == att.id) it.copy(state = finalState) else it })
            }
            synchronized(uploadJobs) { uploadJobs.remove(att.id) }
        }
        synchronized(uploadJobs) { uploadJobs[att.id] = job }
        job.start()
    }

    /** Read a SAF/document URI to bytes via ContentResolver (handles content:// and file://). */
    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")

    /** Best-effort display name + size; falls back to last path segment and -1. */
    private fun queryDisplayNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx) ?: name
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    /** Build the create prompt body: user text + trailing `[attached: a, b]` marker listing each
     *  staged file's uploads/<name> path. Same marker the session transcript reducer parses/strips. */
    private fun composePromptWithMarker(text: String, attachments: List<PendingAttachment>): String {
        val paths = attachments.mapNotNull { (it.state as? UploadState.Done)?.path }
        if (paths.isEmpty()) return text
        val marker = "[attached: ${paths.joinToString(", ")}]"
        return if (text.isEmpty()) "\n\n$marker" else "$text\n\n$marker"
    }

    /** Submit form as a new session. Awaits in-flight uploads; on success sets createdId, on failure sets error. */
    fun submit() {
        AppLog.d("VM", "submitting new session")
        val s = _uiState.value
        _uiState.update { it.copy(submitting = true, error = null) }
        // Join staging uploads still in flight at Launch so the request's marker + stagedUploads
        // reflect final state (only Done files are sent).
        val jobsToAwait = synchronized(uploadJobs) { uploadJobs.values.toList() }
        viewModelScope.launch {
            try {
                jobsToAwait.forEach { it.join() }
            } catch (e: CancellationException) {
                throw e
            }
            // Re-read after joins: Done attachments carry token/name/path; Failed are dropped from
            // both the marker and stagedUploads (the user saw the error chip).
            val finalAtts = _uiState.value.attachments
            val staged = finalAtts.mapNotNull { att ->
                (att.state as? UploadState.Done)?.let { d ->
                    val token = d.token
                    val name = d.name
                    if (token != null && name != null) StagedUpload(token, name, d.path) else null
                }
            }
            val req = NewSessionReq(
                repos = s.selectedRepos,
                skills = emptyList(),
                // ForceOff → hidden; ForceOn → forcedOn; Inherit → neither list. Keyed by it.id
                // (the canonical component identifier).
                hiddenSkills     = s.availableSkillComponents.filter { s.skillOverrides[it.id] == Override.ForceOff }.map { it.id },
                forcedOnSkills   = s.availableSkillComponents.filter { s.skillOverrides[it.id] == Override.ForceOn  }.map { it.id },
                // Plugins/MCP have no per-session selection — sessions inherit the global config.
                hiddenPlugins    = emptyList(),
                forcedOnPlugins  = emptyList(),
                hiddenMcpServers   = emptyList(),
                forcedOnMcpServers = emptyList(),
                extraMcpServers  = emptyList(),
                prompt = composePromptWithMarker(s.prompt, finalAtts),
                model = s.model,
                // Ultracode always runs at xhigh — enforce the invariant in case a template set
                // mode=ultracode with a different effort.
                effort = if (s.mode == "ultracode") "xhigh" else s.effort,
                mode = s.mode,
                permissionMode = s.permissionMode,
                // Session-scoped CLAUDE.md; blank means "no extra guidance" → send null.
                claudeMd = s.claudeMd.ifBlank { null },
                stagedUploads = staged,
            )
            when (val outcome = sessionsRepo.create(req)) {
                is Outcome.Success -> {
                    AppLog.d("VM", "session created: ${outcome.value}")
                    _uiState.update {
                        it.copy(submitting = false, createdId = outcome.value)
                    }
                }
                is Outcome.Failure -> {
                    AppLog.w("VM", "create session failed: ${outcome.error}")
                    _uiState.update {
                        it.copy(submitting = false, error = "Failed to create session: ${outcome.error}")
                    }
                }
            }
        }
    }
}
