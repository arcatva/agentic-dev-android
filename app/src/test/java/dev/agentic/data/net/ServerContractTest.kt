package dev.agentic.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client half of the cross-repo API contract guard (server half: agentic-dev
 * `server-rs/tests/contract.rs`, which locks the Session wire KEY SET).
 *
 * The fixture `contract/session-sample.json` is a REAL, fully-populated Session serialized by the
 * backend store (generated from server-rs: create + patch a session, `serde_json::to_string_pretty`)
 * — not a handcrafted approximation. Decoding it with the same lenient [Json] the runtime client
 * uses proves every field the client MODELS is TYPE-compatible with what the server actually
 * emits (e.g. a server-side `startedAt: i64 -> string` change fails this test).
 *
 * Scope — what this does NOT cover: the client intentionally models a SUBSET of the wire keys
 * (ignoreUnknownKeys at runtime), so drift on UNMODELED keys (repo, costUsd, exitCode,
 * titlePinned, hiddenSkills/Plugins/McpServers, extraMcpServers, baseSha(s),
 * nativeWatermarkLines, forcedOn*) is invisible here — the server-side key-set fixture guards
 * their existence, nothing guards their types until the client models them. Runtime-only fields
 * (activity, awaitingInput, workflowRunning, pendingPrompt, autoResumeAt) never appear on a
 * store row, so the fixture cannot exercise them.
 *
 * Regenerate the fixture after an intentional contract change: see the header of
 * server-rs/tests/contract.rs. (encodeDefaults only affects encoding, so this decode-side config
 * matches the runtime client.)
 */
class ServerContractTest {
    // Runtime-equivalent configuration (KtorAgenticApi uses the same flags).
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("contract/session-sample.json")) {
            "missing test resource contract/session-sample.json"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `decodes a real fully-populated server Session`() {
        val s = json.decodeFromString<Session>(fixture())

        // Identity / core state
        assertEquals("sess-sample-1", s.id)
        assertEquals("Fix the login bug", s.prompt)
        assertEquals("done", s.status)

        // Typed scalar fields — these assertions are what catches a server-side type change.
        assertEquals("usage limit reached", s.error)
        assertEquals("usage_limit", s.errorKind)
        assertEquals(1783700000000L, s.startedAt)
        assertEquals(1783700060000L, s.endedAt)
        assertTrue(s.createdAt > 0)
        assertTrue(s.lastUserMessageAt > 0)

        // Collections + optionals
        assertEquals(listOf("demo", "lib"), s.repos)
        assertEquals(listOf("tenants-dev"), s.skills)
        assertEquals("opus", s.model)
        assertEquals("high", s.effort)
        assertEquals("bypassPermissions", s.permissionMode)
        assertEquals("11111111-2222-3333-4444-555555555555", s.claudeSessionId)
        assertEquals("/tmp/wt/sess-sample-1", s.worktreePath)
        assertEquals("agentic/sess-sample-1", s.branch)
        assertEquals("live", s.worktreeState)

        // Fork/group metadata + runtime flags
        assertEquals("parent-42", s.parentSessionId)
        assertEquals("grp-1", s.groupId)
        assertEquals("fork", s.origin)
        assertEquals(0L, s.unreadEventId)
        assertEquals(0L, s.ackedEventId)
        assertTrue(s.autoResume)
        assertFalse(s.detached)

        // The server normalizes some stored values on read (mode "workflows" reads back null in
        // this sample) — the client must tolerate that.
        assertEquals(null, s.mode)
    }

    @Test
    fun `session sample also decodes inside a SessionList envelope`() {
        val list = json.decodeFromString<SessionList>("""{"sessions":[${fixture()}]}""")
        assertEquals(1, list.sessions.size)
        assertEquals("sess-sample-1", list.sessions[0].id)
    }
}
