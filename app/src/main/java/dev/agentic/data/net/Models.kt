package dev.agentic.data.net

import androidx.compose.runtime.Immutable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Immutable
@Serializable
data class Session(
    val id: String,
    val prompt: String = "",
    val status: String = "pending",
    val error: String? = null,
    // Structured stop reason set by the backend (auth_error, usage_limit, wall_timeout, idle_timeout,
    // crashed, interrupted, claude_error). null = clean turn, or an older backend that predates the field —
    // stopReason() then falls back to the legacy error-text heuristic. Default keeps deserialization
    // backward-compatible.
    val errorKind: String? = null,
    val repos: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    val claudeSessionId: String? = null,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val lastUserMessageAt: Long = 0,
    val worktreePath: String? = null,
    val branch: String? = null,
    val worktreeState: String? = null,
    val activity: Activity? = null,
    // true = turn finished, session idle and accepting a new message even while a background workflow
    // runs; false = busy mid-turn. null only before the session's first turn has started.
    val awaitingInput: Boolean? = null,
    // Runtime-only flag from the backend: true when the turn is done/idle but a background workflow is
    // still running, so the list shows the session as running. Defaults false for older servers.
    val workflowRunning: Boolean = false,
    // Authoritative: the wire payload of the prompt this session is currently PARKED on (an
    // AskUserQuestion / perm / plan awaiting the user), or null. Lets the client render the awaiting
    // card from the server's source of truth instead of inferring it from the warm-reseed-fragile log.
    val pendingPrompt: JsonElement? = null,
    /** Server-side: id of the session this one was forked from, or null. Used by SessionScreen
     *  to render a "Forked from …" chip. Null for sessions created from scratch or by older
     *  backends that predate the field. */
    val parentSessionId: String? = null,
    /** Session group assignment (DB-backed folder). null = uncategorized. */
    val groupId: String? = null,
    /** Discord-style: monotonic counter incremented server-side at each "your turn" point (IDLE / DONE).
     *  A session is unread when its unreadEventId is strictly greater than the client's last-acked id.
     *  Eliminates timestamp comparison, clock-skew issues, and client-side idle detection loops. */
    val unreadEventId: Long = 0,
    /** Server-authoritative: the highest unreadEventId the user has acknowledged.
     *  Set by `PUT /api/sessions/:id/ack`. Discord-style: unread = unreadEventId > ackedEventId.
     *  Both fields are server-side — no client-side read-state persistence needed. */
    val ackedEventId: Long = 0,
    /** When true, the server auto-resumes this session after a usage-limit stop (5h/7d token
     *  limit). Default true when absent — older backends won't send the field, and the
     *  auto-resume feature defaults ON for safety (a missed resume is worse than a retry). */
    val autoResume: Boolean = true,
    /** Epoch ms when an automatic resume is scheduled, or null when none is pending.
     *  Absent on older backends → defaults null. The settings screen formats it for display. */
    val autoResumeAt: Long? = null,
    /** Origin of this session row, as set server-side. One of:
     *    "native"   — created via POST /api/sessions (the default for new requests).
     *    "fork"     — created via POST /api/sessions/:id/fork (a fork of an existing session).
     *    "adopted"  — imported via POST /api/sessions/adopt from a Claude Code CLI session log.
     *  Default "native" so older backends (or wire payloads that predate the field) still parse.
     *  Used by the home list to render a small "adopted" badge on imported rows. */
    val origin: String = "native",
    /** True when the session has been handed off to an external Claude Code CLI process via
     *  POST /api/sessions/:id/detach. A detached session freezes its stream watermark and stops
     *  accepting live follow-ups; the detail screen surfaces a "handed off to a terminal" banner
     *  and exposes the `resumeCmd` returned by detach so the user can reconnect from the CLI.
     *  Default false for older backends and for any non-detached session. */
    val detached: Boolean = false,
)

@Immutable
@Serializable
data class Activity(val turns: Int = 0, val lastSkill: String? = null)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

@Serializable
data class SessionDetail(
    val session: Session,
    val log: List<String> = emptyList(),
    // Windowed-load coordinates (server's GET ?limit path). `log` may be only the LAST `total-start`
    // rendered lines; `total` is the full rendered-line count. Absent (null) on the legacy full
    // response, in which case the whole log is present and `log.size == total`. The live-stream cursor
    // (`since`) is a rendered-offset into the FULL log, so it must advance to `total`, NOT `log.size`.
    val start: Int = 0,
    val total: Int? = null,
)

