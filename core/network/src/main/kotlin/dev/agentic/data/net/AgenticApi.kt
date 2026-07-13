package dev.agentic.data.net

/** Contract for the agentic-dev backend client (REST + WebSocket stream). Implementations can swap transport or inject fakes for testing. */
interface AgenticApi {
    var baseUrl: String
    var token: String?
    /** Fired by the implementation on any 401 so the app can clear the stale token and route to the login screen. */
    var onUnauthorized: (() -> Unit)?

    suspend fun login(password: String): String
    suspend fun sessions(): List<Session>
    /** Server-side content search across rendered transcripts (GET /api/sessions/search?q=). */
    suspend fun searchSessions(q: String): SearchResponse
    // `limit` null = legacy full log; set to request the server's windowed path (returns { log, start, total }) so a huge transcript isn't shipped whole.
    suspend fun session(id: String, limit: Int? = null): SessionDetail
    /** Discord-style cursor-paginated structured events from GET /api/sessions/{id}/events (events = typed ClaudeEvent::to_wire() output, not raw JSONL — prevents OOM on long sessions). */
    suspend fun sessionEvents(id: String, limit: Int? = null, before: Long? = null, after: Long? = null, around: Long? = null): SessionEventsResponse
    /** Bare session row (no log) for surfaces that don't render transcript (e.g. settings). */
    suspend fun get(id: String): Session
    suspend fun usage(): Usage
    suspend fun workflows(id: String): List<WorkflowRun>
    suspend fun workflowAgent(id: String, runId: String, agentId: String): String
    suspend fun repos(): RepoList
    suspend fun skills(): List<SkillInfo>
    /** Available slash commands (GET /api/commands, the SDK's live list). Default no-op for test fakes. */
    suspend fun commands(): List<SlashCommand> = emptyList()
    /** Installed Claude Code plugins (`<plugin>@<marketplace>` ids) from GET /api/plugins. Default no-op for test fakes. */
    suspend fun plugins(): List<PluginInfo> = emptyList()
    suspend fun create(req: NewSessionReq): String
    /** Fork — returns the new session id; new session sits idle (status="pending") until the user opens and follows up. */
    suspend fun fork(id: String): String
    suspend fun followUp(id: String, prompt: String, setTitle: Boolean = true, model: String? = null, effort: String? = null, permissionMode: String? = null): Int
    suspend fun patchSession(id: String, req: PatchSessionReq): Session
    suspend fun uploadFile(id: String, bytes: ByteArray, filename: String): String
    /** Stage a file BEFORE a session exists (New-request attachments) via POST /api/uploads. Returns token + sanitized name + uploads/<name> path for the prompt marker. */
    suspend fun uploadStaging(bytes: ByteArray, filename: String): StagedUpload
    suspend fun fileBytes(id: String, path: String, onProgress: ((Float?) -> Unit)? = null): ByteArray
    /** Streams into [dest] (no whole-file buffering), automatically RESUMING over transient mid-transfer failures via HTTP Range. Throws when unrecoverable. */
    suspend fun downloadFileTo(
        id: String,
        path: String,
        dest: java.io.File,
        onProgress: ((received: Long, total: Long?) -> Unit)? = null,
    )
    suspend fun outbox(id: String): List<SharedFile>
    suspend fun kill(id: String)
    /** Interrupt the current turn; keep the session alive (idle, ready for the next message). */
    suspend fun interrupt(id: String)
    /** Discord-style: acknowledge current unread event. PUT /api/sessions/{id}/ack with {"eventId": N}. */
    suspend fun ackSession(id: String, eventId: Long)
    /** Answer a parked permission/plan prompt (allow/deny, optional feedback). */
    suspend fun respondPermission(id: String, decision: String, feedback: String? = null)
    suspend fun delete(id: String)
    /** Open the session stream; [onLine] is invoked per event frame (JSON ClaudeEvent). If the WS upgrade is 401, fires [onUnauthorized] (matches HTTP 401 signal) then rethrows. */
    suspend fun stream(id: String, since: Int?, onLine: suspend (String) -> Unit)
    suspend fun registerDevice(token: String)
    suspend fun getTemplates(): List<Template>
    suspend fun putTemplates(templates: List<Template>)
    /** Model catalog (native Claude tiers + registered BYOK providers). Default no-op for test fakes. */
    suspend fun models(): List<ModelEntry> = emptyList()
    /** Model catalog for session-start pickers (GET /api/models?scope=session_start) — Claude/native only; BYOK never appears here. Default no-op for test fakes. */
    suspend fun sessionStartModels(): List<ModelEntry> = emptyList()
    /** Session groups (folders) — DB-backed CRUD. */
    suspend fun listGroups(): List<Group>
    suspend fun createGroup(req: CreateGroupReq): Group
    suspend fun updateGroup(id: String, req: UpdateGroupReq): Group
    suspend fun deleteGroup(id: String)
    /** Provider registry (BYOK cheap models for delegate fan-out). Default no-op for test fakes. */
    suspend fun providers(): List<Provider> = emptyList()
    suspend fun addProvider(req: NewProviderReq) {}
    suspend fun deleteProvider(name: String) {}
    suspend fun nativeModels(): List<NativeFamily> = emptyList()
    suspend fun putNativeOverride(family: String, req: NativeOverrideReq) {}
    suspend fun deleteNativeOverride(family: String) {}
    /** Global cost⇄quality routing knob (0=cheapest..1=strongest). Default no-op for test fakes. */
    suspend fun getRouting(): RoutingConfig = RoutingConfig()
    suspend fun setRouting(tradeoff: Float) {}
    suspend fun getGlobalSettings(): List<ComponentInfo> = emptyList()
    suspend fun toggleGlobalComponent(kind: String, id: String, enabled: Boolean): List<ComponentInfo> = emptyList()
    /** External skill store aggregated across every configured source. [refresh] bypasses the server's per-source cache. Default no-op for test fakes. */
    suspend fun getSkillCatalog(refresh: Boolean = false): SkillCatalogResp = SkillCatalogResp()
    suspend fun getSkillSources(): List<String> = emptyList()
    suspend fun addSkillSource(source: String): List<String> = emptyList()
    suspend fun deleteSkillSource(source: String): List<String> = emptyList()
    /** Install a skill from a GitHub source; [update] replaces an existing install. */
    suspend fun installSkill(source: String, update: Boolean = false): List<ComponentInfo> = emptyList()
    suspend fun deleteSkill(name: String): List<ComponentInfo> = emptyList()
    /** Install a plugin globally (slow — CLI shells out). */
    suspend fun installPlugin(id: String): List<ComponentInfo> = emptyList()
    suspend fun uninstallPlugin(id: String): List<ComponentInfo> = emptyList()
    suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> = emptyList()
    suspend fun deleteMcpServer(name: String): List<ComponentInfo> = emptyList()

    suspend fun commits(id: String): List<RepoCommits>
    suspend fun commitFiles(id: String, repo: String, sha: String): List<CommitFile>
    suspend fun commitDiff(id: String, repo: String, sha: String, path: String): FileDiff
    suspend fun rewind(id: String, turnIndex: Int)
    suspend fun discard(id: String)
    /** List Claude Code CLI sessions on disk that this server could adopt (GET /api/adoptable). Empty list when server predates the feature or local scan finds nothing — both non-error for the picker. */
    suspend fun adoptable(): List<Adoptable> = emptyList()
    /** Adopt (import) a Claude Code session as native (POST /api/sessions/adopt). Picker navigates to the returned id. */
    suspend fun adoptSession(req: AdoptSessionReq): String
    /** Hand off a native session back to a local Claude Code CLI (POST /api/sessions/:id/detach). Returns the exact `resumeCmd` for the user to paste plus cwd/claudeSessionId; on success the server sets `Session.detached = true`. */
    suspend fun detach(id: String): DetachResp
    /** Drop idle pooled connections so the next request opens a fresh socket (app-foreground heal — a half-open backgrounded socket must not be reused). Default no-op for test fakes. */
    fun evictConnections() {}
    fun close()
}
