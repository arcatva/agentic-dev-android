package dev.agentic.ui.newrequest

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    // ACTIVE skills. The single "Skills" picker starts with every skill selected (lit); deselecting
    // one hides it. On submit, hiddenSkills = availableSkills - selectedSkills.
    val selectedSkills: List<String> = emptyList(),
    // Installed Claude Code plugins (`<plugin>@<marketplace>` ids). Same blacklist model as
    // skills: every plugin starts selected (enabled); deselecting one disables it for this
    // session. On submit, hiddenPlugins = availablePlugins - selectedPlugins.
    val availablePlugins: List<PluginInfo> = emptyList(),
    val selectedPlugins: List<String> = emptyList(),
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
                // Default every skill to ACTIVE (selected) so the picker shows all chips lit.
                _uiState.update { it.copy(availableSkills = skills, selectedSkills = skills.map { s -> s.name }) }
            } catch (e: Exception) { AppLog.d("VM", "catalog skills load failed: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                val plugins = sessionsRepo.plugins()
                // Default every plugin to ACTIVE (selected), mirroring the skills picker.
                _uiState.update { it.copy(availablePlugins = plugins, selectedPlugins = plugins.map { p -> p.name }) }
            } catch (e: Exception) { AppLog.d("VM", "catalog plugins load failed: ${e.message}") }
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
    fun setSkills(skills: List<String>) { _uiState.update { it.copy(selectedSkills = skills) } }
    fun setPlugins(plugins: List<String>) { _uiState.update { it.copy(selectedPlugins = plugins) } }
    fun setPrompt(prompt: String) { _uiState.update { it.copy(prompt = prompt) } }
    fun setClaudeMd(claudeMd: String) { _uiState.update { it.copy(claudeMd = claudeMd) } }
    fun setModel(model: String?) { _uiState.update { it.copy(model = model) } }
    fun setEffort(effort: String?) { _uiState.update { it.copy(effort = effort) } }
    fun setMode(mode: String?) { _uiState.update { it.copy(mode = mode) } }
    fun setPermissionMode(permissionMode: String?) { _uiState.update { it.copy(permissionMode = permissionMode) } }

    /**
     * Apply [template] to the form: expand [vars] into the prompt body using the domain
     * [applyTemplate] pure function, then copy repos/skills/model/effort/mode from the template.
     */
    fun applyTemplate(t: Template, vars: Map<String, String>) {
        AppLog.d("VM", "applying template: ${t.name}")
        _uiState.update {
            it.copy(
                prompt = applyTemplate(t.promptBody, vars),
                selectedRepos = t.repos,
                // A template may restrict the active set; an empty list means "all active" (don't
                // accidentally hide everything). hiddenSkills is derived from this on submit.
                selectedSkills = t.skills.ifEmpty { it.availableSkills.map { s -> s.name } },
                model = t.model,
                effort = t.effort,
                mode = t.mode,
                permissionMode = t.permissionMode,
            )
        }
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
                // The whitelist is dead post-cutover; the picker expresses gating as a blacklist:
                // anything the user deselected (availableSkills - selectedSkills) is hidden.
                skills = emptyList(),
                hiddenSkills = s.availableSkills.map { it.name } - s.selectedSkills.toSet(),
                // Same blacklist derivation for plugins: anything deselected is disabled.
                hiddenPlugins = s.availablePlugins.map { it.name } - s.selectedPlugins.toSet(),
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
