package dev.agentic.data.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.client.plugins.ResponseException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import dev.agentic.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/** Ktor-based [AgenticApi] implementation. */
class KtorAgenticApi(
    override var baseUrl: String,
    override var token: String? = null,
    /** Look up the pinned server-cert fingerprint for a host key (see [certHostKey]); default never pins (tests / callers that don't need TOFU). */
    private val pinnedFingerprintFor: (hostKey: String) -> String? = { null },
) : AgenticApi {

    override var onUnauthorized: (() -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // TOFU TLS keyed off the CURRENT baseUrl (read live — baseUrl is mutable). Shared by both OkHttp
    // clients below so REST and WS trust the same cert.
    private val tofuTls = TofuTls(
        currentHostKey = { certHostKey(baseUrl) },
        pinnedFingerprintFor = pinnedFingerprintFor,
    )

    // Kept so [evictConnections] can drop pooled sockets on app-foreground (a half-open backgrounded
    // socket must not be reused or the next call hangs).
    private val rpcOkHttp = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .sslSocketFactory(tofuTls.socketFactory, tofuTls.trustManager)
        .hostnameVerifier(tofuTls.hostnameVerifier)
        .build()

    // REST client. Now that the long-lived WS lives on streamClient below we can give every REST
    // call REAL socket/request timeouts (the "frozen until app restart" fix: a poll/refetch on a
    // half-open pooled connection used to hang forever — only connectTimeout was set, so every
    // HTTP-GET self-heal wedged). socketTimeoutMillis bounds the gap between bytes (breaks a
    // half-open read); requestTimeoutMillis is a generous overall cap, overridden per-request by
    // large fetches (session log, file download).
    private val client = HttpClient(OkHttp) {
        engine { preconfigured = rpcOkHttp }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            socketTimeoutMillis = 20_000
            requestTimeoutMillis = 60_000
        }
        // expectSuccess=true → Ktor throws ResponseException for non-2xx (mapped to AppError.Http);
        // the 401 validateResponse below fires first (headers phase), so UnauthorizedException is
        // raised before ResponseException for 401 — priority is intentional. A 401 would otherwise
        // parse as empty data (e.g. SessionList{sessions=[]}) and the user just sees a blank screen.
        expectSuccess = true
        HttpResponseValidator {
            validateResponse { resp ->
                if (resp.status == HttpStatusCode.Unauthorized) {
                    // /api/login 401 = "wrong password", NOT expired session — don't fire app-wide
                    // logout (would bounce user out mid-attempt). expectSuccess=true still raises
                    // ResponseException → AppError.Http(401), which the login screen maps to "wrong password".
                    if (!resp.call.request.url.encodedPath.endsWith("/api/login")) {
                        onUnauthorized?.invoke(); throw UnauthorizedException()
                    }
                }
            }
        }
    }

    // Dedicated client for the long-lived session WS ONLY. Deliberately NO socket/request timeout
    // (a legitimately idle stream must never be torn down by a timer). 20s ping keeps NAT mappings
    // open; liveness is enforced at the app layer in stream() instead via HEARTBEAT (no gap > 2.5x).
    private val streamOkHttp = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .sslSocketFactory(tofuTls.socketFactory, tofuTls.trustManager)
        .hostnameVerifier(tofuTls.hostnameVerifier)
        .build()

    private val streamClient = HttpClient(OkHttp) {
        engine { preconfigured = streamOkHttp }
        install(WebSockets) { pingInterval = 20_000L }
        install(HttpTimeout) { connectTimeoutMillis = 6_000 }
    }

    private fun HttpRequestBuilder.auth() { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    override suspend fun login(password: String): String {
        return try {
            val r: LoginResp = client.post("$baseUrl/api/login") {
                contentType(ContentType.Application.Json); setBody(LoginReq(password))
            }.body()
            token = r.token
            AppLog.d("API", "POST login -> OK")
            r.token
        } catch (e: Exception) {
            AppLog.w("API", "POST login -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun sessions(): List<Session> {
        return try {
            val r: List<Session> = client.get("$baseUrl/api/sessions") { auth() }.body<SessionList>().sessions
            AppLog.d("API", "GET sessions -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Content search across session transcripts. Per plan: GET …/sessions/search?q=<text> →
     *  SearchResponse { query, results }. We decode via the configured [json] instance (handles
     *  unknown fields leniently, matches the ContentNegotiation plugin) rather than a fresh
     *  Json{}, so behaviour stays consistent with the rest of the API surface. */
    /** Decoded via the shared `json` instance (lenient + matches ContentNegotiation plugin) rather than a fresh Json{}, to stay consistent with the rest of the API surface. */
    override suspend fun searchSessions(q: String): SearchResponse {
        return try {
            val r: SearchResponse = client.get("$baseUrl/api/sessions/search") { auth(); parameter("q", q) }
                .body<SearchResponse>()
            AppLog.d("API", "GET sessions/search -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/search -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun session(id: String, limit: Int?): SessionDetail {
        return try {
            val r: SessionDetail = client.get("$baseUrl/api/sessions/$id") { auth(); if (limit != null) parameter("limit", limit) }.body()
            AppLog.d("API", "GET sessions/${id.take(8)} -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun sessionEvents(id: String, limit: Int?, before: Long?, after: Long?, around: Long?): SessionEventsResponse {
        return try {
            val r: SessionEventsResponse = client.get("$baseUrl/api/sessions/$id/events") {
                auth(); if (limit != null) parameter("limit", limit); if (before != null) parameter("before", before); if (after != null) parameter("after", after); if (around != null) parameter("around", around)
            }.body()
            AppLog.d("API", "GET sessions/${id.take(8)}/events -> OK (${r.events.size} events, latestEventId=${r.latestEventId})")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/events -> FAILED: ${e.message}")
            throw e
        }
    }

    /** `limit=0` returns an empty log; unwrap [SessionDetail.session] to avoid deserializing a 200MB log. */
    override suspend fun get(id: String): Session {
        return try {
            val r: Session = client.get("$baseUrl/api/sessions/$id") { auth(); parameter("limit", 0) }.body<SessionDetail>().session
            AppLog.d("API", "GET sessions/${id.take(8)} -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun usage(): Usage {
        return try {
            val r: Usage = client.get("$baseUrl/api/usage") { auth() }.body()
            AppLog.d("API", "GET usage -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET usage -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun workflows(id: String): List<WorkflowRun> {
        return try {
            val r: List<WorkflowRun> = client.get("$baseUrl/api/sessions/$id/workflows") { auth() }.body<WorkflowList>().workflows
            AppLog.d("API", "GET sessions/${id.take(8)}/workflows -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/workflows -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun workflowAgent(id: String, runId: String, agentId: String): String {
        return try {
            val r: String = client.get("$baseUrl/api/sessions/$id/workflows/$runId/agents/$agentId") { auth() }.body<AgentTranscript>().transcript
            AppLog.d("API", "GET sessions/${id.take(8)}/workflows/${runId.take(8)}/agents/${agentId.take(8)} -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/workflows/${runId.take(8)}/agents/${agentId.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun repos(): RepoList {
        return try {
            val r: RepoList = client.get("$baseUrl/api/repos") { auth() }.body()
            AppLog.d("API", "GET repos -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET repos -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun skills(): List<SkillInfo> {
        return try {
            val r: List<SkillInfo> = client.get("$baseUrl/api/skills") { auth() }.body()
            AppLog.d("API", "GET skills -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET skills -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun plugins(): List<PluginInfo> {
        return try {
            val r: List<PluginInfo> = client.get("$baseUrl/api/plugins") { auth() }.body()
            AppLog.d("API", "GET plugins -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET plugins -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun create(req: NewSessionReq): String {
        return try {
            val r: String = client.post("$baseUrl/api/sessions") { auth(); contentType(ContentType.Application.Json); setBody(req) }.body<IdResp>().id
            AppLog.d("API", "POST sessions -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Fork a session. Returns the new id. Throws on non-2xx (the same auth/401 handling as the
     *  other routes — see [auth] + [onUnauthorized]). */
    override suspend fun fork(id: String): String {
        return try {
            val r: String = client.post("$baseUrl/api/sessions/$id/fork") { auth() }.body<ForkResp>().id
            AppLog.d("API", "POST sessions/${id.take(8)}/fork -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/fork -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun followUp(id: String, prompt: String, setTitle: Boolean, model: String?, effort: String?, permissionMode: String?): Int {
        return try {
            val r: Int = client.post("$baseUrl/api/sessions/$id/messages") { auth(); contentType(ContentType.Application.Json); setBody(PromptReq(prompt, setTitle, model, effort, permissionMode)) }.body<FollowUpResp>().since
            AppLog.d("API", "POST sessions/${id.take(8)}/messages -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/messages -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun patchSession(id: String, req: PatchSessionReq): Session {
        return try {
            val r: Session = client.patch("$baseUrl/api/sessions/$id") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(req)
            }.body()
            AppLog.d("API", "PATCH sessions/${id.take(8)} -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "PATCH sessions/${id.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Upload a file into the session worktree's uploads/. */
    override suspend fun uploadFile(id: String, bytes: ByteArray, filename: String): String {
        return try {
            val r: String = client.post("$baseUrl/api/sessions/$id/upload") {
                auth()
                setBody(MultiPartFormDataContent(formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    })
                }))
            }.body<UploadResp>().path
            AppLog.d("API", "POST sessions/${id.take(8)}/upload -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/upload -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Stage a file before a session exists (New-request attachments). Returns token + sanitized name + uploads/<name> path the client embeds in `[attached: ...]`. */
    override suspend fun uploadStaging(bytes: ByteArray, filename: String): StagedUpload {
        return try {
            val r: StagedUpload = client.post("$baseUrl/api/uploads") {
                auth()
                setBody(MultiPartFormDataContent(formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                        append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    })
                }))
            }.body()
            AppLog.d("API", "POST uploads -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST uploads -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Raw bytes of a file in the session worktree (inline image previews — small). Dispatchers.IO so body accumulation never competes with UI on Main. */
    override suspend fun fileBytes(id: String, path: String, onProgress: ((Float?) -> Unit)?): ByteArray {
        return try {
            val r: ByteArray = withContext(Dispatchers.IO) {
                client.get("$baseUrl/api/sessions/$id/file") {
                    auth(); parameter("path", path)
                    // Request-scoped timeouts (do NOT touch the client-level config that deliberately
                    // leaves the long-lived WS stream without a request timeout).
                    timeout { socketTimeoutMillis = 60_000; requestTimeoutMillis = 600_000 }
                    if (onProgress != null) onDownload { sent, total ->
                        onProgress(if (total != null && total > 0L) (sent.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                }.readBytes()
            }
            AppLog.d("API", "GET sessions/${id.take(8)}/file -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/file -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Stream download with automatic Range/If-Range resume (outbox save path). Bytes go straight to [dest] (never a whole-file ByteArray), on Dispatchers.IO. */
    override suspend fun downloadFileTo(
        id: String,
        path: String,
        dest: java.io.File,
        onProgress: ((Long, Long?) -> Unit)?,
    ) {
        try {
            withContext(Dispatchers.IO) {
                ResumableDownloader(client).download(
                    url = "$baseUrl/api/sessions/$id/file",
                    dest = dest,
                    configure = {
                        auth(); parameter("path", path)
                        // Per-ATTEMPT timeouts: short 15s idle is safe now — a stalled attempt is
                        // cheap because the next attempt RESUMES where this one stopped rather than
                        // restarting. Keeps the UI's "stalled — auto-resuming" label honest (resume
                        // follows within seconds). 600s caps ONE attempt, not the whole transfer —
                        // each resume gets a fresh budget.
                        timeout { socketTimeoutMillis = 15_000; requestTimeoutMillis = 600_000 }
                    },
                    onProgress = onProgress,
                )
            }
            AppLog.d("API", "GET sessions/${id.take(8)}/file (stream) -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/file (stream) -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Files the agent deliberately shared (placed in the session's outbox/). */
    override suspend fun outbox(id: String): List<SharedFile> {
        return try {
            val r: List<SharedFile> = client.get("$baseUrl/api/sessions/$id/outbox") { auth() }.body<OutboxResp>().files
            AppLog.d("API", "GET sessions/${id.take(8)}/outbox -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/outbox -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun kill(id: String) {
        try {
            client.delete("$baseUrl/api/sessions/$id") { auth() }
            AppLog.d("API", "DELETE sessions/${id.take(8)} -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "DELETE sessions/${id.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun interrupt(id: String) {
        try {
            client.post("$baseUrl/api/sessions/$id/interrupt") { auth() }
            AppLog.d("API", "POST sessions/${id.take(8)}/interrupt -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/interrupt -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun ackSession(id: String, eventId: Long) {
        try {
            client.put("$baseUrl/api/sessions/$id/ack") {
                auth()
                contentType(ContentType.Application.Json)
                setBody("""{"eventId":$eventId}""")
            }
            AppLog.d("API", "PUT sessions/${id.take(8)}/ack eventId=$eventId -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "PUT sessions/${id.take(8)}/ack -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun respondPermission(id: String, decision: String, feedback: String?) {
        try {
            client.post("$baseUrl/api/sessions/$id/permission") {
                auth(); contentType(ContentType.Application.Json); setBody(PermDecisionReq(decision, feedback))
            }
            AppLog.d("API", "POST sessions/${id.take(8)}/permission -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/permission -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun delete(id: String) {
        try {
            client.post("$baseUrl/api/sessions/$id/delete") { auth() }
            AppLog.d("API", "POST sessions/${id.take(8)}/delete -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/delete -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Open the session stream; [onLine] per event frame. Token goes in the query only when non-null ("token=null" would be rejected). A WS upgrade rejected with 401 fires [onUnauthorized] (matches HTTP 401) before rethrow. */
    override suspend fun stream(id: String, since: Int?, onLine: suspend (String) -> Unit) {
        val wsBase = baseUrl.replaceFirst("http", "ws")
        val parts = buildList {
            token?.let { add("token=$it") }
            since?.let { add("since=$it") }
        }
        val q = parts.joinToString("&")
        val url = if (q.isNotEmpty()) "$wsBase/api/sessions/$id/stream?$q" else "$wsBase/api/sessions/$id/stream"
        try {
            AppLog.v("WS", "open id=$id since=$since")
            streamClient.webSocket(url) {
                // Liveness watchdog (Discord-gateway): server HEARTBEATs every ~N ms even when idle;
                // any gap > 2.5x N = socket dead (half-open) even if TCP/OkHttp's ping hasn't noticed.
                // Returning ends the WS so the reconnect loop RESUMEs from last seq. Default 30s.
                var idleCapMs = 30_000L
                // Parse the cap from HELLO exactly ONCE. Flag (not `idleCapMs == default`) because a
                // 10s heartbeat yields a 30s cap == default — re-parsing every frame's JSON would
                // tax the network thread. Substring pre-check avoids parsing non-HELLO frames; first
                // frame against an old server (no HELLO) just marks it done.
                var watchdogConfigured = false
                var nFrames = 0
                var lastAt = System.currentTimeMillis()
                while (true) {
                    val received = withTimeoutOrNull(idleCapMs) { incoming.receiveCatching() }
                    if (received == null) { AppLog.w("WS", "watchdog FIRED id=$id idle=${idleCapMs}ms frames=$nFrames"); break }
                    val frame = received.getOrNull()
                    if (frame == null) { AppLog.v("WS", "incoming closed id=$id frames=$nFrames"); break }
                    if (frame is Frame.Text) {
                        val now = System.currentTimeMillis()
                        val gap = now - lastAt; lastAt = now
                        nFrames++
                        // Surface stalls: a gap > ~1.5 heartbeats = frames stopped even though socket
                        // is open — the live-lag signature.
                        if (gap > 5_000) AppLog.v("WS", "gap ${gap}ms before frame#$nFrames id=$id")
                        val text = frame.readText()
                        if (!watchdogConfigured) {
                            if (text.contains("\"hello\"")) {
                                heartbeatCapFrom(text)?.let { idleCapMs = it; watchdogConfigured = true; AppLog.v("WS", "hello id=$id cap=${idleCapMs}ms") }
                            } else {
                                watchdogConfigured = true
                            }
                        }
                        onLine(text)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w("WS", "stream exception id=$id: ${e.message}")
            // OkHttp throws ProtocolException (extends IOException) on a rejected WS upgrade
            // ("Expected HTTP 101 response but was '401 Unauthorized'"). Surface as the same
            // onUnauthorized signal used by HTTP 401.
            val msg = e.message ?: ""
            if (msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true)) {
                onUnauthorized?.invoke()
            }
            throw e
        }
    }

    /** Register an FCM push token with the backend so it can send finish-line notifications. */
    override suspend fun registerDevice(token: String) {
        try {
            client.post("$baseUrl/api/devices") {
                auth(); contentType(ContentType.Application.Json); setBody(DeviceReq(token))
            }
            AppLog.d("API", "POST devices -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST devices -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Templates ──
    override suspend fun getTemplates(): List<Template> {
        return try {
            val r: List<Template> = client.get("$baseUrl/api/templates") { auth() }.body<TemplateList>().templates
            AppLog.d("API", "GET templates -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET templates -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun putTemplates(templates: List<Template>) {
        try {
            client.put("$baseUrl/api/templates") {
                auth(); contentType(ContentType.Application.Json); setBody(TemplateList(templates))
            }
            AppLog.d("API", "PUT templates -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "PUT templates -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Model catalog ──
    override suspend fun models(): List<ModelEntry> {
        return try {
            val r: List<ModelEntry> = client.get("$baseUrl/api/models") { auth() }.body<ModelsResponse>().models
            AppLog.d("API", "GET models -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET models -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun sessionStartModels(): List<ModelEntry> {
        return try {
            val r: List<ModelEntry> = client.get("$baseUrl/api/models") {
                auth(); parameter("scope", "session_start")
            }.body<ModelsResponse>().models
            AppLog.d("API", "GET models?scope=session_start -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET models?scope=session_start -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Session groups (folders) ──
    override suspend fun listGroups(): List<Group> {
        return try {
            val r: List<Group> = client.get("$baseUrl/api/groups") { auth() }.body<GroupList>().groups
            AppLog.d("API", "GET groups -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET groups -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun createGroup(req: CreateGroupReq): Group {
        return try {
            val r: Group = client.post("$baseUrl/api/groups") {
                auth(); contentType(ContentType.Application.Json); setBody(req)
            }.body<GroupResp>().group
            AppLog.d("API", "POST groups -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST groups -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun updateGroup(id: String, req: UpdateGroupReq): Group {
        return try {
            val r: Group = client.patch("$baseUrl/api/groups/${id.encodeURLPathPart()}") {
                auth(); contentType(ContentType.Application.Json); setBody(req)
            }.body<GroupResp>().group
            AppLog.d("API", "PATCH groups/${id.take(8)} -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "PATCH groups/${id.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun deleteGroup(id: String) {
        try {
            client.delete("$baseUrl/api/groups/${id.encodeURLPathPart()}") { auth() }
            AppLog.d("API", "DELETE groups/${id.take(8)} -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "DELETE groups/${id.take(8)} -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Provider registry (BYOK) ──
    override suspend fun providers(): List<Provider> {
        return try {
            val r: List<Provider> = client.get("$baseUrl/api/providers") { auth() }.body<ProviderList>().providers
            AppLog.d("API", "GET providers -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET providers -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun addProvider(req: NewProviderReq) {
        try {
            client.post("$baseUrl/api/providers") {
                auth(); contentType(ContentType.Application.Json); setBody(req)
            }
            AppLog.d("API", "POST providers -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST providers -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun deleteProvider(name: String) {
        try {
            client.delete("$baseUrl/api/providers/${name.encodeURLPathPart()}") { auth() }
            AppLog.d("API", "DELETE providers/$name -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "DELETE providers/$name -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── ChatGPT subscription OAuth ──
    override suspend fun chatgptLoginStart(): ChatgptLoginStart {
        return try {
            val r: ChatgptLoginStart = client.post("$baseUrl/api/providers/chatgpt/login/start") { auth() }.body()
            AppLog.d("API", "POST chatgpt/login/start -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST chatgpt/login/start -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun chatgptLoginComplete(code: String, state: String): ChatgptStatus {
        return try {
            client.post("$baseUrl/api/providers/chatgpt/login/complete") {
                auth(); contentType(ContentType.Application.Json)
                setBody(ChatgptCompleteReq(code, state))
            }
            AppLog.d("API", "POST chatgpt/login/complete -> OK")
            // complete returns {ok, account_id, expires_at}; read the authoritative status next.
            chatgptStatus()
        } catch (e: Exception) {
            AppLog.w("API", "POST chatgpt/login/complete -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun chatgptStatus(): ChatgptStatus {
        return try {
            client.get("$baseUrl/api/providers/chatgpt/status") { auth() }.body()
        } catch (e: Exception) {
            AppLog.w("API", "GET chatgpt/status -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Native Claude per-family routing overrides ──
    override suspend fun nativeModels(): List<NativeFamily> {
        return try {
            val r: List<NativeFamily> = client.get("$baseUrl/api/native-models") { auth() }
                .body<NativeFamilyList>().families
            AppLog.d("API", "GET native-models -> OK (${r.size})")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET native-models -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun putNativeOverride(family: String, req: NativeOverrideReq) {
        try {
            client.post("$baseUrl/api/native-models/${family.encodeURLPathPart()}") {
                auth(); contentType(ContentType.Application.Json); setBody(req)
            }
            AppLog.d("API", "POST native-models/$family -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST native-models/$family -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun deleteNativeOverride(family: String) {
        try {
            client.delete("$baseUrl/api/native-models/${family.encodeURLPathPart()}") { auth() }
            AppLog.d("API", "DELETE native-models/$family -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "DELETE native-models/$family -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun getRouting(): RoutingConfig {
        return try {
            val r: RoutingConfig = client.get("$baseUrl/api/routing") { auth() }.body()
            AppLog.d("API", "GET routing -> OK (tradeoff=${r.tradeoff})")
            r
        } catch (e: Exception) {
            // Propagate (matches every other method here) so the global auth/error handling sees a
            // 401 etc. The VM wraps this call in runCatchingOutcome and keeps the default on failure.
            AppLog.w("API", "GET routing -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun setRouting(tradeoff: Float) {
        try {
            client.post("$baseUrl/api/routing") {
                auth(); contentType(ContentType.Application.Json); setBody(RoutingConfig(tradeoff))
            }
            AppLog.d("API", "POST routing -> OK (tradeoff=$tradeoff)")
        } catch (e: Exception) {
            AppLog.w("API", "POST routing -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Global Settings (S5a) ──
    override suspend fun getGlobalSettings(): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.get("$baseUrl/api/global-settings") { auth() }.body()
            AppLog.d("API", "GET global-settings -> OK (${r.size})")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET global-settings -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun toggleGlobalComponent(kind: String, id: String, enabled: Boolean): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.post("$baseUrl/api/global-settings/toggle") {
                auth(); contentType(ContentType.Application.Json); setBody(ToggleComponentReq(kind, id, enabled))
            }.body()
            AppLog.d("API", "POST global-settings/toggle kind=$kind id=$id enabled=$enabled -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST global-settings/toggle -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Global Settings CRUD (S5c) ──
    override suspend fun getSkillCatalog(refresh: Boolean): SkillCatalogResp {
        return try {
            val r: SkillCatalogResp = client.get("$baseUrl/api/skills/catalog") {
                auth(); if (refresh) parameter("refresh", "true")
            }.body()
            AppLog.d("API", "GET skills/catalog refresh=$refresh -> OK (${r.skills.size}, ${r.errors.size} errors)")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET skills/catalog -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun getSkillSources(): List<String> {
        return try {
            val r: SkillSourcesResp = client.get("$baseUrl/api/skills/sources") { auth() }.body()
            AppLog.d("API", "GET skills/sources -> OK (${r.sources.size})")
            r.sources
        } catch (e: Exception) {
            AppLog.w("API", "GET skills/sources -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun addSkillSource(source: String): List<String> {
        return try {
            val r: SkillSourcesResp = client.post("$baseUrl/api/skills/sources") {
                auth(); contentType(ContentType.Application.Json); setBody(AddSkillSourceReq(source))
            }.body()
            AppLog.d("API", "POST skills/sources source=$source -> OK")
            r.sources
        } catch (e: Exception) {
            AppLog.w("API", "POST skills/sources -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun deleteSkillSource(source: String): List<String> {
        return try {
            val r: SkillSourcesResp = client.delete("$baseUrl/api/skills/sources") {
                auth(); parameter("source", source)
            }.body()
            AppLog.d("API", "DELETE skills/sources source=$source -> OK")
            r.sources
        } catch (e: Exception) {
            AppLog.w("API", "DELETE skills/sources -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun installSkill(source: String, update: Boolean): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.post("$baseUrl/api/skills/install") {
                auth(); contentType(ContentType.Application.Json); setBody(InstallSkillReq(source, update))
            }.body()
            AppLog.d("API", "POST skills/install source=$source update=$update -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST skills/install -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun deleteSkill(name: String): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.delete("$baseUrl/api/skills/${name.encodeURLPathPart()}") { auth() }.body()
            AppLog.d("API", "DELETE skills/$name -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "DELETE skills/$name -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun installPlugin(id: String): List<ComponentInfo> {
        return try {
            // Plugin install shells out to the claude CLI — can stay quiet for well over the
            // client-level 20s socket-idle cap while still working. Override BOTH the socket idle
            // cap AND the request cap so a quiet-but-alive CLI isn't cut.
            val r: List<ComponentInfo> = client.post("$baseUrl/api/plugins") {
                auth(); contentType(ContentType.Application.Json); setBody(AddPluginReq(id))
                timeout {
                    requestTimeoutMillis = 180_000
                    socketTimeoutMillis  = 180_000
                    connectTimeoutMillis =  20_000
                }
            }.body()
            AppLog.d("API", "POST plugins id=$id -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST plugins -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun uninstallPlugin(id: String): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.delete("$baseUrl/api/plugins/${id.encodeURLPathPart()}") {
                auth()
                timeout {
                    requestTimeoutMillis = 180_000
                    socketTimeoutMillis  = 180_000
                    connectTimeoutMillis =  20_000
                }
            }.body()
            AppLog.d("API", "DELETE plugins/$id -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "DELETE plugins/$id -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun addMcpServer(def: McpServerDef): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.post("$baseUrl/api/mcp-servers") {
                auth(); contentType(ContentType.Application.Json); setBody(def)
            }.body()
            AppLog.d("API", "POST mcp-servers name=${def.name} -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST mcp-servers -> FAILED: ${e.message}")
            throw e
        }
    }

    override suspend fun deleteMcpServer(name: String): List<ComponentInfo> {
        return try {
            val r: List<ComponentInfo> = client.delete("$baseUrl/api/mcp-servers/${name.encodeURLPathPart()}") { auth() }.body()
            AppLog.d("API", "DELETE mcp-servers/$name -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "DELETE mcp-servers/$name -> FAILED: ${e.message}")
            throw e
        }
    }

    // ── Commit-graph view ──
    override suspend fun commits(id: String): List<RepoCommits> {
        return try {
            val r: List<RepoCommits> = client.get("$baseUrl/api/sessions/$id/commits") { auth() }.body<CommitsResp>().repos
            AppLog.d("API", "GET sessions/${id.take(8)}/commits -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/commits -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Changed files for a commit (or "working"). sha = path segment (URL-encoded); repo goes in the query. */
    override suspend fun commitFiles(id: String, repo: String, sha: String): List<CommitFile> {
        return try {
            val r: List<CommitFile> = client.get("$baseUrl/api/sessions/$id/commits/${sha.encodeURLPathPart()}/files") {
                auth(); parameter("repo", repo)
            }.body<CommitFilesResp>().files
            AppLog.d("API", "GET sessions/${id.take(8)}/commits/${sha.take(8)}/files -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/commits/${sha.take(8)}/files -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Line-level diff for one file. sha = path segment; repo + path go in the query (URL-encoded by [parameter], so paths with slashes are safe). */
    override suspend fun commitDiff(id: String, repo: String, sha: String, path: String): FileDiff {
        return try {
            val r: FileDiff = client.get("$baseUrl/api/sessions/$id/commits/${sha.encodeURLPathPart()}/diff") {
                auth(); parameter("repo", repo); parameter("path", path)
            }.body<DiffResp>().diff
            AppLog.d("API", "GET sessions/${id.take(8)}/commits/${sha.take(8)}/diff -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/commits/${sha.take(8)}/diff -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Discard all uncommitted changes in the session's worktrees. */
    override suspend fun discard(id: String) {
        try {
            client.post("$baseUrl/api/sessions/$id/discard") { auth() }
            AppLog.d("API", "POST sessions/${id.take(8)}/discard -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/discard -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Decodes the flat JSON array straight into `List<Adoptable>` (the global `json` already handles missing fields leniently). 404 (server predates the feature) is the only exception swallowed, degraded to "nothing to adopt". */
    override suspend fun adoptable(): List<Adoptable> {
        return try {
            val r: List<Adoptable> = client.get("$baseUrl/api/adoptable") { auth() }.body()
            AppLog.d("API", "GET adoptable -> OK (${r.size})")
            r
        } catch (e: ResponseException) {
            // A server that predates the adopt feature 404s this route. Degrade to "nothing to
            // adopt" rather than surfacing an error banner on older backends.
            if (e.response.status == HttpStatusCode.NotFound) {
                AppLog.d("API", "GET adoptable -> 404 (server predates feature); returning empty")
                emptyList()
            } else {
                AppLog.w("API", "GET adoptable -> FAILED: ${e.message}")
                throw e
            }
        } catch (e: Exception) {
            AppLog.w("API", "GET adoptable -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Adopt a Claude Code session (POST /api/sessions/adopt). Picker navigates to the returned id. */
    override suspend fun adoptSession(req: AdoptSessionReq): String {
        return try {
            val r: String = client.post("$baseUrl/api/sessions/adopt") {
                auth(); contentType(ContentType.Application.Json); setBody(req)
            }.body<AdoptSessionResp>().id
            AppLog.d("API", "POST sessions/adopt -> OK id=$r")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/adopt -> FAILED: ${e.message}")
            throw e
        }
    }

    /** POST /api/sessions/:id/detach. Throws on non-2xx (VM surfaces server body as user error). After success the server sets `Session.detached = true`; the detail screen keeps the "handed off" banner up without an extra round-trip. */
    override suspend fun detach(id: String): DetachResp {
        return try {
            val r: DetachResp = client.post("$baseUrl/api/sessions/${id.encodeURLPathPart()}/detach") { auth() }.body()
            AppLog.d("API", "POST sessions/${id.take(8)}/detach -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/detach -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Restore the working tree to the snapshot before [turnIndex] (code only). Throws on non-2xx. */
    override suspend fun rewind(id: String, turnIndex: Int) {
        try {
            client.post("$baseUrl/api/sessions/$id/rewind") {
                auth(); contentType(ContentType.Application.Json); setBody(RewindReq(turnIndex))
            }
            AppLog.d("API", "POST sessions/${id.take(8)}/rewind -> OK")
        } catch (e: Exception) {
            AppLog.w("API", "POST sessions/${id.take(8)}/rewind -> FAILED: ${e.message}")
            throw e
        }
    }

    /** Drop idle pooled REST connections so the next call opens a fresh socket (app-foreground heal). */
    override fun evictConnections() {
        AppLog.d("API", "evictConnections")
        rpcOkHttp.connectionPool.evictAll()
    }

    override fun close() {
        AppLog.d("API", "close")
        client.close(); streamClient.close()
    }

    /** Derive the watchdog idle cap from HELLO's heartbeatMs (~2.5x + slack). null when not a HELLO so the caller keeps its current cap. */
    private fun heartbeatCapFrom(text: String): Long? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["kind"]?.jsonPrimitive?.content != "hello") return@runCatching null
        val hb = obj["heartbeatMs"]?.jsonPrimitive?.longOrNull ?: return@runCatching null
        (hb * 5) / 2 + 5_000L
    }.onFailure { e ->
        AppLog.w("API", "heartbeatCapFrom parse failed: ${e.message}", e)
    }.getOrNull()
}

/** POST /api/sessions/{id}/fork response. */
@Serializable
private data class ForkResp(val id: String)
