package dev.agentic.data

import dev.agentic.data.net.AgenticApi
import dev.agentic.data.net.AdoptSessionReq
import dev.agentic.data.net.Adoptable
import dev.agentic.data.net.CatalogSkill
import dev.agentic.data.net.SkillCatalogResp
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

// Minimal in-memory fake for AgenticApi; only login/registerDevice/onUnauthorized/baseUrl/token/close have real behaviour, others stub-throw TODO().
class FakeAgenticApi(
    override var baseUrl: String = "http://fake-host",
    override var token: String? = null,
) : AgenticApi {

    override var onUnauthorized: (() -> Unit)? = null

    // When non-null, login() returns this token.
    var loginTokenResult: String? = "fake-token"
    var loginException: Exception? = null

    // Track whether close() was called.
    var closed = false

    var modelsResult: List<ModelEntry> = emptyList()
    var modelsException: Exception? = null
    override suspend fun models(): List<ModelEntry> {
        modelsException?.let { throw it }
        return modelsResult
    }
    // Session-start catalog reuses modelsResult/modelsException so the VM's sessionStartModels() call for the new-request picker surfaces the same scripted data.
    override suspend fun sessionStartModels(): List<ModelEntry> {
        modelsException?.let { throw it }
        return modelsResult
    }


    // Returned by session(id) (keyed by id; falls back to [sessionDetailDefault]).
    var sessionDetails: MutableMap<String, SessionDetail> = mutableMapOf()
    // Fallback for session(id) when no per-id entry exists; if also null, session() throws.
    var sessionDetailDefault: SessionDetail? = null
    // When set, session(id) throws this instead of returning a detail.
    var sessionException: Exception? = null
    // Per-id count of session(id) invocations (lets tests assert load() ran once).
    val sessionCalls: MutableMap<String, Int> = mutableMapOf()
    // Per-id sequence of session(id) results consumed one-per-call; falls back to sessionDetails/sessionDetailDefault.
    val sessionScript: MutableMap<String, MutableList<Result<SessionDetail>>> = mutableMapOf()

    // Frames the next stream() call replays into onLine, in order, then returns (stream closes).
    var streamFrames: List<String> = emptyList()
    // Number of times stream() was invoked, and the `since` offsets it was called with.
    var streamCallCount = 0
    val streamSinceArgs: MutableList<Int?> = mutableListOf()
    // The `limit` arg each session(id, limit) call was made with (windowed-load assertions).
    val sessionLimitArgs: MutableList<Int?> = mutableListOf()
    // When non-null, stream() throws this after replaying frames (simulates a closed socket).
    var streamException: Exception? = null
    // Per-call stream script consumed one step per stream() invocation; falls back to streamFrames/streamException.
    val streamScript: MutableList<StreamStep> = mutableListOf()

    // One scripted stream() connection: replay [frames] then (if [throws] != null) throw it.
    data class StreamStep(val frames: List<String> = emptyList(), val throws: Exception? = null)

    // When true, stream() replays streamFrames then suspends open until cancelled — models a persistent socket for reconnect-loop tests.
    var streamHoldsOpen = false

    // Sequence of results sessions() yields per tick: a String key means "throw", a list means "emit".
    var sessionsScript: MutableList<Result<List<Session>>> = mutableListOf()
    var sessionsResult: List<Session> = emptyList()
    // When set, sessions() suspends on this until completed — mirrors [usageGate] for list polls,
    var sessionsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null


    // Sequence of results workflows(id) yields per tick; falls back to [workflowsResult].
    var workflowsScript: MutableList<Result<List<WorkflowRun>>> = mutableListOf()
    var workflowsResult: List<WorkflowRun> = emptyList()

    // Sequence of results outbox(id) yields per tick; falls back to [outboxResult].
    var outboxScript: MutableList<Result<List<SharedFile>>> = mutableListOf()
    var outboxResult: List<SharedFile> = emptyList()

    // Returned by workflowAgent(); if [workflowAgentException] is set, throws instead.
    var agentTranscriptResult: String = ""
    var workflowAgentException: Exception? = null


    // Returned by commits(); if [commitsException] is set, throws instead.
    var commitsResult: List<RepoCommits> = emptyList()
    var commitsException: Exception? = null
    // Per-call commits() script consumed one step per call (a thrown Result re-throws); falls back
    val commitsScript: MutableList<Result<List<RepoCommits>>> = mutableListOf()
    // Number of commits() invocations (lets a test assert a reload happened).
    var commitsCallCount = 0

    // Returned by commitFiles(); if [commitFilesException] is set, throws instead.
    var commitFilesResult: List<CommitFile> = emptyList()
    var commitFilesException: Exception? = null
    // Records each commitFiles() call as (id, repo, sha).
    val commitFilesCalls: MutableList<Triple<String, String, String>> = mutableListOf()

    // Returned by commitDiff(); if [commitDiffException] is set, throws instead.
    var commitDiffResult: FileDiff = FileDiff()
    var commitDiffException: Exception? = null
    // Records each commitDiff() call as (id, repo, sha, path).
    val commitDiffCalls: MutableList<List<String>> = mutableListOf()

    // If set, rewind() throws it.
    var rewindException: Exception? = null
    val rewindCalls: MutableList<Pair<String, Int>> = mutableListOf()

    // Tracks discard() calls (by session id).
    val discardCalls: MutableList<String> = mutableListOf()


    // Returned by repos().
    var reposResult: RepoList = RepoList()
    // Returned by skills().
    var skillsResult: List<SkillInfo> = emptyList()
    // Returned by plugins().
    var pluginsResult: List<PluginInfo> = emptyList()
    // Returned by getTemplates(); if [getTemplatesException] is set, throws instead.
    var templatesResult: List<Template> = emptyList()
    var getTemplatesException: Exception? = null
    // Tracks putTemplates() calls.
    val putTemplatesCalls: MutableList<List<Template>> = mutableListOf()
    // Returned by create(); if [createException] is set, throws instead.
    var createResult: String = "new-session-id"
    var createException: Exception? = null
    val createCalls: MutableList<NewSessionReq> = mutableListOf()
    // Tracks fork() calls.
    val forkCalls: MutableList<String> = mutableListOf()
    // Returned by fork(); if [forkError] is set, fork() throws it instead.
    var forkResult: String = "forked"
    var forkError: Throwable? = null
    // Returned by usage().
    var usageResult: Usage = Usage()
    var usageException: Exception? = null
    // When set, usage() suspends on this until completed — lets a test observe an in-flight refresh.
    var usageGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    // Tracks delete() calls.
    val deleteCalls: MutableList<String> = mutableListOf()
    // Tracks kill() calls.
    val killCalls: MutableList<String> = mutableListOf()
    // Tracks interrupt() calls.
    val interruptCalls: MutableList<String> = mutableListOf()
    // Tracks respondPermission() calls as (id, decision, feedback).
    val permissionCalls: MutableList<Triple<String, String, String?>> = mutableListOf()
    // When set, respondPermission() throws this instead of recording the call.
    var respondPermissionException: Exception? = null

    // When non-null, uploadFile() returns this path; otherwise throws [uploadException].
    var uploadPathResult: String? = "uploads/file.txt"
    var uploadException: Exception? = null

    // Returned by followUp(); if [followUpException] is set, followUp() throws instead.
    var followUpSince = 0
    var followUpException: Exception? = null
    val followUpCalls: MutableList<Triple<String, String, Boolean>> = mutableListOf()

    // Suspend so handlers can park on test gates; a BLOCKING runBlocking gate deadlocks runTest's single-threaded dispatcher.
    var searchHandler: (suspend (String) -> SearchResponse?)? = null
    val searchSessionsCalls: MutableList<String> = mutableListOf()

    override suspend fun login(password: String): String {
        loginException?.let { throw it }
        val t = loginTokenResult ?: error("No login token configured")
        token = t
        return t
    }

    override suspend fun registerDevice(token: String) {

    }

    override fun close() { closed = true }


    override suspend fun sessions(): List<Session> {
        sessionsGate?.await()
        if (sessionsScript.isNotEmpty()) return sessionsScript.removeAt(0).getOrThrow()
        return sessionsResult
    }

    // Mirrors real AgenticApi.get, which returns SessionDetail.session.
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
    // Records each sessionEvents call's cursor (before, after, around) for windowing assertions.
    val sessionEventsCursors: MutableList<Triple<Long?, Long?, Long?>> = mutableListOf()
    // The `limit` arg each sessionEvents(id, limit, …) call was made with.
    val sessionEventsLimitArgs: MutableList<Int?> = mutableListOf()
    // Per-id sequence of sessionEvents(id, …) results consumed one-per-call; falls back to pagedEvents/sessionEventsResult.
    val sessionEventsScript: MutableMap<String, MutableList<Result<SessionEventsResponse>>> = mutableMapOf()
    // Paged-log mode: when set, sessionEvents serves tail/before/after/around windows over this immutable event list (stride 1), EXCLUSIVE cursors, capped at pagedPageLimit.
    val pagedEvents: MutableMap<String, List<kotlinx.serialization.json.JsonElement>> = mutableMapOf()
    // Overrides the per-window event cap in paged mode (null = use the requested limit).
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
        val total = n.toLong()
        val idxs: List<Int> = when {
            before != null -> (0 until n).filter { it.toLong() < before }.takeLast(cap)
            after != null  -> (0 until n).filter { it.toLong() > after }.take(cap)
            around != null -> { val c = around.toInt().coerceIn(0, maxOf(0, n - 1))
                                val s = (c - cap / 2).coerceAtLeast(0); (s until minOf(s + cap, n)).toList() }
            else           -> (0 until n).toList().takeLast(cap)
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

    // uploadStaging return value; uploadStagingCalls records each filename.
    var uploadStagingResult: StagedUpload? = StagedUpload(token = "stage-token", name = "file.txt", path = "uploads/file.txt")
    var uploadStagingException: Exception? = null
    val uploadStagingCalls: MutableList<String> = mutableListOf()
    override suspend fun uploadStaging(bytes: ByteArray, filename: String): StagedUpload {
        uploadStagingCalls.add(filename)
        uploadStagingException?.let { throw it }
        return uploadStagingResult ?: error("No staging upload configured")
    }
    // fileBytes return value; fileBytesCalls records each (id, path).
    var fileBytesResult: ByteArray = ByteArray(0)
    var fileBytesException: Throwable? = null
    val fileBytesCalls = mutableListOf<Pair<String, String>>()
    // Progress fractions [fileBytes] will replay into onProgress; whether onProgress was non-null.
    var fileBytesProgress: List<Float?> = emptyList()
    var fileBytesOnProgressWasNonNull = false
    override suspend fun fileBytes(id: String, path: String, onProgress: ((Float?) -> Unit)?): ByteArray {
        fileBytesCalls.add(id to path)
        fileBytesOnProgressWasNonNull = onProgress != null
        fileBytesProgress.forEach { onProgress?.invoke(it) }
        fileBytesException?.let { throw it }
        return fileBytesResult
    }
    // downloadFileTo writes this into dest; downloadFileCalls records each (id, path); progress replays (received, total) pairs.
    var downloadFileResult: ByteArray = ByteArray(0)
    var downloadFileException: Throwable? = null
    val downloadFileCalls = mutableListOf<Pair<String, String>>()
    var downloadFileProgress: List<Pair<Long, Long?>> = emptyList()
    override suspend fun downloadFileTo(
        id: String,
        path: String,
        dest: java.io.File,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        downloadFileCalls.add(id to path)
        downloadFileProgress.forEach { (r, t) -> onProgress?.invoke(r, t) }
        downloadFileException?.let { throw it }
        dest.writeBytes(downloadFileResult)
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
    // Tracks each ackSession() call as (id, eventId); the ack path is best-effort on the wire.
    val ackSessionCalls: MutableList<Pair<String, Long>> = mutableListOf()
    override suspend fun ackSession(id: String, eventId: Long) {
        ackSessionCalls.add(id to eventId)
    }

    // Returned by adoptable(); if [adoptableException] is set, throws instead.
    var adoptableResult: List<Adoptable> = emptyList()
    var adoptableException: Exception? = null
    val adoptableCalls: MutableList<Unit> = mutableListOf()
    override suspend fun adoptable(): List<Adoptable> {
        adoptableCalls.add(Unit)
        adoptableException?.let { throw it }
        return adoptableResult
    }
    // adoptSession return value; adoptSessionCalls records each (claudeSessionId, cwd) pair.
    var adoptSessionResult: String = "adopted-id"
    var adoptSessionException: Exception? = null
    val adoptSessionCalls: MutableList<AdoptSessionReq> = mutableListOf()
    override suspend fun adoptSession(req: AdoptSessionReq): String {
        adoptSessionCalls.add(req)
        adoptSessionException?.let { throw it }
        return adoptSessionResult
    }
    // Defaults model the happy-path CLI hand-off shape so callers can render the resume command without wiring each field.
    var detachResult: DetachResp = DetachResp(cwd = "/tmp/example", claudeSessionId = "csid", resumeCmd = "claude --resume csid")
    var detachException: Exception? = null
    val detachCalls: MutableList<String> = mutableListOf()
    override suspend fun detach(id: String): DetachResp {
        detachCalls.add(id)
        detachException?.let { throw it }
        return detachResult
    }

    // Returned by getGlobalSettings(); if [getGlobalSettingsException] is set, throws instead.
    var globalSettingsResult: List<ComponentInfo> = emptyList()
    var getGlobalSettingsException: Exception? = null
    var getGlobalSettingsCallCount = 0

    override suspend fun getGlobalSettings(): List<ComponentInfo> {
        getGlobalSettingsCallCount++
        getGlobalSettingsException?.let { throw it }
        return globalSettingsResult
    }

    // Returned by toggleGlobalComponent(); if [toggleGlobalComponentException] is set, throws instead.
    var toggleGlobalComponentResult: List<ComponentInfo> = emptyList()
    var toggleGlobalComponentException: Exception? = null
    // Records each toggleGlobalComponent() call as Triple(kind, id, enabled).
    val toggleGlobalComponentCalls: MutableList<Triple<String, String, Boolean>> = mutableListOf()

    override suspend fun toggleGlobalComponent(kind: String, id: String, enabled: Boolean): List<ComponentInfo> {
        toggleGlobalComponentCalls.add(Triple(kind, id, enabled))
        toggleGlobalComponentException?.let { throw it }
        return toggleGlobalComponentResult
    }

    var skillCatalogResult: List<CatalogSkill> = emptyList()
    var skillCatalogErrors: List<String> = emptyList()
    var skillCatalogException: Exception? = null
    var skillCatalogCalls: Int = 0
    val skillCatalogRefreshCalls: MutableList<Boolean> = mutableListOf()
    override suspend fun getSkillCatalog(refresh: Boolean): SkillCatalogResp {
        skillCatalogCalls++
        skillCatalogRefreshCalls.add(refresh)
        skillCatalogException?.let { throw it }
        return SkillCatalogResp(skills = skillCatalogResult, errors = skillCatalogErrors)
    }

    var skillSourcesResult: List<String> = listOf("anthropics/skills")
    var skillSourcesException: Exception? = null
    override suspend fun getSkillSources(): List<String> {
        skillSourcesException?.let { throw it }
        return skillSourcesResult
    }

    val addSkillSourceCalls: MutableList<String> = mutableListOf()
    var addSkillSourceException: Exception? = null
    override suspend fun addSkillSource(source: String): List<String> {
        addSkillSourceCalls.add(source)
        addSkillSourceException?.let { throw it }
        skillSourcesResult = skillSourcesResult + source
        return skillSourcesResult
    }

    val deleteSkillSourceCalls: MutableList<String> = mutableListOf()
    var deleteSkillSourceException: Exception? = null
    override suspend fun deleteSkillSource(source: String): List<String> {
        deleteSkillSourceCalls.add(source)
        deleteSkillSourceException?.let { throw it }
        skillSourcesResult = skillSourcesResult - source
        return skillSourcesResult
    }

    var installSkillResult: List<ComponentInfo> = emptyList()
    var installSkillException: Exception? = null
    val installSkillCalls: MutableList<Pair<String, Boolean>> = mutableListOf()
    override suspend fun installSkill(source: String, update: Boolean): List<ComponentInfo> {
        installSkillCalls.add(source to update)
        installSkillException?.let { throw it }
        return installSkillResult
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
