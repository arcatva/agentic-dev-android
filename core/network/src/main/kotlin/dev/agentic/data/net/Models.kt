package dev.agentic.data.net

import androidx.compose.runtime.Immutable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import dev.agentic.domain.CommitLike
import dev.agentic.domain.SessionSnapshot
import dev.agentic.domain.WorkflowRunState
import kotlinx.serialization.json.JsonElement

@Immutable
@Serializable
data class Session(
    override val id: String,
    override val prompt: String = "",
    override val status: String = "pending",
    val error: String? = null,
    // Structured stop reason (auth_error/usage_limit/wall_timeout/idle_timeout/crashed/interrupted/claude_error).
    // null = clean turn or older backend that predates the field; default keeps deserialization backward-compatible.
    override val errorKind: String? = null,
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
    // true = turn finished, idle accepting new message even if a background workflow runs; false = busy mid-turn; null = before first turn.
    override val awaitingInput: Boolean? = null,
    // true when turn is done/idle but a background workflow is still running (list shows it as running).
    override val workflowRunning: Boolean = false,
    // Authoritative wire payload of the prompt session is PARKED on (AskUserQuestion/perm/plan awaiting user), else null. Lets the client render the awaiting card from source-of-truth instead of inferring from fragile log.
    val pendingPrompt: JsonElement? = null,
    /** Server-side: id of the session this one was forked from (SessionScreen "Forked from …" chip), or null. */
    val parentSessionId: String? = null,
    /** Session group id (DB-backed folder); null = uncategorized. */
    val groupId: String? = null,
    /** Discord-style monotonic counter incremented server-side at each "your turn" point. Unread = unreadEventId > client's ackedEventId; eliminates timestamp comparison / clock-skew / client-side idle loops. */
    override val unreadEventId: Long = 0,
    /** Server-authoritative: highest unreadEventId the user has acknowledged (via PUT /api/sessions/:id/ack). */
    override val ackedEventId: Long = 0,
    /** When true, the server auto-resumes after a usage-limit stop (5h/7d). Defaults true so older backends default-on safely (a missed resume is worse than a retry). */
    val autoResume: Boolean = true,
    /** Epoch ms of scheduled auto-resume, or null. */
    val autoResumeAt: Long? = null,
    /** "native" (POST /api/sessions), "fork" (POST /api/sessions/:id/fork), or "adopted" (POST /api/sessions/adopt). */
    val origin: String = "native",
    /** True when handed off to an external Claude Code CLI via POST /api/sessions/:id/detach; frozen stream watermark, no live follow-ups; detail screen shows the "handed off" banner with the `resumeCmd`. */
    val detached: Boolean = false,
) : SessionSnapshot

@Immutable
@Serializable
data class Activity(val turns: Int = 0, val lastSkill: String? = null)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

@Serializable
data class SessionDetail(
    val session: Session,
    val log: List<String> = emptyList(),
    // Windowed-load coords (server's GET ?limit path): `log` may be only the LAST `total-start` rendered
    // lines. Live-stream cursor `since` is a rendered-offset into the FULL log, so it must advance to
    // `total`, NOT `log.size`.
    val start: Int = 0,
    val total: Int? = null,
)

/** Discord-style cursor-paginated structured events (pre-parsed ClaudeEvent::to_wire() — no raw JSONL re-parsing on the client). */
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

/** An available slash command (from GET /api/commands, the SDK's live list). [name] has no leading `/`. */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    @SerialName("argumentHint") val argumentHint: String = "",
)

