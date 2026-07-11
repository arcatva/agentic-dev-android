package dev.agentic.data.net

/** Contract for the agentic-dev backend client (REST + WebSocket stream). All methods mirror the
 *  public surface of the original [dev.agentic.net.Api]; implementations can swap transport or
 *  inject fakes for testing. */
interface AgenticApi {
    var baseUrl: String
    var token: String?
    /** Invoked by the implementation on any 401 so the app can clear the stale token and route
     *  to the login screen. */
    var onUnauthorized: (() -> Unit)?

    suspend fun login(password: String): String
    suspend fun sessions(): List<Session>
    /** GET …/sessions/search?q=<text> — server-side content search across rendered transcripts. */
    suspend fun searchSessions(q: String): SearchResponse
    // `limit`: when set, request only the last `limit` rendered log lines (server's windowed path,
    // returns { log, start, total }) so a huge transcript isn't shipped whole. null = legacy full log.
    suspend fun session(id: String, limit: Int? = null): SessionDetail
    /** Discord-style cursor-paginated structured events from GET /api/sessions/{id}/events.
     *  `limit`: max events to return (default 100). `before`: cursor — returns events with eventId < before.
     *  `after`: returns events with eventId > after. `around`: returns events centered on eventId.
     *  Returns typed ClaudeEvent::to_wire() output instead of raw JSONL strings, preventing OOM on long sessions. */
    suspend fun sessionEvents(id: String, limit: Int? = null, before: Long? = null, after: Long? = null, around: Long? = null): SessionEventsResponse
    /** Bare session row (no log). Backed by GET /api/sessions/:id; the endpoint also returns a `log`,
     *  but this overload's only consumer is the settings screen which doesn't render transcript. */
    suspend fun get(id: String): Session
    suspend fun usage(): Usage
    suspend fun workflows(id: String): List<WorkflowRun>
    suspend fun workflowAgent(id: String, runId: String, agentId: String): String
    suspend fun repos(): RepoList
    suspend fun skills(): List<SkillInfo>
    /** Installed Claude Code plugins (`<plugin>@<marketplace>` ids) from GET /api/plugins.
     *  Default impl returns empty list — test fakes override. */
    suspend fun plugins(): List<PluginInfo> = emptyList()
    suspend fun create(req: NewSessionReq): String
/** Fork the session. Returns the new session's id; the new session sits idle
     *  (status="pending") until the user opens it and sends a follow-up prompt. */
    suspend fun fork(id: String): String
    suspend fun followUp(id: String, prompt: String, setTitle: Boolean = true, model: String? = null, effort: String? = null, permissionMode: String? = null): Int
    suspend fun patchSession(id: String, req: PatchSessionReq): Session
    suspend fun uploadFile(id: String, bytes: ByteArray, filename: String): String
    /** Stage a file BEFORE a session exists (New-request attachments) via POST /api/uploads. Returns
     *  the staging token, sanitized name, and the uploads/<name> path for the prompt marker. */
    suspend fun uploadStaging(bytes: ByteArray, filename: String): StagedUpload
    suspend fun fileBytes(id: String, path: String, onProgress: ((Float?) -> Unit)? = null): ByteArray
    /** Streams the file at [path] into [dest] (no whole-file buffering), automatically RESUMING
     *  over transient mid-transfer failures via HTTP Range. [onProgress] reports cumulative
     *  (bytesReceived, totalBytes-or-null). Throws when the transfer is unrecoverable. */
    suspend fun downloadFileTo(
        id: String,
        path: String,
        dest: java.io.File,
        onProgress: ((received: Long, total: Long?) -> Unit)? = null,
    )
    suspend fun outbox(id: String): List<SharedFile>
    suspend fun kill(id: String)
    /** Interrupt the current turn but keep the session alive (idle, ready for the next message). */
    suspend fun interrupt(id: String)
    /** Discord-style: acknowledge the session's current unread event.
     *  `PUT /api/sessions/{id}/ack` with `{"eventId": N}`. Sets ackedEventId on the server. */
    suspend fun ackSession(id: String, eventId: Long)
    /** Answer a parked permission/plan prompt for [id] (allow/deny, optional feedback). */
    suspend fun respondPermission(id: String, decision: String, feedback: String? = null)
    suspend fun delete(id: String)
    /** Open the session stream; calls [onLine] for each event frame (a JSON ClaudeEvent). Returns when closed. */
    suspend fun stream(id: String, since: Int?, onLine: suspend (String) -> Unit)
    suspend fun registerDevice(token: String)
    suspend fun getTemplates(): List<Template>
    suspend fun putTemplates(templates: List<Template>)
    /** Model catalog (native Claude tiers + registered BYOK providers). Default impl returns
     *  empty list — test fakes override. */
    suspend fun models(): List<ModelEntry> = emptyList()
    /** Model catalog for main-thread/session-start pickers (GET /api/models?scope=session_start).
     *  Only Claude/native models — BYOK providers never appear here. Default impl returns empty
     *  list — test fakes override. */
    suspend fun sessionStartModels(): List<ModelEntry> = emptyList()
    /** Session groups (folders) — DB-backed CRUD replacing the old file-based groups. */
    suspend fun listGroups(): List<Group>
    suspend fun createGroup(req: CreateGroupReq): Group
    suspend fun updateGroup(id: String, req: UpdateGroupReq): Group
    suspend fun deleteGroup(id: String)
    /** Provider registry (BYOK cheap models for delegate fan-out). Default impls keep test fakes
     *  compiling; [KtorAgenticApi] overrides all three. */
    suspend fun providers(): List<Provider> = emptyList()
    suspend fun addProvider(req: NewProviderReq) {}
    suspend fun deleteProvider(name: String) {}
    // Native Claude model per-family routing overrides.
    suspend fun nativeModels(): List<NativeFamily> = emptyList()
    suspend fun putNativeOverride(family: String, req: NativeOverrideReq) {}
    suspend fun deleteNativeOverride(family: String) {}
    // ── Feature: Global Settings (S5a) ────────────────────────────────────────
    /** Fetch all globally-configured components (skills, plugins, MCP) with their on/off state.
     *  Default impl returns empty list — test fakes override. */
    suspend fun getGlobalSettings(): List<ComponentInfo> = emptyList()
    /** Toggle a component's global enabled state. Returns the refreshed full list.
     *  Default impl returns empty list — test fakes override. */
    suspend fun toggleGlobalComponent(kind: String, id: String, enabled: Boolean): List<ComponentInfo> = emptyList()
    // ── Feature: Global Settings CRUD (S5c) ─────────────────────────────────────
    /** The external skill store, aggregated across every configured source. [refresh] bypasses
     *  the server's per-source cache. Default impl = empty. */
    suspend fun getSkillCatalog(refresh: Boolean = false): SkillCatalogResp = SkillCatalogResp()
    /** The configured store sources. */
    suspend fun getSkillSources(): List<String> = emptyList()
    /** Add a store source (`owner/repo[/path]` or URL). Returns the refreshed source list. */
    suspend fun addSkillSource(source: String): List<String> = emptyList()
    /** Remove a store source. Returns the refreshed source list. */
    suspend fun deleteSkillSource(source: String): List<String> = emptyList()
    /** Install a skill from a GitHub source; [update] replaces an existing install. Returns the refreshed list. */
    suspend fun installSkill(source: String, update: Boolean = false): List<ComponentInfo> = emptyList()
    /** Delete a skill globally by name. Returns the refreshed list. */
    suspend fun deleteSkill(name: String): List<ComponentInfo> = emptyList()
    /** Install a plugin globally (slow — CLI shells out). Returns the refreshed list. */
    suspend fun installPlugin(id: String): List<ComponentInfo> = emptyList()
    /** Uninstall a plugin globally. Returns the refreshed list. */
    suspend fun uninstallPlugin(id: String): List<ComponentInfo> = emptyList()
    /** Add a globally configured MCP server. Returns the refreshed list. */
    suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> = emptyList()
    /** Delete a globally configured MCP server by name. Returns the refreshed list. */
    suspend fun deleteMcpServer(name: String): List<ComponentInfo> = emptyList()