/** Discord-style cursor-paginated structured events from GET /api/sessions/{id}/events.
 *  `events` are pre-parsed ClaudeEvent::to_wire() output — kind-tagged JSON objects
 *  matching the WebSocket wire format. No raw JSONL re-parsing needed on the client. */
@Serializable
data class SessionEventsResponse(
    val session: Session,
    val events: List<JsonElement> = emptyList(),
    val latestEventId: Long = 0,
    val firstEventLine: Long = 0,
    val hasMore: Boolean = false,
    val hasMoreAfter: Boolean = false,
)

@Serializable
data class SkillInfo(val name: String, val description: String = "")

/** One installed Claude Code plugin. [name] is the full `<plugin>@<marketplace>` id — the exact
 *  key claude's `enabledPlugins` settings map expects, echoed back verbatim in
 *  [NewSessionReq.hiddenPlugins] when the user toggles the plugin off. */
@Serializable
data class PluginInfo(val name: String)

@Serializable
data class RepoList(val local: List<String> = emptyList(), val remote: List<String> = emptyList())

@Serializable
data class LoginReq(val password: String)

@Serializable
data class LoginResp(val token: String)

@Serializable
data class NewSessionReq(
    val repos: List<String>,
    val skills: List<String>,
    /** Blacklist: skills to HIDE from this session. Backend maps these to claude
     *  `skillOverrides:{<name>:"off"}` in the per-session `--settings`. */
    val hiddenSkills: List<String> = emptyList(),
    /** Blacklist: plugins (`<plugin>@<marketplace>` ids) to DISABLE for this session. Backend maps
     *  these to claude `enabledPlugins:{<id>:false}` in the per-session `--settings`. */
    val hiddenPlugins: List<String> = emptyList(),
    // ── S5b: tri-state per-session overrides ─────────────────────────────────
    /** Skills to FORCE ON for this session (override global-off). */
    val forcedOnSkills: List<String> = emptyList(),
    /** Plugins to FORCE ON for this session (override global-off). */
    val forcedOnPlugins: List<String> = emptyList(),
    /** MCP servers to FORCE ON for this session (override global-off). */
    val forcedOnMcpServers: List<String> = emptyList(),
    /** MCP servers to HIDE from this session (override global-on). */
    val hiddenMcpServers: List<String> = emptyList(),
    /** Extra MCP servers to ADD for this session only (ad-hoc, not globally configured). */
    val extraMcpServers: List<McpServerDef> = emptyList(),
    // ─────────────────────────────────────────────────────────────────────────
    val prompt: String,
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    /** Optional session-scoped CLAUDE.md content. The backend writes it into the session dir so
     *  Claude Code loads it as project memory for this session, layered on top of each repo's own
     *  CLAUDE.md. null/blank = no extra guidance. */
    val claudeMd: String? = null,
    /** Files staged via POST /api/uploads before this session existed (New-request attachments). The
     *  backend moves each into the new session's uploads/ dir before the prompt runs, so the agent can
     *  read the `[attached: uploads/<name>]` paths in the very first prompt. Empty = none. */
    val stagedUploads: List<StagedUpload> = emptyList(),
)

@Serializable
data class IdResp(val id: String)

@Serializable
data class PromptReq(val prompt: String, val setTitle: Boolean = true, val model: String? = null, val effort: String? = null, val permissionMode: String? = null)

/** Body for PATCH /api/sessions/:id — partial session settings update. All fields optional;
 *  null = leave that column as-is. Mirrors the server's SessionPatch column set. */
@Serializable
data class PatchSessionReq(
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    /** Assign session to a group. Empty string = remove from group. null = leave unchanged. */
    val groupId: String? = null,
    /** Toggle auto-resume after usage limit. null = leave unchanged.
     *  false also cancels a scheduled resume server-side. */
    val autoResume: Boolean? = null,
)

/** Body for POST /api/sessions/:id/permission — answer a parked allow/deny or plan-approval prompt.
 *  feedback is the deny reason or plan-revision note (ignored on allow). */
@Serializable
data class PermDecisionReq(val decision: String, val feedback: String? = null)

// ── Feature: Session grouping (folders) ────────────────────────────────────────

/** A user-created folder that groups sessions together. */
@Immutable
@Serializable
data class Group(
    val id: String,
    val name: String,
    val icon: String? = null,
    val sortOrder: Long = 0,
    val createdAt: Long = 0,
)

