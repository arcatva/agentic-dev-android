package dev.agentic.ui.newrequest

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.McpServerDef
import dev.agentic.data.net.NewSessionReq
import dev.agentic.data.net.Outcome
import dev.agentic.data.log.AppLog
import dev.agentic.data.net.PluginInfo
import dev.agentic.data.net.SkillInfo
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
 * Per-component tri-state session override.
 * - [Inherit]: follow global setting (component appears in neither forced-on nor hidden list).
 * - [ForceOn]: force this component ON for this session (even if globally disabled).
 * - [ForceOff]: force this component OFF for this session (even if globally enabled).
 * Tap cycle: Inherit → ForceOn → ForceOff → Inherit.
 */
enum class Override { Inherit, ForceOn, ForceOff }

/**
 * Draft state for the "Add MCP server" inline form.
 * [transport] is "stdio" or "http". Fields not relevant to the selected transport
 * are retained in state but ignored on add (the form shows only the relevant ones).
 */
data class McpDraft(
    val name: String = "",
    val transport: String = "stdio",
    // stdio fields
    val command: String = "",
    val args: String = "",       // space-separated, split on add
    val env: String = "",        // KEY=VALUE lines, split on add
    // http/sse fields
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
        else -> null
    }
    val isValid: Boolean get() = validationError == null
}

/**
 * Default content pre-filled into the New-request "CLAUDE.md (optional)" field. It tells each
 * launched session how this multi-session / multi-worktree environment works: stay on your own
 * `agentic/<session>` branch, open a PR for the user to approve instead of pushing to the default
 * branch, and treat merge conflicts as the user's call (AI-resolve vs. manual). The user can edit or
 * clear it per session; cleared (blank) sends no extra guidance (see [NewRequestViewModel.submit]).
 * Branch-agnostic on purpose — `gh pr create` targets whatever the repo's default branch is
 * (`main` or `master`), so this works across repos.
 */
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

/** Client-side cap on a single attachment, checked before the file is read into memory. Reading an
 *  arbitrarily large device file into a ByteArray risks an OutOfMemoryError on Android; this fails
 *  oversized picks fast with a clear message instead. Only enforced when the picker reports a size
 *  (unknown size = -1 proceeds — rare, and the server's own upload_max_bytes still caps the upload). */
private const val MAX_ATTACHMENT_BYTES: Long = 25L * 1024 * 1024