    /** Recent commit history per repo for session [id] (GET …/commits). */
    suspend fun commits(id: String): List<RepoCommits>
    /** Changed files for [sha] in [repo] of session [id] (GET …/commits/<sha>/files?repo=<repo>). */
    suspend fun commitFiles(id: String, repo: String, sha: String): List<CommitFile>
    /** Line-level diff for one [path] in [sha] of [repo] (GET …/commits/<sha>/diff?repo=&path=). */
    suspend fun commitDiff(id: String, repo: String, sha: String, path: String): FileDiff
    /** Restore the working tree to the snapshot before [turnIndex] (POST …/rewind). Code only. */
    suspend fun rewind(id: String, turnIndex: Int)
    suspend fun discard(id: String)
    /** List Claude Code CLI sessions on disk that this server could adopt (GET /api/adoptable).
     *  Each entry has a stable `sessionId` (the JSONL UUID), a `cwd` it must be re-imported under,
     *  and `resumable` indicating whether the user will be able to resume or only read history.
     *  Returns an empty list when the server predates the feature or when the local scan finds
     *  nothing — both are non-error surfaces for the picker. */
    suspend fun adoptable(): List<Adoptable> = emptyList()
    /** Adopt (import) a Claude Code session as a native server session (POST /api/sessions/adopt).
     *  The picker navigates to the returned id on success. */
    suspend fun adoptSession(req: AdoptSessionReq): String
    /** Hand off a native session back to a local Claude Code CLI process (POST /api/sessions/:id/detach).
     *  Returns the exact `resumeCmd` for the user to paste in a terminal, plus the cwd/claudeSessionId
     *  the resume requires. After a successful detach, the server sets `Session.detached == true`. */
    suspend fun detach(id: String): DetachResp
    /** Drop idle pooled connections so the next request opens a fresh socket. Called on app-foreground
     *  so a connection that went half-open while backgrounded can't be reused (and hang). Default no-op
     *  keeps test fakes compiling; [KtorAgenticApi] evicts its OkHttp pool. */
    fun evictConnections() {}
    fun close()
}
