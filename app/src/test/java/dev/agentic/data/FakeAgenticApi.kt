package dev.agentic.data

import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.AdoptSessionReq
import dev.agentic.data.net.Adoptable
import dev.agentic.data.net.CommitFile
import dev.agentic.data.net.ComponentInfo
import dev.agentic.data.net.CreateGroupReq
import dev.agentic.data.net.DetachResp
import dev.agentic.data.net.FileDiff
import dev.agentic.data.net.Group
import dev.agentic.data.net.ModelEntry
import dev.agentic.data.net.NewSessionReq
import dev.agentic.data.net.UpdateGroupReq
import dev.agentic.data.net.PatchSessionReq
import dev.agentic.data.net.PluginInfo
import dev.agentic.data.net.RepoCommits
import dev.agentic.data.net.RepoList
import dev.agentic.data.net.SearchResponse
import dev.agentic.data.net.Session
import dev.agentic.data.net.SessionDetail
import dev.agentic.data.net.SessionEventsResponse
import dev.agentic.data.net.SharedFile
import dev.agentic.data.net.SkillInfo
import dev.agentic.data.net.StagedUpload
import dev.agentic.data.net.Template
import dev.agentic.data.net.Usage
import dev.agentic.data.net.McpServerDef
import dev.agentic.data.net.WorkflowRun

/**
 * Minimal in-memory fake for [AgenticApi] used in unit tests.
 * Only login, registerDevice, onUnauthorized, baseUrl, token, and close() have real behaviour.
 * All other members stub-throw TODO() by default; override in tests if needed.
 */