data class NewRequestUiState(
    val availableRepos: List<String> = emptyList(),
    val availableSkills: List<SkillInfo> = emptyList(),
    val templates: List<Template> = emptyList(),
    val selectedRepos: List<String> = emptyList(),
    // ── Tri-state override maps (replaces binary selectedSkills/selectedPlugins) ──
    // Default is Inherit for all, meaning "follow global setting".
    // On submit: ForceOff → hiddenX, ForceOn → forcedOnX, Inherit → neither.
    val skillOverrides: Map<String, Override> = emptyMap(),
    val availablePlugins: List<PluginInfo> = emptyList(),
    val pluginOverrides: Map<String, Override> = emptyMap(),
    // ── MCP components (fetched from GET /api/global-settings, kind=="mcp") ──
    val mcpComponents: List<ComponentInfo> = emptyList(),
    val mcpOverrides: Map<String, Override> = emptyMap(),
    // ── Extra MCP servers (inline-add form) ──
    val extraMcpServers: List<McpServerDef> = emptyList(),
    val mcpDraft: McpDraft = McpDraft(),
    val prompt: String = "",
    // Session-scoped CLAUDE.md guidance, PRE-FILLED with [DEFAULT_CLAUDE_MD] (the multi-session
    // worktree / PR / conflict workflow). Sent verbatim on submit, so the agent gets it by default;
    // blank → null (the user cleared it ⇒ no extra guidance). The backend writes it into the session
    // dir so Claude Code loads it as project memory, on top of each repo's own CLAUDE.md. Not touched
    // by templates (a template leaves whatever the user currently has).
    val claudeMd: String = DEFAULT_CLAUDE_MD,
    // Model defaults to the cached session-start default if already loaded, otherwise null — the
    // init block fetches GET /api/models?scope=session_start and fills it in. Until the scoped
    // catalog arrives the slider shows "Default" (no override). Using the cached default avoids a
    // null→default flicker when the screen is opened a second time.
    // New requests default to Ultracode mode (the Effort slider's top "Ultracode" notch).
    // mode=ultracode forces xhigh effort + auto workflow orchestration; we seed effort=xhigh too so the
    // initial state mirrors exactly what tapping the Ultracode notch produces (setMode + setEffort). A
    // template or a manual slider move still overrides any of these.
    val model: String? = ModelCatalog.defaultSessionStartModelKey(),
    val effort: String? = "xhigh",
    val mode: String? = "ultracode",
    val permissionMode: String? = null,
    // Files the user picked from the device to attach to this request. Each is uploaded to the
    // pre-session staging area (POST /api/uploads) in the background; on submit, the successfully
    // staged ones are listed in [NewSessionReq.stagedUploads] and their paths embedded in the
    // prompt's `[attached: ...]` marker so the backend adopts them into the new session before the
    // first prompt runs. Each chip shows its [PendingAttachment.state]; remove is always available.
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
                val skills = sessionsRepo.skills()
                // Default every skill to Inherit (follow global) — no pre-selection.
                _uiState.update { it.copy(availableSkills = skills) }
            } catch (e: Exception) { AppLog.d("VM", "catalog skills load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                val plugins = sessionsRepo.plugins()
                // Default every plugin to Inherit (follow global).
                _uiState.update { it.copy(availablePlugins = plugins) }
            } catch (e: Exception) { AppLog.d("VM", "catalog plugins load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                // Fetch MCP components from global settings (kind == "mcp").
                val mcpList = sessionsRepo.globalSettings().filter { it.kind == "mcp" }
                _uiState.update { it.copy(mcpComponents = mcpList) }
            } catch (e: Exception) { AppLog.d("VM", "catalog mcp load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                val templates = sessionsRepo.templates()
                _uiState.update { it.copy(templates = templates) }
            } catch (e: Exception) { AppLog.d("VM", "catalog templates load failed: ${e.message}") }
        }
        viewModelScope.launch {
            // Full catalog: feeds modelLabel for session tags / workflow chips. Independent of the
            // scoped load below, so it gets its own coroutine (parallel, like the other catalogs).
            try {
                sessionsRepo.modelCatalog()
            } catch (e: Exception) { AppLog.d("VM", "catalog modelCatalog load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                // Claude-only session-start catalog — the only list this screen's Model slider offers.
                sessionsRepo.sessionStartModelCatalog()
                val defaultModel = ModelCatalog.defaultSessionStartModelKey()
                if (defaultModel != null) {
                    // Only set the default when the user hasn't already picked a model
                    // (either manually or via a template) while the catalog was loading.
                    _uiState.update { if (it.model == null) it.copy(model = defaultModel) else it }
                }
            } catch (e: Exception) { AppLog.d("VM", "catalog sessionStartModelCatalog load failed: ${e.message}") }
        }
    }

    fun setRepos(repos: List<String>) { _uiState.update { it.copy(selectedRepos = repos) } }

    /**
     * Set the tri-state override for a component. [kind] is "skill", "plugin", or "mcp".
     * [id] is the component's id/name. [override] is the new state.
     */
    fun setOverride(kind: String, id: String, override: Override) {
        _uiState.update { s ->
            when (kind) {
                "skill"  -> s.copy(skillOverrides  = s.skillOverrides  + (id to override))
                "plugin" -> s.copy(pluginOverrides  = s.pluginOverrides + (id to override))
                "mcp"    -> s.copy(mcpOverrides     = s.mcpOverrides    + (id to override))
                else -> s
            }
        }
    }

    /**
     * Map a template's skill list to overrides: listed skills → Inherit (unaffected), unlisted skills → ForceOff.
     * An empty [skillNames] means "all active" → leaves all at Inherit (don't accidentally hide everything).
     * Internal — used only by [applyTemplate].
     */
    internal fun setSkillsFromTemplate(skillNames: List<String>, available: List<SkillInfo>) {
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

    /** Update the "Add MCP server" draft form state. */
    fun updateMcpDraft(draft: McpDraft) { _uiState.update { it.copy(mcpDraft = draft) } }

    /**
     * Validate and add an MCP server from the current draft form.
     * Returns a user-readable error string on failure, null on success (draft is reset).
     */
    fun addMcpServer(): String? {
        val draft = _uiState.value.mcpDraft
        val err = draft.validationError
        if (err != null) return err
        val def = buildMcpServerDef(draft)
        _uiState.update { it.copy(extraMcpServers = it.extraMcpServers + def, mcpDraft = McpDraft()) }
        return null
    }

    /** Remove an added MCP server by name (idempotent). */
    fun removeMcpServer(name: String) {
        _uiState.update { it.copy(extraMcpServers = it.extraMcpServers.filterNot { s -> s.name == name }) }
    }

    private fun buildMcpServerDef(draft: McpDraft): McpServerDef {
        return if (draft.transport == "stdio") {
            McpServerDef(
                name = draft.name.trim(),
                command = draft.command.trim(),
                args = draft.args.trim().takeIf { it.isNotEmpty() }?.split("\\s+".toRegex()),
                env = parseKeyValueLines(draft.env),
            )
        } else {
            McpServerDef(
                name = draft.name.trim(),
                url = draft.url.trim(),
                type = draft.httpType,
                headers = parseKeyValueLines(draft.headers),
            )
        }
    }

    /** Parse "KEY=VALUE\nKEY2=VALUE2" into a map. Blank input → null (omitted from JSON). */
    private fun parseKeyValueLines(text: String): Map<String, String>? {
        if (text.isBlank()) return null
        return text.lines()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                else null
            }
            .takeIf { it.isNotEmpty() }
            ?.toMap()
    }

    /**
     * Apply [template] to the form: expand [vars] into the prompt body using the domain
     * [applyTemplate] pure function, then copy repos/model/effort/mode from the template.
     * Skills from the template are mapped to overrides via [setSkillsFromTemplate].
     */
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
        // Map template skill list to overrides: unlisted skills → ForceOff; listed/all → Inherit.
        setSkillsFromTemplate(t.skills, s.availableSkills)
    }

    // ── Attachments (pre-session staging) ───────────────────────────────────────

    /** In-flight staging-upload coroutines, keyed by [PendingAttachment.id] (the URI string). Held so
     *  a removal mid-upload can cancel the network call, and so [submit] can join any still running
     *  before composing the prompt. Synchronized because attachFiles spawns N coroutines that each
     *  finish independently on the main thread. */
    private val uploadJobs = mutableMapOf<String, Job>()

    /** Pick [uris] from the device and start staging them in parallel. Each becomes a
     *  [PendingAttachment] (Pending → Uploading → Done/Failed). Display name + size are queried up
     *  front so the chip can render immediately. Mirrors the session composer's attach flow but hits
     *  the pre-session staging endpoint, since there is no session id yet. */
    fun attachFiles(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        AppLog.d("VM", "attaching ${uris.size} file(s)")
        val newOnes = uris.map { uri ->
            val (name, size) = queryDisplayNameAndSize(resolver, uri)
            PendingAttachment.of(uri, name, size)
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

    /** Spawn the staging-upload coroutine for [att], flipping it Uploading → Done(token,name,path) or
     *  Failed(reason). On completion the job is dropped from [uploadJobs] regardless of outcome.
     *
     *  Started LAZY and registered in [uploadJobs] BEFORE [Job.start] so the completion block's
     *  `remove` can never run before the `put` (which would strand a finished job in the map — it
     *  races under an immediate/synchronous completion, e.g. an oversize reject or a test dispatcher). */
    private fun launchUpload(att: PendingAttachment, resolver: ContentResolver) {
        AppLog.d("VM", "staging upload: ${att.displayName}")
        _uiState.update { s ->
            s.copy(attachments = s.attachments.map { if (it.id == att.id) it.copy(state = UploadState.Uploading) else it })
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val finalState: UploadState = if (att.sizeBytes > MAX_ATTACHMENT_BYTES) {
                // Reject before reading into memory (OOM guard). sizeBytes < 0 means unknown → proceed.
                UploadState.Failed("File too large (max ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB)")
            } else {
                val outcome = runCatching {
                    val bytes = readBytes(resolver, att.localUri)
                    sessionsRepo.uploadStaging(bytes, att.displayName)
                }
                outcome.fold(
                    onSuccess = { r ->
                        when (r) {
                            is Outcome.Success -> {
                                AppLog.d("VM", "staging upload done: ${r.value.path}")
                                // Keep token + name so submit() can list this file in stagedUploads; path
                                // goes in the prompt marker.
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

    /** Read a SAF/document URI to bytes via the ContentResolver (handles content:// and file://).
     *  Throws if the stream can't be opened. */
    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")

    /** Best-effort display name + size for a picker URI; falls back to the last path segment and -1. */
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

    /** Build the create prompt body: the user's text plus a trailing `[attached: a, b]` marker listing
     *  the uploads/<name> path of every successfully-staged attachment. Matches the marker the session
     *  transcript reducer parses (and strips), so the optimistic prompt shown after navigation and the
     *  server's eventual echo normalize to the same text. No marker when nothing staged. */
    private fun composePromptWithMarker(text: String, attachments: List<PendingAttachment>): String {
        val paths = attachments.mapNotNull { (it.state as? UploadState.Done)?.path }
        if (paths.isEmpty()) return text
        val marker = "[attached: ${paths.joinToString(", ")}]"
        return if (text.isEmpty()) "\n\n$marker" else "$text\n\n$marker"
    }

    /**
     * Submit the form as a new session. Sets submitting=true, awaits any in-flight attachment uploads,
     * then calls sessionsRepo.create with the staged attachments + a marker-bearing prompt:
     * - Success: sets createdId (screen navigates away and may call setPendingPrompt).
     * - Failure: sets a user-readable error, clears submitting.
     */
    fun submit() {
        AppLog.d("VM", "submitting new session")
        val s = _uiState.value
        _uiState.update { it.copy(submitting = true, error = null) }
        // Join any staging uploads still in flight when the user tapped Launch, so the create request's
        // marker + stagedUploads reflect their final state (only Done files are sent).
        val jobsToAwait = synchronized(uploadJobs) { uploadJobs.values.toList() }
        viewModelScope.launch {
            try {
                jobsToAwait.forEach { it.join() }
            } catch (e: CancellationException) {
                throw e
            }
            // Re-read after the joins: Done attachments carry their staging token/name/path; Failed
            // ones are dropped from both the marker and stagedUploads (the user saw the error chip).
            val finalAtts = _uiState.value.attachments
            val staged = finalAtts.mapNotNull { att ->
                (att.state as? UploadState.Done)?.let { d ->
                    if (d.token != null && d.name != null) StagedUpload(d.token, d.name, d.path) else null
                }
            }
            val req = NewSessionReq(
                repos = s.selectedRepos,
                // The whitelist is dead post-cutover; gating is sent as override lists.
                skills = emptyList(),
                // ForceOff → hidden; ForceOn → forcedOn; Inherit → neither list
                hiddenSkills     = s.availableSkills.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOff },
                forcedOnSkills   = s.availableSkills.map { it.name }.filter { s.skillOverrides[it] == Override.ForceOn },
                hiddenPlugins    = s.availablePlugins.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOff },
                forcedOnPlugins  = s.availablePlugins.map { it.name }.filter { s.pluginOverrides[it] == Override.ForceOn },
                hiddenMcpServers   = s.mcpComponents.map { it.id }.filter { s.mcpOverrides[it] == Override.ForceOff },
                forcedOnMcpServers = s.mcpComponents.map { it.id }.filter { s.mcpOverrides[it] == Override.ForceOn },
                extraMcpServers  = s.extraMcpServers,
                prompt = composePromptWithMarker(s.prompt, finalAtts),
                model = s.model,
                // Ultracode always runs at xhigh effort — preserve that invariant even if a template
                // set mode=ultracode with a different effort (the Effort slider folds the two together).
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