/** [name] is the full `<plugin>@<marketplace>` id (the exact key claude's `enabledPlugins` map expects, echoed verbatim in [NewSessionReq.hiddenPlugins]). */
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
    /** Skills to HIDE — backend maps to `skillOverrides:{<name>:"off"}` in per-session `--settings`. */
    val hiddenSkills: List<String> = emptyList(),
    /** Plugins (`<plugin>@<marketplace>` ids) to DISABLE — backend maps to `enabledPlugins:{<id>:false}`. */
    val hiddenPlugins: List<String> = emptyList(),
    /** Skills to FORCE ON for this session (override global-off). */
    val forcedOnSkills: List<String> = emptyList(),
    /** Plugins to FORCE ON for this session (override global-off). */
    val forcedOnPlugins: List<String> = emptyList(),
    /** MCP servers to FORCE ON for this session (override global-off). */
    val forcedOnMcpServers: List<String> = emptyList(),
    /** MCP servers to HIDE for this session (override global-on). */
    val hiddenMcpServers: List<String> = emptyList(),
    /** Extra MCP servers to ADD for this session only (ad-hoc, not globally configured). */
    val extraMcpServers: List<McpServerDef> = emptyList(),
    val prompt: String,
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    /** Session-scoped CLAUDE.md content — backend writes it into the session dir so Claude Code loads it as project memory, layered on top of each repo's own CLAUDE.md. null/blank = no extra guidance. */
    val claudeMd: String? = null,
    /** Files staged via POST /api/uploads before this session existed; backend moves each into the new session's uploads/ dir before the prompt runs. Empty = none. */
    val stagedUploads: List<StagedUpload> = emptyList(),
)

@Serializable
data class IdResp(val id: String)

@Serializable
data class PromptReq(val prompt: String, val setTitle: Boolean = true, val model: String? = null, val effort: String? = null, val permissionMode: String? = null)

/** Body for PATCH /api/sessions/:id — partial session settings update (null = leave that column unchanged). */
@Serializable
data class PatchSessionReq(
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    val permissionMode: String? = null,
    /** "" = remove from group; null = leave unchanged. */
    val groupId: String? = null,
    /** null = leave unchanged; false also cancels a scheduled resume server-side. */
    val autoResume: Boolean? = null,
)

/** Body for POST /api/sessions/:id/permission — feedback is the deny reason or plan-revision note (ignored on allow). */
@Serializable
data class PermDecisionReq(val decision: String, val feedback: String? = null)

// ── Session grouping (folders) ──

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

/** Response from POST /api/uploads (pre-session staging) + per-attachment payload echoed in [NewSessionReq.stagedUploads]. [path] is `uploads/<name>` — what the `[attached: ...]` marker references once the backend adopts the file. */
@Serializable
data class StagedUpload(val token: String, val name: String, val path: String)

/** Exactly one transport must be supplied: stdio (set [command], optionally [args]/[env]) or http/sse (set [url], [type], optionally [headers]). [name] non-empty and != "agentic". Null fields omitted from JSON. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class McpServerDef(
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val command: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val args: List<String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val env: Map<String, String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val type: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val headers: Map<String, String>? = null,
)

// mtime is Double, not Long: the server sends fractional ms (statSync mtimeMs); a Long would fail to
// deserialize a JSON float and silently drop the whole list. Convert with .toLong() at use.
@Serializable
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
    override val status: String = "",
    val summary: String? = null,
    val agentCount: Int? = null,
    /** Creation time (epoch ms); 0 if backend predates the field (degrades to insertion-order sort). */
    val createdAt: Long = 0,
    val phases: List<WorkflowPhase> = emptyList(),
    val agents: List<WorkflowAgent> = emptyList(),
    val logs: List<String> = emptyList(),
) : WorkflowRunState

@Serializable
data class WorkflowList(val workflows: List<WorkflowRun> = emptyList())

@Serializable
data class AgentTranscript(val transcript: String = "")

@Serializable
data class UsageWindow(val utilization: Double = 0.0, val resets_at: String? = null)

@Serializable
data class Usage(val five_hour: UsageWindow? = null, val seven_day: UsageWindow? = null)

// ── Push notifications ──
@Serializable
data class DeviceReq(val token: String)

// ── Templates ──
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
    /** Variable names that appear as {{name}} in promptBody. */
    val vars: List<String> = emptyList(),
)

@Serializable
data class TemplateList(val templates: List<Template> = emptyList())