@Serializable
data class GroupList(val groups: List<Group> = emptyList())

@Serializable
data class CreateGroupReq(val name: String, val icon: String? = null)

@Serializable
data class UpdateGroupReq(val name: String? = null, val icon: String? = null)

@Serializable
data class GroupResp(val group: Group)

@Serializable
data class FollowUpResp(val ok: Boolean = true, val since: Int = 0)

@Serializable
data class UploadResp(val path: String)

/** Response from POST /api/uploads (pre-session staging) and the per-attachment payload echoed back
 *  in [NewSessionReq.stagedUploads]. [token]+[name] identify the staged file server-side; [path]
 *  ("uploads/<name>") is what the prompt's `[attached: ...]` marker references once the backend
 *  adopts the file into the new session's uploads/ dir. */
@Serializable
data class StagedUpload(val token: String, val name: String, val path: String)

/** One MCP server to add for this session only (ad-hoc, not globally configured).
 *  Exactly one transport must be supplied:
 *  - stdio: set [command] (required), optionally [args] and [env].
 *  - http/sse: set [url] (required), [type] ("http" or "sse"), optionally [headers].
 *  [name] must be non-empty and must not be "agentic". Null fields are omitted from JSON. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpServerDef(
    val name: String,
    // stdio transport
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val command: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val args: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val env: Map<String, String>? = null,
    // http/sse transport
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val type: String? = null,      // "http" or "sse"
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val headers: Map<String, String>? = null,
)

@Serializable
// mtime is Double, not Long: the server sends fractional milliseconds (statSync mtimeMs); a Long field
// would fail to deserialize a JSON float and silently drop the whole list. Convert with .toLong() at use.
data class SharedFile(val path: String, val name: String, val mtime: Double = 0.0)

@Serializable
data class OutboxResp(val files: List<SharedFile> = emptyList())

@Immutable
@Serializable
data class WorkflowAgent(
    val agentId: String,
    val label: String = "agent",
    val state: String = "",
    val model: String = "",
    val phaseTitle: String? = null,
    val promptPreview: String? = null,
    val resultPreview: String? = null,
)

@Immutable
@Serializable
data class WorkflowPhase(val title: String = "", val detail: String? = null)

@Immutable
@Serializable
data class WorkflowRun(
    val runId: String,
    val name: String = "workflow",
    val status: String = "",
    val summary: String? = null,
    val agentCount: Int? = null,
    /** Creation time (epoch ms) from the backend; 0 if the backend predates the field (degrades to
     *  insertion-order sort + a hidden time line). */
    val createdAt: Long = 0,
    val phases: List<WorkflowPhase> = emptyList(),
    val agents: List<WorkflowAgent> = emptyList(),
    val logs: List<String> = emptyList(),
)

@Serializable
data class WorkflowList(val workflows: List<WorkflowRun> = emptyList())

@Serializable
data class AgentTranscript(val transcript: String = "")

@Serializable
data class UsageWindow(val utilization: Double = 0.0, val resets_at: String? = null)

@Serializable
data class Usage(val five_hour: UsageWindow? = null, val seven_day: UsageWindow? = null)

// ── Feature: Push notifications ───────────────────────────────────────────────
@Serializable
data class DeviceReq(val token: String)

// ── Feature: Templates ────────────────────────────────────────────────────────
@Serializable
data class Template(
    val name: String,
    val repos: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val promptBody: String = "",
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    /** Variable names that appear as {{name}} in promptBody */
    val vars: List<String> = emptyList(),
)

@Serializable
data class TemplateList(val templates: List<Template> = emptyList())

// ── Feature: Commit-graph view ────────────────────────────────────────────────
/** One commit in the session branch's recent history. `at` is epoch-ms (backend converts %at*1000).
 *  `isSession` marks commits in `baseSha..HEAD` (work done in this session). */
@Serializable
data class CommitNode(
    val sha: String,
    val shortSha: String,
    val parents: List<String> = emptyList(),
    val subject: String = "",
    val author: String = "",
    val at: Long = 0,
    val isSession: Boolean = false,
    /** Branch/tag/HEAD labels pointing at this commit (from git %D). Empty for most commits. */
    val refs: List<GitRef> = emptyList(),
)

/** A ref label on a commit. [kind] is one of "head" | "branch" | "tag" | "remote". */
@Serializable
data class GitRef(val name: String, val kind: String = "branch")

