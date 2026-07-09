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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/** Ktor-based implementation of [AgenticApi]. Behaviour is identical to the original
 *  [dev.agentic.net.Api]; the class is renamed and placed in the data layer so repositories
 *  depend on the interface, not the concrete transport. */
class KtorAgenticApi(
    override var baseUrl: String,
    override var token: String? = null,
    // Looks up the pinned server-cert fingerprint for a host key (see [certHostKey]); default never
    // pins (used by tests / callers that don't need TLS trust-on-first-use).
    private val pinnedFingerprintFor: (hostKey: String) -> String? = { null },
) : AgenticApi {

    // Set by the app: invoked on any 401 so it can drop the stale token and route to the login screen.
    override var onUnauthorized: (() -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Trust-on-first-use TLS, keyed off the CURRENT baseUrl (read live, since baseUrl is mutable).
    // Shared by both OkHttp clients below so REST and WebSocket connections trust the same cert.
    private val tofuTls = TofuTls(
        currentHostKey = { certHostKey(baseUrl) },
        pinnedFingerprintFor = pinnedFingerprintFor,
    )

    // Shared OkHttp behind the REST client, kept so [evictConnections] can drop its pooled sockets on
    // app-foreground (a connection that went half-open while backgrounded must not be reused).
    private val rpcOkHttp = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .sslSocketFactory(tofuTls.socketFactory, tofuTls.trustManager)
        .hostnameVerifier(tofuTls.hostnameVerifier)
        .build()

    // REST client. Now that the long-lived WS lives on [streamClient] below, we can give every REST
    // call REAL socket/request timeouts. This is the fix for "frozen until app restart": a poll or
    // refetch on a half-open pooled connection used to hang forever (only connectTimeout was set), so
    // every self-heal path that begins with an HTTP GET wedged. socketTimeoutMillis bounds the gap
    // between bytes (breaks a half-open read); requestTimeoutMillis is a generous overall cap that
    // large fetches (session log, file download) override per-request.
    private val client = HttpClient(OkHttp) {
        engine { preconfigured = rpcOkHttp }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            socketTimeoutMillis = 20_000
            requestTimeoutMillis = 60_000
        }
        // Prevents deserialize-crash on error bodies: with expectSuccess=true Ktor throws
        // ResponseException for any non-2xx, which runCatchingOutcome maps to AppError.Http.
        // The 401 validateResponse below still fires first (headers phase), so UnauthorizedException
        // is raised before ResponseException for 401 — the priority order is intentional.
        expectSuccess = true
        // A 401 otherwise parses as empty data (e.g. SessionList{sessions=[]}) and the user just sees a
        // blank screen with no hint to re-auth. Surface it: fire onUnauthorized + throw so callers fail.
        HttpResponseValidator {
            validateResponse { resp ->
                if (resp.status == HttpStatusCode.Unauthorized) {
                    // A 401 on /api/login means "wrong password", NOT an expired session — don't fire the
                    // app-wide logout (it would bounce the user out of the login screen mid-attempt).
                    // expectSuccess=true still raises ResponseException → AppError.Http(401), which the
                    // login screen maps to a "wrong password" message.
                    if (!resp.call.request.url.encodedPath.endsWith("/api/login")) {
                        onUnauthorized?.invoke(); throw UnauthorizedException()
                    }
                }
            }
        }
    }

    // Dedicated client for the long-lived session WS ONLY. A 20s ping keeps NAT mappings open; we
    // deliberately set NO socket/request timeout here so a legitimately idle (awaiting-input) stream
    // is never torn down by a timer. Liveness is enforced at the app layer in [stream] instead: the
    // server sends a HEARTBEAT every ~10s, so a gap beyond ~2.5x that means the socket is dead.
    // Dedicated OkHttp for the WS stream, with the same TOFU TLS trust as the REST client.
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

    /** GET /api/sessions/:id returns `{ session, log }`; we only need the row here (settings screen), so
     *  call with limit=0 (empty log) and unwrap [SessionDetail.session] to avoid deserializing a 200MB log. */
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

    /** Upload a file into the session worktree's uploads/; returns the path the agent can read. */
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

    /** Stage a file before a session exists (New-request attachments). POST /api/uploads with the
     *  same multipart shape as [uploadFile] but no session id; returns the token + sanitized name +
     *  uploads/<name> path the client embeds in the create prompt's [attached: ...] marker. */
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

    /** Raw bytes of a file in the session worktree (for image preview / download).
     *  onProgress, when given, reports download fraction 0f..1f (null when length is unknown). */
    override suspend fun fileBytes(id: String, path: String, onProgress: ((Float?) -> Unit)?): ByteArray {
        return try {
            val r: ByteArray = client.get("$baseUrl/api/sessions/$id/file") {
                auth(); parameter("path", path)
                // Request-scoped timeouts (do NOT touch the client-level config that deliberately leaves the
                // long-lived WS stream without a request timeout). socketTimeoutMillis is an IDLE cap (max
                // gap between bytes): a large APK over a slow link keeps downloading as long as bytes keep
                // arriving, while a truly stalled body still fails. requestTimeoutMillis stays only as a
                // generous absolute upper bound (the old 120s whole-transfer cap killed big-but-progressing
                // downloads).
                timeout { socketTimeoutMillis = 60_000; requestTimeoutMillis = 600_000 }
                if (onProgress != null) onDownload { sent, total ->
                    onProgress(if (total != null && total > 0L) (sent.toFloat() / total).coerceIn(0f, 1f) else null)
                }
            }.readBytes()
            AppLog.d("API", "GET sessions/${id.take(8)}/file -> OK")
            r
        } catch (e: Exception) {
            AppLog.w("API", "GET sessions/${id.take(8)}/file -> FAILED: ${e.message}")
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

    /** Open the session stream; calls [onLine] for each event frame (a JSON ClaudeEvent). Returns when closed.
     *
     *  Token is appended to the query only when non-null (sending "token=null" would be rejected).
     *  If the WS handshake is rejected with 401/Unauthorized the [onUnauthorized] callback is fired
     *  (matching the same logout behaviour as HTTP 401) before rethrowing. */
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
                // Liveness watchdog (Discord-gateway style): the server HEARTBEATs every ~N ms even
                // when idle, so any gap longer than ~2.5x N means the socket is dead (half-open) even
                // if TCP/OkHttp's ping hasn't noticed. Returning ends the WS so the caller's reconnect
                // loop RESUMEs from the last seq. Default cap (before HELLO / against an old server) 30s.
                var idleCapMs = 30_000L
                // Parse the cap from HELLO exactly once. A flag (not `idleCapMs == default`) because a
                // 10s heartbeat yields a 30s cap == the default, which would otherwise re-parse every
                // frame's JSON on the network thread. A cheap substring pre-check avoids parsing
                // non-HELLO frames; the first frame against an old server (no HELLO) just marks it done.
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
                        // Surface stalls: a gap longer than ~1.5 heartbeats means frames stopped flowing
                        // even though the socket is open — the signature of the live-lag bug.
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
            // OkHttp throws ProtocolException (extends IOException) with a message like
            // "Expected HTTP 101 response but was '401 Unauthorized'" when the WS upgrade is
            // rejected. We surface this as the same onUnauthorized signal used by HTTP 401.
            val msg = e.message ?: ""
            if (msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true)) {
                onUnauthorized?.invoke()
            }
            throw e
        }
    }

    // ── Feature: Push notifications ───────────────────────────────────────────
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

    // ── Feature: Templates ────────────────────────────────────────────────────
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

    // ── Model catalog ──────────────────────────────────────────────────────────
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

    // ── Feature: Session groups (folders) ─────────────────────────────────────
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

    // ── Feature: Provider registry (BYOK cheap models) ────────────────────────
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

    // ── Feature: Global Settings (S5a) ────────────────────────────────────────
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

    // ── Feature: Commit-graph view ─────────────────────────────────────────────
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

    /** Changed files for a commit (or "working"). The sha is a path segment; repo goes in the query.
     *  [parameter] URL-encodes the repo value, and [encodeURLPathPart] guards the sha segment. */
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

    /** Line-level diff for one file. sha is a path segment; repo + path go in the query (both
     *  URL-encoded by [parameter], so a path with slashes is safe). */
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

    /** Adopt-picker discovery (GET /api/adoptable). The server returns a flat JSON array, so we
     *  decode straight into `List<Adoptable>` (the global `json` instance already handles missing
     *  fields leniently via `ignoreUnknownKeys`). Any HTTP failure propagates; callers (picker +
     *  ViewModel) decide whether to surface it as a banner. */
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

    /** Adopt a Claude Code session (POST /api/sessions/adopt). Returns the new server session id
     *  the picker navigates to on success. */
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

    /** Hand a session off to a local Claude Code CLI (POST /api/sessions/:id/detach). Throws on
     *  non-2xx — the calling ViewModel surfaces the server's body as a user-facing error. After
     *  success the server flips `Session.detached = true` on subsequent reads, which the detail
     *  screen uses to keep the "handed off" banner up without an additional round-trip. */
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

    /** If [text] is the gateway HELLO frame, derive the watchdog idle cap from its heartbeat cadence
     *  (~2.5x + slack). null when it isn't a HELLO (so the caller keeps the current cap). */
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