// ── Commit-graph view ──
/** One commit in the session branch's recent history. `at` = epoch-ms (backend converts %at*1000); `isSession` marks commits in `baseSha..HEAD` (work done in this session). */
@Serializable
data class CommitNode(
    override val sha: String,
    val shortSha: String,
    override val parents: List<String> = emptyList(),
    val subject: String = "",
    val author: String = "",
    val at: Long = 0,
    val isSession: Boolean = false,
    /** Branch/tag/HEAD labels pointing at this commit (from git %D). Empty for most commits. */
    val refs: List<GitRef> = emptyList(),
) : CommitLike

/** A ref label on a commit ([kind]: "head" | "branch" | "tag" | "remote"). */
@Serializable
data class GitRef(val name: String, val kind: String = "branch")

@Serializable
data class Uncommitted(val added: Int = 0, val modified: Int = 0, val deleted: Int = 0)

@Serializable
data class RepoCommits(
    val repo: String,
    val commits: List<CommitNode> = emptyList(),
    val uncommitted: Uncommitted? = null,
)

@Serializable
data class CommitsResp(val repos: List<RepoCommits> = emptyList())

/** One changed file for a commit (or working tree); `status` = full word ("added"/"modified"/"deleted"/"renamed"/"unknown"). */
@Serializable
data class CommitFile(
    val path: String,
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class CommitFilesResp(val files: List<CommitFile> = emptyList())

// ── Line-level diff ──
/** [kind]: "context" | "add" | "del". [oldLine]/[newLine] are 1-based and present only on the side(s) the line exists in (context = both, add = new only, del = old only). */
@Serializable
data class DiffLine(
    val kind: String = "context",
    val oldLine: Int? = null,
    val newLine: Int? = null,
    val content: String = "",
)

/** One `@@ … @@` hunk. */
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

/** [binary] = no hunks; [truncated] = raw diff exceeded server cap and was cut. */
@Serializable
data class FileDiff(
    val path: String = "",
    val status: String = "modified",
    val binary: Boolean = false,
    val truncated: Boolean = false,
    val hunks: List<DiffHunk> = emptyList(),
)

@Serializable
data class DiffResp(val diff: FileDiff = FileDiff())

// ── Rewind (code-only restore) ──
/** 0-based user-turn index to restore code state to. */
@Serializable
data class RewindReq(val turnIndex: Int)

// ── Session content search ──

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

@Serializable
data class SearchMatch(
    val field: SearchField,
    val snippet: String,
    val lineIndex: Int,
)

@Serializable
data class SearchHit(
    val session: Session,
    val score: Float,
    val matches: List<SearchMatch>,
)

/** Response shape for `GET /api/sessions/search?q=<text>&limit=<n>`; `query` echoes the trimmed query the server actually searched for. */
@Serializable
data class SearchResponse(
    val query: String,
    val results: List<SearchHit> = emptyList(),
)

// ── Provider registry (BYOK cheap models for delegate fan-out) ──

/** API key is masked (never returned); [hasKey] reflects whether one is stored. Field names map to backend snake_case JSON. */
@Serializable
data class Provider(
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    /** "anthropic" (called directly) or "openai" (through the LiteLLM proxy). */
    val protocol: String = "anthropic",
    /** 0..1 capability axis: router only sends a task to a model capable enough for it. */
    val capability: Float = 0.5f,
    /** What the model is good at — the router reads this. */
    val description: String? = null,
    /** 0..1 scheduling priority: among capable models, the router prefers higher priority. */
    val priority: Float = 0.5f,
    /** 0..1 relative cost: among equally prioritized models, the router prefers lower cost. */
    val cost: Float = 0.5f,
    /** True if this provider MAKES the routing decisions (the LLM-as-router). */
    val router: Boolean = false,
    /** Whether this model participates in routing at all. Off → excluded from the candidate pool. */
    val enabled: Boolean = true,
    @SerialName("has_key") val hasKey: Boolean = false,
)

@Serializable
data class ProviderList(val providers: List<Provider> = emptyList())

// ── Native Claude per-family routing overrides ──

/** One native Claude model as discovered by the Anthropic Models API. */
@Serializable
data class NativeModelRef(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

/** [editable] is false for the `other` catch-all; [customized] means an override row exists. */
@Serializable
data class NativeFamily(
    val family: String,
    val label: String,
    val models: List<NativeModelRef> = emptyList(),
    val capability: Float = 0.5f,
    val priority: Float = 0.5f,
    val cost: Float = 0.5f,
    val description: String = "",
    val enabled: Boolean = true,
    val customized: Boolean = false,
    val editable: Boolean = false,
)

@Serializable
data class NativeFamilyList(val families: List<NativeFamily> = emptyList())

/** Body for POST /api/native-models/{family}. */
@Serializable
data class NativeOverrideReq(
    val capability: Float,
    val priority: Float,
    val cost: Float,
    val description: String = "",
    val enabled: Boolean = true,
)

/** Global cost⇄quality routing knob: 0 = cheapest, 1 = strongest. GET/POST /api/routing. */
@Serializable
data class RoutingConfig(val tradeoff: Float = 0.5f)

// ── Model catalog ──

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

// ── ChatGPT subscription OAuth (connect a personal ChatGPT plan as a GPT provider) ──

/** Response of POST /api/oauth/chatgpt/start — the authorize URL to open + the CSRF state. */
@Serializable
data class OauthStartResp(
    @SerialName("authorize_url") val authorizeUrl: String = "",
    val state: String = "",
)

/** Body of POST /api/oauth/chatgpt/complete. */
@Serializable
data class OauthCompleteReq(val state: String, val code: String)

/** GET /api/oauth/chatgpt — connection state for the UI badge (never carries the token). */
@Serializable
data class OauthStatus(
    val connected: Boolean = false,
    @SerialName("account_id") val accountId: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("needs_relogin") val needsRelogin: Boolean = false,
    val model: String = "",
)

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
    val enabled: Boolean = true,
)

// ── Adopt a Claude Code CLI session into this server ──
/** One candidate in GET /api/adoptable — discoverable Claude Code session log on disk. [resumable]=false means the JSONL ended without terminal status (read-only import only). mtimeMs is fractional ms from statSync(2); Double to avoid dropping when server sends integer. */
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

/** Body for POST /api/sessions/adopt — pick which Claude Code session (by sessionId) + which cwd to import it under. The cwd MUST match what the discoverable was scanned under (server pins to scan root). */
@Serializable
data class AdoptSessionReq(
    val claudeSessionId: String,
    val cwd: String,
)

/** Picker navigates to this id on success. */
@Serializable
data class AdoptSessionResp(val id: String)

/** Server hands the session back to a local Claude Code CLI and returns the exact resumeCmd the user pastes in a terminal. [cwd] is where the terminal must be before running resume; detail screen shows resumeCmd copyable when `Session.detached == true`. */
@Serializable
data class DetachResp(
    val cwd: String,
    val claudeSessionId: String,
    val resumeCmd: String,
)

// ── Global Settings CRUD (S5c) ──

@Serializable
data class AddPluginReq(val id: String)

/** [source] is the ready-to-install reference to POST to /api/skills/install; [sourceRepo] is the configured store source it came from (display/grouping). */
@Serializable
data class CatalogSkill(
    val name: String,
    val description: String = "",
    val source: String,
    val sourceRepo: String = "",
    /** true = store version differs; null = unknown (not installed or installed without provenance metadata). */
    val updateAvailable: Boolean? = null,
)

/** [errors] carries per-source scan failures — one broken source degrades instead of failing the whole store. */
@Serializable
data class SkillCatalogResp(
    val skills: List<CatalogSkill> = emptyList(),
    val errors: List<String> = emptyList(),
)

@Serializable
data class SkillSourcesResp(val sources: List<String> = emptyList())

/** Body for POST /api/skills/sources (add a store source). */
@Serializable
data class AddSkillSourceReq(val source: String)

/** [source] = `owner/repo[/path]` or github.com URL; [update] replaces an existing install of the same name (atomic swap). */
@Serializable
data class InstallSkillReq(val source: String, val update: Boolean = false)