/** Working-tree change counts for the per-repo "Uncommitted changes" node; null when clean. */
@Serializable
data class Uncommitted(val added: Int = 0, val modified: Int = 0, val deleted: Int = 0)

/** Commit history for one repo in the session, plus its uncommitted-changes node (null when clean). */
@Serializable
data class RepoCommits(
    val repo: String,
    val commits: List<CommitNode> = emptyList(),
    val uncommitted: Uncommitted? = null,
)

/** GET /api/sessions/:id/commits wraps the per-repo list under "repos". */
@Serializable
data class CommitsResp(val repos: List<RepoCommits> = emptyList())

/** One changed file for a commit (or the working tree). `status` is a full word
 *  ("added"/"modified"/"deleted"/"renamed"/"unknown"). */
@Serializable
data class CommitFile(
    val path: String,
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
)

/** GET /api/sessions/:id/commits/:sha/files wraps the list under "files". */
@Serializable
data class CommitFilesResp(val files: List<CommitFile> = emptyList())

// ── Feature: Line-level diff (single file) ─────────────────────────────────────
/** One physical line of a hunk. [kind] is "context" | "add" | "del". [oldLine]/[newLine] are
 *  1-based and present only on the side(s) the line exists in (context = both, add = new only,
 *  del = old only). */
@Serializable
data class DiffLine(
    val kind: String = "context",
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val content: String = "",
)

/** A contiguous block of changes (one `@@ … @@` section). */
@Serializable
data class DiffHunk(
    val oldStart: Int = 0,
    val oldLines: Int = 0,
    val newStart: Int = 0,
    val newLines: Int = 0,
    /** Section heading git prints after the second `@@` (often the enclosing decl). May be empty. */
    val header: String = "",
    val lines: List<DiffLine> = emptyList(),
)

/** Parsed line-level diff for ONE file. [binary] true → no hunks; [truncated] true → the raw diff
 *  exceeded the server cap and was cut short. */
@Serializable
data class FileDiff(
    val path: String = "",
    val status: String = "modified",
    val binary: Boolean = false,
    val truncated: Boolean = false,
    val hunks: List<DiffHunk> = emptyList(),
)

/** GET /api/sessions/:id/commits/:sha/diff wraps the file diff under "diff". */
@Serializable
data class DiffResp(val diff: FileDiff = FileDiff())

// ── Feature: Rewind (code-only restore) ────────────────────────────────────────
/** POST /api/sessions/:id/rewind body — the 0-based user-turn index to restore the code state to. */
@Serializable
data class RewindReq(val turnIndex: Int)

// ── Feature: Session content search ───────────────────────────────────────────

/** Where in a session a search hit came from. Wire format matches the backend's
 *  `engine::search::SearchField` (Task 2 of the session-search plan). kotlinx-serialization
 *  serializes enum constants by name, so JSON `"Notes"` maps to `SearchField.Notes`. */
@Serializable
enum class SearchField {
    Title, Repo, Branch, SessionId, Status, Error,
    Prompt,
    Notes, Answer,
    ToolName, ToolSummary, ToolDetail,
    SpawnDesc, SpawnResult,
    Skill, Workflow, Ask, Plan, Perm,
    Attachment,
}

/** One match within a session: which field matched, a short text snippet, and the
 *  rendered-log line index (matches backend `SearchMatch.line_index`, wire field
 *  `lineIndex` per Task 3). */
@Serializable
data class SearchMatch(
    val field: SearchField,
    val snippet: String,
    val lineIndex: Int,
)

/** A session that matched the query, with a relevance score and the list of
 *  field-level matches that contributed to the score. */
@Serializable
data class SearchHit(
    val session: Session,
    val score: Float,
    val matches: List<SearchMatch>,
)

/** Response shape for `GET /api/sessions/search?q=<text>&limit=<n>` (backend Task 3).
 *  `query` echoes the (trimmed) query the server actually searched for. */
@Serializable
data class SearchResponse(
    val query: String,
    val results: List<SearchHit> = emptyList(),
)

// ── Provider registry (BYOK cheap models for delegate fan-out) ────────────────

/** A registered provider as returned by GET /api/providers. The API key is masked — never returned;
 *  [hasKey] reflects whether one is stored. Field names map to the backend's snake_case JSON. */