class FakeAgenticApi(
    override var baseUrl: String = "http://fake-host",
    override var token: String? = null,
) : AgenticApi {

    override var onUnauthorized: (() -> Unit)? = null

    /** When non-null, login() returns this token. When null, throws the given loginException. */
    var loginTokenResult: String? = "fake-token"
    var loginException: Exception? = null

    /** Track whether close() was called. */
    var closed = false

    // ── Model catalog ───────────────────────────────────────────────────────────
    var modelsResult: List<ModelEntry> = emptyList()
    var modelsException: Exception? = null
    override suspend fun models(): List<ModelEntry> {
        modelsException?.let { throw it }
        return modelsResult
    }
    /** Session-start catalog (Claude-only, GET /api/models?scope=session_start).
     *  Reuses [modelsResult]/[modelsException] so the existing model-catalog tests work without
     *  a second scriptable surface: the VM calls sessionStartModels() (not models()) for the
     *  New-request default-model picker. Without this override the interface default returns
     *  emptyList() silently, leaving the model null even when modelsResult is populated. */
    override suspend fun sessionStartModels(): List<ModelEntry> {
        modelsException?.let { throw it }
        return modelsResult
    }

    // ── Scriptable surface for SessionsRepository tests ─────────────────────────

    /** Returned by session(id) (keyed by id; falls back to [sessionDetailDefault]). */
    var sessionDetails: MutableMap<String, SessionDetail> = mutableMapOf()
    /** Fallback for session(id) when no per-id entry exists; if also null, session() throws. */
    var sessionDetailDefault: SessionDetail? = null
    /** When set, session(id) throws this instead of returning a detail. */
    var sessionException: Exception? = null
    /** Per-id count of session(id) invocations (lets tests assert load() ran once). */
    val sessionCalls: MutableMap<String, Int> = mutableMapOf()
    /**
     * Per-id sequence of session(id) results consumed one-per-call (a thrown Result re-throws). Once
     * exhausted, session(id) falls back to [sessionDetails]/[sessionDetailDefault]. Used by the
     * reconnect loop tests to make the post-stream session() CONVERGE to a terminal status so the
     * loop breaks (otherwise it would retry forever).
     */
    val sessionScript: MutableMap<String, MutableList<Result<SessionDetail>>> = mutableMapOf()

    /** Frames the next stream() call replays into onLine, in order, then returns (stream closes). */
    var streamFrames: List<String> = emptyList()
    /** Number of times stream() was invoked, and the `since` offsets it was called with. */
    var streamCallCount = 0
    val streamSinceArgs: MutableList<Int?> = mutableListOf()
    /** The `limit` arg each session(id, limit) call was made with (windowed-load assertions). */
    val sessionLimitArgs: MutableList<Int?> = mutableListOf()
    /** When non-null, stream() throws this after replaying frames (simulates a closed socket). */
    var streamException: Exception? = null
    /**
     * Per-call stream script consumed one step per stream() invocation: each step replays its
     * [StreamStep.frames] into onLine then either returns or throws [StreamStep.throws]. Once
     * exhausted, stream() falls back to [streamFrames]/[streamException]. Lets a reconnect test
     * script distinct behaviour for the first vs. subsequent connection.
     */
    val streamScript: MutableList<StreamStep> = mutableListOf()

    /** One scripted stream() connection: replay [frames] then (if [throws] != null) throw it. */
    data class StreamStep(val frames: List<String> = emptyList(), val throws: Exception? = null)

    /**
     * When true (and no [streamScript] step applies), stream() replays [streamFrames] then suspends
     * open until cancelled instead of returning — models a persistent socket that never closes. Lets
     * a test observe a still-active reconnect loop (busy stays true; the job is alive to be killed).
     */
    var streamHoldsOpen = false

    /** Sequence of results sessions() yields per tick: a String key means "throw", a list means "emit".
     *  Falls back to [sessionsResult] once exhausted. */
    var sessionsScript: MutableList<Result<List<Session>>> = mutableListOf()
    var sessionsResult: List<Session> = emptyList()
    /** When set, sessions() suspends on this until completed — mirrors [usageGate] for list polls,
     *  so a test can hold the live session poll open and observe what the UI shows meanwhile. */
    var sessionsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    // ── Scriptable surface for WorkflowsRepository tests ────────────────────────

    /** Sequence of results workflows(id) yields per tick; falls back to [workflowsResult]. */
    var workflowsScript: MutableList<Result<List<WorkflowRun>>> = mutableListOf()
    var workflowsResult: List<WorkflowRun> = emptyList()

    /** Sequence of results outbox(id) yields per tick; falls back to [outboxResult]. */
    var outboxScript: MutableList<Result<List<SharedFile>>> = mutableListOf()
    var outboxResult: List<SharedFile> = emptyList()

    /** Returned by workflowAgent(); if [workflowAgentException] is set, throws instead. */
    var agentTranscriptResult: String = ""
    var workflowAgentException: Exception? = null

    // ── Scriptable surface for FilesRepository/CommitGraphViewModel tests ────────

    /** Returned by commits(); if [commitsException] is set, throws instead. */
    var commitsResult: List<RepoCommits> = emptyList()
    var commitsException: Exception? = null
    /** Per-call commits() script consumed one step per call (a thrown Result re-throws); falls back
     *  to [commitsResult]/[commitsException] once exhausted. Lets a test script load → reload. */
    val commitsScript: MutableList<Result<List<RepoCommits>>> = mutableListOf()
    /** Number of commits() invocations (lets a test assert a reload happened). */
    var commitsCallCount = 0

    /** Returned by commitFiles(); if [commitFilesException] is set, throws instead. */
    var commitFilesResult: List<CommitFile> = emptyList()
    var commitFilesException: Exception? = null
    /** Records each commitFiles() call as (id, repo, sha). */
    val commitFilesCalls: MutableList<Triple<String, String, String>> = mutableListOf()

    /** Returned by commitDiff(); if [commitDiffException] is set, throws instead. */
    var commitDiffResult: FileDiff = FileDiff()
    var commitDiffException: Exception? = null
    /** Records each commitDiff() call as (id, repo, sha, path). */
    val commitDiffCalls: MutableList<List<String>> = mutableListOf()

    /** If set, rewind() throws it. Records each call as (id, turnIndex). */
    var rewindException: Exception? = null
    val rewindCalls: MutableList<Pair<String, Int>> = mutableListOf()

    /** Tracks discard() calls (by session id). */
    val discardCalls: MutableList<String> = mutableListOf()

    // ── Scriptable surface for NewRequestViewModel tests ─────────────────────────

    /** Returned by repos(). */
    var reposResult: RepoList = RepoList()
    /** Returned by skills(). */
    var skillsResult: List<SkillInfo> = emptyList()
    /** Returned by plugins(). */
    var pluginsResult: List<PluginInfo> = emptyList()
    /** Returned by getTemplates(); if [getTemplatesException] is set, throws instead. */
    var templatesResult: List<Template> = emptyList()
    var getTemplatesException: Exception? = null
    /** Tracks putTemplates() calls. */
    val putTemplatesCalls: MutableList<List<Template>> = mutableListOf()
    /** Returned by create(); if [createException] is set, throws instead. */
    var createResult: String = "new-session-id"
    var createException: Exception? = null
    val createCalls: MutableList<NewSessionReq> = mutableListOf()
    /** Tracks fork() calls. */
    val forkCalls: MutableList<String> = mutableListOf()
    /** Returned by fork(); if [forkError] is set, fork() throws it instead. */
    var forkResult: String = "forked"
    var forkError: Throwable? = null
    /** Returned by usage(). If [usageException] is set, throws instead. */
    var usageResult: Usage = Usage()
    var usageException: Exception? = null
    /** When set, usage() suspends on this until completed — lets a test observe an in-flight refresh. */
    var usageGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** Tracks delete() calls. */
    val deleteCalls: MutableList<String> = mutableListOf()
    /** Tracks kill() calls. */
    val killCalls: MutableList<String> = mutableListOf()
    /** Tracks interrupt() calls. */
    val interruptCalls: MutableList<String> = mutableListOf()
    /** Tracks respondPermission() calls as (id, decision, feedback). */
    val permissionCalls: MutableList<Triple<String, String, String?>> = mutableListOf()
    /** When set, respondPermission() throws this instead of recording the call. */
    var respondPermissionException: Exception? = null

    /** When non-null, uploadFile() returns this path; otherwise throws [uploadException]. */
    var uploadPathResult: String? = "uploads/file.txt"
    var uploadException: Exception? = null

    /** Returned by followUp(); if [followUpException] is set, followUp() throws instead. */
    var followUpSince = 0
    var followUpException: Exception? = null
    val followUpCalls: MutableList<Triple<String, String, Boolean>> = mutableListOf()

    /** Scripted searchSessions response — return non-null to override the default empty response.
     *  Suspend so handlers can park on test-controlled gates (e.g. CompletableDeferred.await());
     *  a BLOCKING gate (runBlocking) inside runTest's single-threaded dispatcher deadlocks the
     *  whole suite — that hung testDebugUnitTest locally and in CI. */
    var searchHandler: (suspend (String) -> SearchResponse?)? = null
    val searchSessionsCalls: MutableList<String> = mutableListOf()

    override suspend fun login(password: String): String {
        loginException?.let { throw it }
        val t = loginTokenResult ?: error("No login token configured")
        token = t
        return t
    }

    override suspend fun registerDevice(token: String) {
        // best-effort stub; no-op in tests unless overridden
    }

    override fun close() { closed = true }

    // ── All other members are stubs ───────────────────────────────────────────

    override suspend fun sessions(): List<Session> {
        sessionsGate?.await()
        if (sessionsScript.isNotEmpty()) return sessionsScript.removeAt(0).getOrThrow()
        return sessionsResult
    }

    /** Returns the [Session] from [sessionDetails] (or [sessionDetailDefault]) — mirrors the real
     *  AgenticApi.get, which returns SessionDetail.session, and stays consistent with [session]. */
    override suspend fun get(id: String): Session =
        sessionDetails[id]?.session ?: sessionDetailDefault?.session ?: TODO("no Session configured for $id")

    override suspend fun patchSession(id: String, req: PatchSessionReq): Session {
        patchSessionCalls.add(id to req)
        return get(id)
    }

    override suspend fun session(id: String, limit: Int?): SessionDetail {
        sessionCalls[id] = (sessionCalls[id] ?: 0) + 1
        sessionLimitArgs.add(limit)
        sessionException?.let { throw it }
        sessionScript[id]?.takeIf { it.isNotEmpty() }?.let { return it.removeAt(0).getOrThrow() }
        return sessionDetails[id] ?: sessionDetailDefault ?: TODO("no SessionDetail configured for $id")
    }

    var sessionEventsResult: SessionEventsResponse? = null
    var sessionEventsException: Exception? = null
    val sessionEventsCalls: MutableList<String> = mutableListOf()
    /** Records each sessionEvents call's cursor (before, after, around) for windowing assertions. */
    val sessionEventsCursors: MutableList<Triple<Long?, Long?, Long?>> = mutableListOf()
    /** The `limit` arg each sessionEvents(id, limit, …) call was made with. The load/refetch paths pass
     *  [SessionsRepository.INITIAL_LOG_LIMIT]; the refresh/poll paths pass limit=1. Lets a windowed-load
     *  test assert the request limit without threading it through the cursor triple. */
    val sessionEventsLimitArgs: MutableList<Int?> = mutableListOf()
    /**
     * Per-id sequence of sessionEvents(id, …) results consumed one-per-call (a thrown Result re-throws).
     * Once exhausted, sessionEvents() falls back to [pagedEvents]/[sessionEventsResult]. This is the
     * events-surface twin of [sessionScript]: the reconnect-loop tests use it to make the post-stream
     * refetch CONVERGE to a terminal session so the loop breaks (it would otherwise retry forever), and
     * the load→followUp-poll→refetch tests to script distinct responses per call.
     */
    val sessionEventsScript: MutableMap<String, MutableList<Result<SessionEventsResponse>>> = mutableMapOf()
    /** Paged-log mode (bounded-window tests): when set for an id, [sessionEvents] serves tail/before/
     *  after/around windows over this IMMUTABLE event list, event k occupying rendered line k (stride
     *  1), modeling the server contract — `before`/`after` EXCLUSIVE, capped at [pagedPageLimit] (or the
     *  requested limit), `hasMore` = window start > 0. */
    val pagedEvents: MutableMap<String, List<kotlinx.serialization.json.JsonElement>> = mutableMapOf()
    /** Overrides the effective per-window event cap in paged mode, so a test can use tiny pages without
     *  needing >100 events (the client always requests limit=100). Null = use the requested limit. */
    var pagedPageLimit: Int? = null

    override suspend fun sessionEvents(id: String, limit: Int?, before: Long?, after: Long?, around: Long?): SessionEventsResponse {
        sessionEventsCalls.add(id)
        sessionEventsCursors.add(Triple(before, after, around))
        sessionEventsLimitArgs.add(limit)
        sessionEventsException?.let { throw it }
        sessionEventsScript[id]?.takeIf { it.isNotEmpty() }?.let { return it.removeAt(0).getOrThrow() }
        val all = pagedEvents[id] ?: return sessionEventsResult ?: TODO("no SessionEventsResponse configured for $id")
        val cap = pagedPageLimit ?: (limit ?: 100).coerceIn(1, 100)
        val n = all.size
        val total = n.toLong()   // one rendered line per event (stride 1)
        val idxs: List<Int> = when {
            before != null -> (0 until n).filter { it.toLong() < before }.takeLast(cap)
            after != null  -> (0 until n).filter { it.toLong() > after }.take(cap)
            around != null -> { val c = around.toInt().coerceIn(0, maxOf(0, n - 1))
                                val s = (c - cap / 2).coerceAtLeast(0); (s until minOf(s + cap, n)).toList() }
            else           -> (0 until n).toList().takeLast(cap)   // tail (no cursor)
        }
        val startLine = idxs.firstOrNull()?.toLong() ?: total
        val session = sessionDetails[id]?.session ?: sessionDetailDefault?.session
            ?: Session(id = id, prompt = "", status = "done")
        return SessionEventsResponse(
            session = session,
            events = idxs.map { all[it] },
            latestEventId = total,
            firstEventLine = startLine,
            hasMore = startLine > 0,
            hasMoreAfter = before != null || after != null || around != null,
        )
    }

    override suspend fun usage(): Usage {
        usageGate?.await()
        usageException?.let { throw it }
        return usageResult
    }
    override suspend fun workflows(id: String): List<WorkflowRun> {
        if (workflowsScript.isNotEmpty()) return workflowsScript.removeAt(0).getOrThrow()
        return workflowsResult
    }
    override suspend fun workflowAgent(id: String, runId: String, agentId: String): String {
        workflowAgentException?.let { throw it }
        return agentTranscriptResult
    }
    override suspend fun repos(): RepoList = reposResult
    override suspend fun skills(): List<SkillInfo> = skillsResult
    override suspend fun plugins(): List<PluginInfo> = pluginsResult
    override suspend fun create(req: NewSessionReq): String {
        createCalls.add(req)
        createException?.let { throw it }
        return createResult
    }

override suspend fun fork(id: String): String {
        forkCalls.add(id)
        forkError?.let { throw it }
        return forkResult
    }

    override suspend fun followUp(id: String, prompt: String, setTitle: Boolean, model: String?, effort: String?, permissionMode: String?): Int {
        followUpCalls.add(Triple(id, prompt, setTitle))
        followUpException?.let { throw it }
        return followUpSince
    }

    override suspend fun searchSessions(q: String): SearchResponse {
        searchSessionsCalls.add(q)
        return searchHandler?.invoke(q) ?: SearchResponse(query = q, results = emptyList())
    }

    override suspend fun uploadFile(id: String, bytes: ByteArray, filename: String): String {
        uploadException?.let { throw it }
        return uploadPathResult ?: error("No upload path configured")
    }

    /** When non-null, uploadStaging() returns this; otherwise throws [uploadStagingException].
     *  [uploadStagingCalls] records each uploaded filename so tests can assert what was staged. */
    var uploadStagingResult: StagedUpload? = StagedUpload(token = "stage-token", name = "file.txt", path = "uploads/file.txt")
    var uploadStagingException: Exception? = null
    val uploadStagingCalls: MutableList<String> = mutableListOf()
    override suspend fun uploadStaging(bytes: ByteArray, filename: String): StagedUpload {
        uploadStagingCalls.add(filename)
        uploadStagingException?.let { throw it }
        return uploadStagingResult ?: error("No staging upload configured")
    }
    /** Bytes returned by [fileBytes]; tests set this. [fileBytesCalls] records each (id, path). */
    var fileBytesResult: ByteArray = ByteArray(0)
    var fileBytesException: Throwable? = null
    val fileBytesCalls = mutableListOf<Pair<String, String>>()
    /** Progress fractions [fileBytes] will replay into onProgress; whether onProgress was non-null. */
    var fileBytesProgress: List<Float?> = emptyList()
    var fileBytesOnProgressWasNonNull = false
    override suspend fun fileBytes(id: String, path: String, onProgress: ((Float?) -> Unit)?): ByteArray {
        fileBytesCalls.add(id to path)
        fileBytesOnProgressWasNonNull = onProgress != null
        fileBytesProgress.forEach { onProgress?.invoke(it) }
        fileBytesException?.let { throw it }
        return fileBytesResult
    }
    override suspend fun outbox(id: String): List<SharedFile> {
        if (outboxScript.isNotEmpty()) return outboxScript.removeAt(0).getOrThrow()
        return outboxResult
    }
    override suspend fun kill(id: String) { killCalls.add(id) }
    override suspend fun interrupt(id: String) { interruptCalls.add(id) }
    override suspend fun respondPermission(id: String, decision: String, feedback: String?) {
        permissionCalls.add(Triple(id, decision, feedback))
        respondPermissionException?.let { throw it }
    }
    override suspend fun delete(id: String) { deleteCalls.add(id) }

    override suspend fun stream(id: String, since: Int?, onLine: suspend (String) -> Unit) {
        streamCallCount++
        streamSinceArgs.add(since)
        if (streamScript.isNotEmpty()) {
            val step = streamScript.removeAt(0)
            step.frames.forEach { onLine(it) }
            step.throws?.let { throw it }
            return
        }
        streamFrames.forEach { onLine(it) }
        streamException?.let { throw it }
        if (streamHoldsOpen) kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun getTemplates(): List<Template> {
        getTemplatesException?.let { throw it }
        return templatesResult
    }
    override suspend fun putTemplates(templates: List<Template>) { putTemplatesCalls.add(templates) }
    // ── Group CRUD scriptable surface ──────────────────────────────────────────
    var groupsResult: List<Group> = emptyList()
    val groupsScript: MutableList<Result<List<Group>>> = mutableListOf()
    val createGroupCalls: MutableList<CreateGroupReq> = mutableListOf()
    val updateGroupCalls: MutableList<Pair<String, UpdateGroupReq>> = mutableListOf()
    val deleteGroupCalls: MutableList<String> = mutableListOf()
    val patchSessionCalls: MutableList<Pair<String, PatchSessionReq>> = mutableListOf()

    override suspend fun listGroups(): List<Group> {
        if (groupsScript.isNotEmpty()) return groupsScript.removeAt(0).getOrThrow()
        return groupsResult
    }
    override suspend fun createGroup(req: CreateGroupReq): Group {
        createGroupCalls.add(req)
        return Group(id = "g-${createGroupCalls.size}", name = req.name, icon = req.icon)
    }
    override suspend fun updateGroup(id: String, req: UpdateGroupReq): Group {
        updateGroupCalls.add(id to req)
        return Group(id = id, name = req.name ?: "renamed", icon = req.icon)
    }
    override suspend fun deleteGroup(id: String) { deleteGroupCalls.add(id) }
    override suspend fun commits(id: String): List<RepoCommits> {
        commitsCallCount++
        if (commitsScript.isNotEmpty()) return commitsScript.removeAt(0).getOrThrow()
        commitsException?.let { throw it }
        return commitsResult
    }
    override suspend fun commitFiles(id: String, repo: String, sha: String): List<CommitFile> {
        commitFilesCalls.add(Triple(id, repo, sha))
        commitFilesException?.let { throw it }
        return commitFilesResult
    }
    override suspend fun commitDiff(id: String, repo: String, sha: String, path: String): FileDiff {
        commitDiffCalls.add(listOf(id, repo, sha, path))
        commitDiffException?.let { throw it }
        return commitDiffResult
    }
    override suspend fun rewind(id: String, turnIndex: Int) {
        rewindCalls.add(id to turnIndex)
        rewindException?.let { throw it }
    }
    override suspend fun discard(id: String) { discardCalls.add(id) }
    /** Tracks each ackSession() call as (id, eventId). Default ackSession was a silent no-op — leave it
     *  un-scriptable so tests that DO want to assert an ack call add an explicit [ackSessionCalls]
     *  member without breaking callers that ignore it (the ack path is best-effort on the wire). */
    val ackSessionCalls: MutableList<Pair<String, Long>> = mutableListOf()
    override suspend fun ackSession(id: String, eventId: Long) {
        ackSessionCalls.add(id to eventId)
    }

    // ── Adopt / detach scriptable surface ──────────────────────────────────────
    /** Returned by adoptable(); if [adoptableException] is set, throws instead. */
    var adoptableResult: List<Adoptable> = emptyList()
    var adoptableException: Exception? = null
    val adoptableCalls: MutableList<Unit> = mutableListOf()
    override suspend fun adoptable(): List<Adoptable> {
        adoptableCalls.add(Unit)
        adoptableException?.let { throw it }
        return adoptableResult
    }
    /** Returned by adoptSession(); if [adoptSessionException] is set, throws instead. [adoptSessionCalls]
     *  records each (claudeSessionId, cwd) pair. */
    var adoptSessionResult: String = "adopted-id"
    var adoptSessionException: Exception? = null
    val adoptSessionCalls: MutableList<AdoptSessionReq> = mutableListOf()
    override suspend fun adoptSession(req: AdoptSessionReq): String {
        adoptSessionCalls.add(req)
        adoptSessionException?.let { throw it }
        return adoptSessionResult
    }
    /** Returned by detach(); if [detachException] is set, throws instead. The defaults model the
     *  happy-path CLI hand-off shape so callers can render the resume command without wiring each
     *  field. [detachCalls] records each session id. */
    var detachResult: DetachResp = DetachResp(cwd = "/tmp/example", claudeSessionId = "csid", resumeCmd = "claude --resume csid")
    var detachException: Exception? = null
    val detachCalls: MutableList<String> = mutableListOf()
    override suspend fun detach(id: String): DetachResp {
        detachCalls.add(id)
        detachException?.let { throw it }
        return detachResult
    }

    // ── Scriptable surface for GlobalSettingsViewModel tests (S5a) ────────────
    /** Returned by getGlobalSettings(); if [getGlobalSettingsException] is set, throws instead. */
    var globalSettingsResult: List<ComponentInfo> = emptyList()
    var getGlobalSettingsException: Exception? = null
    var getGlobalSettingsCallCount = 0

    override suspend fun getGlobalSettings(): List<ComponentInfo> {
        getGlobalSettingsCallCount++
        getGlobalSettingsException?.let { throw it }
        return globalSettingsResult
    }

    /** Returned by toggleGlobalComponent(); if [toggleGlobalComponentException] is set, throws instead. */
    var toggleGlobalComponentResult: List<ComponentInfo> = emptyList()
    var toggleGlobalComponentException: Exception? = null
    /** Records each toggleGlobalComponent() call as Triple(kind, id, enabled). */
    val toggleGlobalComponentCalls: MutableList<Triple<String, String, Boolean>> = mutableListOf()

    override suspend fun toggleGlobalComponent(kind: String, id: String, enabled: Boolean): List<ComponentInfo> {
        toggleGlobalComponentCalls.add(Triple(kind, id, enabled))
        toggleGlobalComponentException?.let { throw it }
        return toggleGlobalComponentResult
    }

    // ── Scriptable surface for GlobalSettings CRUD tests (S5c) ──────────────────
    var addSkillResult: List<ComponentInfo> = emptyList()
    var addSkillException: Exception? = null
    val addSkillCalls: MutableList<Pair<String, String>> = mutableListOf()
    override suspend fun addSkill(name: String, description: String): List<ComponentInfo> {
        addSkillCalls.add(name to description)
        addSkillException?.let { throw it }
        return addSkillResult
    }

    var deleteSkillResult: List<ComponentInfo> = emptyList()
    var deleteSkillException: Exception? = null
    val deleteSkillCalls: MutableList<String> = mutableListOf()
    override suspend fun deleteSkill(name: String): List<ComponentInfo> {
        deleteSkillCalls.add(name)
        deleteSkillException?.let { throw it }
        return deleteSkillResult
    }

    var installPluginResult: List<ComponentInfo> = emptyList()
    var installPluginException: Exception? = null
    val installPluginCalls: MutableList<String> = mutableListOf()
    override suspend fun installPlugin(id: String): List<ComponentInfo> {
        installPluginCalls.add(id)
        installPluginException?.let { throw it }
        return installPluginResult
    }

    var uninstallPluginResult: List<ComponentInfo> = emptyList()
    var uninstallPluginException: Exception? = null
    val uninstallPluginCalls: MutableList<String> = mutableListOf()
    override suspend fun uninstallPlugin(id: String): List<ComponentInfo> {
        uninstallPluginCalls.add(id)
        uninstallPluginException?.let { throw it }
        return uninstallPluginResult
    }

    var addMcpServerResult: List<ComponentInfo> = emptyList()
    var addMcpServerException: Exception? = null
    val addMcpServerCalls: MutableList<McpServerDef> = mutableListOf()
    override suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> {
        addMcpServerCalls.add(def)
        addMcpServerException?.let { throw it }
        return addMcpServerResult
    }

    var deleteMcpServerResult: List<ComponentInfo> = emptyList()
    var deleteMcpServerException: Exception? = null
    val deleteMcpServerCalls: MutableList<String> = mutableListOf()
    override suspend fun deleteMcpServer(name: String): List<ComponentInfo> {
        deleteMcpServerCalls.add(name)
        deleteMcpServerException?.let { throw it }
        return deleteMcpServerResult
    }
}
