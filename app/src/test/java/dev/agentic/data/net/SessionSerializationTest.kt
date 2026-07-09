package dev.agentic.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `decodes endedAt and workflowRunning when present`() {
        val s = json.decodeFromString<Session>(
            """{"id":"s1","status":"done","endedAt":1700,"workflowRunning":true}"""
        )
        assertEquals(1700L, s.endedAt)
        assertTrue(s.workflowRunning)
    }

    @Test fun `defaults endedAt=null and workflowRunning=false when absent`() {
        val s = json.decodeFromString<Session>("""{"id":"s1","status":"done"}""")
        assertNull(s.endedAt)
        assertFalse(s.workflowRunning)
    }

    @Test fun `decodes windowed SessionDetail with start and total`() {
        val d = json.decodeFromString<SessionDetail>(
            """{"session":{"id":"s1","status":"running"},"log":["a","b"],"start":18,"total":20}"""
        )
        assertEquals(2, d.log.size)
        assertEquals(18, d.start)
        assertEquals(20, d.total)
    }

    @Test fun `defaults start=0 and total=null on a legacy SessionDetail`() {
        val d = json.decodeFromString<SessionDetail>(
            """{"session":{"id":"s1","status":"done"},"log":["x"]}"""
        )
        assertEquals(0, d.start)
        assertNull(d.total)
    }

    // ── Commit-graph models ──────────────────────────────────────────────────────

    @Test fun `decodes CommitsResp with repos, commits, and uncommitted node`() {
        val resp = json.decodeFromString<CommitsResp>(
            """
            {"repos":[
              {"repo":"demo",
               "commits":[
                 {"sha":"abc123def456","shortSha":"abc123d","parents":["0000aaa"],
                  "subject":"add feature","author":"Ada","at":1700000000000,"isSession":true},
                 {"sha":"0000aaa","shortSha":"0000aaa","parents":[],
                  "subject":"base","author":"Bob","at":1699999999000,"isSession":false}
               ],
               "uncommitted":{"added":1,"modified":2,"deleted":3}}
            ]}
            """.trimIndent()
        )
        assertEquals(1, resp.repos.size)
        val repo = resp.repos[0]
        assertEquals("demo", repo.repo)
        assertEquals(2, repo.commits.size)
        val head = repo.commits[0]
        assertEquals("abc123def456", head.sha)
        assertEquals("abc123d", head.shortSha)
        assertEquals(listOf("0000aaa"), head.parents)
        assertEquals("add feature", head.subject)
        assertEquals("Ada", head.author)
        assertEquals(1700000000000L, head.at)
        assertTrue(head.isSession)
        assertFalse(repo.commits[1].isSession)
        assertEquals(Uncommitted(added = 1, modified = 2, deleted = 3), repo.uncommitted)
    }

    @Test fun `decodes RepoCommits with null uncommitted and applies CommitNode defaults`() {
        val repo = json.decodeFromString<RepoCommits>(
            """{"repo":"demo","commits":[{"sha":"deadbeef","shortSha":"deadbee"}],"uncommitted":null}"""
        )
        assertEquals("demo", repo.repo)
        assertNull(repo.uncommitted)
        val c = repo.commits[0]
        assertTrue("parents defaults empty", c.parents.isEmpty())
        assertEquals("", c.subject)
        assertEquals("", c.author)
        assertEquals(0L, c.at)
        assertFalse(c.isSession)
    }

    @Test fun `decodes CommitFilesResp with status and counts`() {
        val resp = json.decodeFromString<CommitFilesResp>(
            """{"files":[
                 {"path":"a.kt","status":"added","additions":10,"deletions":0},
                 {"path":"b.kt","status":"deleted","additions":0,"deletions":5},
                 {"path":"c.kt"}
               ]}"""
        )
        assertEquals(3, resp.files.size)
        assertEquals(CommitFile("a.kt", "added", 10, 0), resp.files[0])
        assertEquals("deleted", resp.files[1].status)
        // defaults: status="modified", additions=0, deletions=0
        assertEquals(CommitFile("c.kt", "modified", 0, 0), resp.files[2])
    }

    // ── Content-search models ──────────────────────────────────────────────────

    @Test fun searchResponse_decodes() {
        val payload = """
            {"query":"build failed","results":[{"session":{"id":"s1","prompt":"p","status":"done","repos":[],"createdAt":1,"endedAt":null},"score":1.0,"matches":[{"field":"Notes","snippet":"...build failed...","lineIndex":4}]}]}
        """.trimIndent()
        val r: SearchResponse = json.decodeFromString(payload)
        assertEquals("build failed", r.query)
        assertEquals(1, r.results.size)
        assertEquals(SearchField.Notes, r.results[0].matches[0].field)
    }

    @Test fun searchResponse_tolerates_unknown_field() {
        val r = json.decodeFromString<SearchResponse>(
            """{"query":"q","results":[],"futureFlag":true}"""
        )
        assertEquals("q", r.query)
        assertTrue(r.results.isEmpty())
    }
}