@Serializable
data class Provider(
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    /** "anthropic" (called directly) or "openai" (reached through the LiteLLM proxy). */
    val protocol: String = "anthropic",
    /** 0..1 capability axis: the router only sends a task to a model capable enough for it. */
    val capability: Float = 0.5f,
    /** What the model is good at — the router reads this. */
    val description: String? = null,
    /** 0..1 scheduling priority: among models capable enough, the router prefers higher priority. */
    val priority: Float = 0.5f,
    /** 0..1 relative cost: among equally-prioritized models, the router prefers lower cost. */
    val cost: Float = 0.5f,
    /** True if this provider is the model that MAKES the routing decisions (the LLM-as-router). */
    val router: Boolean = false,
    @SerialName("has_key") val hasKey: Boolean = false,
)

@Serializable
data class ProviderList(val providers: List<Provider> = emptyList())

/** Body for POST /api/providers (add or replace by name). Carries the API key. */
// ── Model catalog (GET /api/models) ─────────────────────────────────────

/** A single entry in the model catalog returned by GET /api/models. */
@Serializable
data class ModelEntry(
    val key: String,         // "claude-opus-4-8"
    val label: String,       // "Opus 4.8"
    val native: Boolean = false,
    val default: Boolean = false,
    val capability: Float = 0.5f,
    val cost: Float = 0.5f,
)

@Serializable
data class ModelsResponse(val models: List<ModelEntry> = emptyList())

// ── Provider registry (BYOK cheap models for delegate fan-out) ────────────────

// ── Feature: Global Settings (S5a) ───────────────────────────────────────────

/** One component (skill, plugin, or MCP server) as returned by GET /api/global-settings.
 *  [kind] is one of "skill" | "plugin" | "mcp". [globalEnabled] is the current on/off state. */
@Serializable
data class ComponentInfo(
    val kind: String,
    val id: String,
    val name: String,
    val description: String = "",
    val source: String = "",
    val globalEnabled: Boolean,
)

/** Body for POST /api/global-settings/toggle. */
@Serializable
data class ToggleComponentReq(
    val kind: String,
    val id: String,
    val enabled: Boolean,
)

@Serializable
data class NewProviderReq(
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    val model: String,
    val protocol: String = "anthropic",
    val capability: Float = 0.5f,
    val description: String? = null,
    val priority: Float = 0.5f,
    val cost: Float = 0.5f,
    val router: Boolean = false,
)

// ── Feature: Adopt a Claude Code CLI session into this server ──────────────────
/** One candidate in GET /api/adoptable — a discoverable Claude Code session log file on disk that
 *  this server could import as a native session. [resumable]=false means the JSONL ended without a
 *  terminal status, so it cannot be resumed — only imported read-only. [lineCount] is the JSONL's
 *  line count (used for ordering / "size" hints in the picker). mtimeMs is a fractional ms
 *  timestamp from statSync(2); a Long field would drop it from the list when the server sends an
 *  integer — keep Double (same caveat as SharedFile.mtime). */
@Serializable
data class Adoptable(
    val sessionId: String,
    val cwd: String = "",
    val slug: String = "",
    val firstPrompt: String = "",
    val mtimeMs: Double = 0.0,
    val resumable: Boolean = false,
    val lineCount: Long = 0,
)

/** Body for POST /api/sessions/adopt — choose which Claude Code session (by `sessionId`) and in
 *  which working directory to import it. The cwd the user picked at adopt time MUST match the cwd
 *  the discoverable was scanned under (the server pins imports to the scan root). */
@Serializable
data class AdoptSessionReq(
    val claudeSessionId: String,
    val cwd: String,
)

/** Response from POST /api/sessions/adopt — the new server session id. The picker navigates to
 *  this id (mirrors NewRequestScreen.onCreated → openSessionAdaptive). */
@Serializable
data class AdoptSessionResp(val id: String)

/** Response from POST /api/sessions/:id/detach — the server hands the session back to a local
 *  Claude Code CLI process and returns the exact resumeCmd the user can paste into a terminal.
 *  [claudeSessionId] is the upstream Claude Code session id (matches NewSessionReq.claudeSessionId
 *  on adopted rows); [cwd] is the directory the terminal must be inside before running the resume
 *  command. The detail screen shows `resumeCmd` copyable next to a "handed off to a terminal"
 *  banner when `Session.detached == true`. */
@Serializable
data class DetachResp(
    val cwd: String,
    val claudeSessionId: String,
    val resumeCmd: String,
)

