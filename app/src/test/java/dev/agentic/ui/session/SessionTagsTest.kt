package dev.agentic.ui.session

import dev.agentic.data.net.ModelEntry
import dev.agentic.data.net.Session
import dev.agentic.ui.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SessionTagsTest {

    @Before fun seedCatalog() {
        ModelCatalog.init(listOf(
            ModelEntry(key = "claude-haiku-4-5-20251001", label = "Haiku 4.5", native = true, default = false, capability = 0.60f),
            ModelEntry(key = "claude-sonnet-4-6", label = "Sonnet 4.6", native = true, default = false, capability = 0.85f),
            ModelEntry(key = "claude-opus-4-8", label = "Opus 4.8", native = true, default = true, capability = 0.97f),
        ))
    }

    @Test fun `repos come first, then skills, in order`() {
        val tags = sessionTags(Session(id = "s", repos = listOf("webapp", "infra-tools"), skills = listOf("deep-research")))
        assertEquals(
            listOf(
                SessionTag("webapp", TagKind.REPO),
                SessionTag("infra-tools", TagKind.REPO),
                SessionTag("deep-research", TagKind.SKILL),
            ),
            tags,
        )
    }

    @Test fun `empty repos (skill-only) yields only skill tags`() {
        val tags = sessionTags(Session(id = "s", repos = emptyList(), skills = listOf("brainstorming")))
        assertEquals(listOf(SessionTag("brainstorming", TagKind.SKILL)), tags)
    }

    @Test fun `empty skills (load-all) yields only repo tags`() {
        val tags = sessionTags(Session(id = "s", repos = listOf("agentic-dev"), skills = emptyList()))
        assertEquals(listOf(SessionTag("agentic-dev", TagKind.REPO)), tags)
    }

    @Test fun `no repos and no skills yields no tags`() {
        assertEquals(emptyList<SessionTag>(), sessionTags(Session(id = "s")))
    }

    @Test fun `blank and duplicate entries are dropped`() {
        val tags = sessionTags(Session(id = "s", repos = listOf("a", "a", " "), skills = listOf("x", "", "x")))
        assertEquals(
            listOf(SessionTag("a", TagKind.REPO), SessionTag("x", TagKind.SKILL)),
            tags,
        )
    }

    @Test fun `model and effort come after repos and skills`() {
        val tags = sessionTags(
            Session(id = "s", repos = listOf("agentic-dev"), skills = listOf("deep-research"),
                model = "claude-opus-4-8", effort = "high"),
        )
        assertEquals(
            listOf(
                SessionTag("agentic-dev", TagKind.REPO),
                SessionTag("deep-research", TagKind.SKILL),
                SessionTag("Opus 4.8", TagKind.MODEL),
                SessionTag("High", TagKind.EFFORT),
            ),
            tags,
        )
    }

    @Test fun `a known model uses its New-request friendly label`() {
        assertEquals(
            listOf(SessionTag("Sonnet 4.6", TagKind.MODEL)),
            sessionTags(Session(id = "s", model = "claude-sonnet-4-6")),
        )
    }

    @Test fun `an unknown model falls back to the raw id with claude- stripped`() {
        assertEquals(
            listOf(SessionTag("gpt-5", TagKind.MODEL)),
            sessionTags(Session(id = "s", model = "gpt-5")),
        )
    }

    @Test fun `every effort level uses its New-request friendly label`() {
        fun effortTag(raw: String) = sessionTags(Session(id = "s", effort = raw)).single()
        assertEquals(SessionTag("Low", TagKind.EFFORT), effortTag("low"))
        assertEquals(SessionTag("Medium", TagKind.EFFORT), effortTag("medium"))
        assertEquals(SessionTag("High", TagKind.EFFORT), effortTag("high"))
        assertEquals(SessionTag("Xhigh", TagKind.EFFORT), effortTag("xhigh"))
        assertEquals(SessionTag("Max", TagKind.EFFORT), effortTag("max"))
    }

    @Test fun `an unknown effort falls back to the raw value`() {
        assertEquals(
            listOf(SessionTag("turbo", TagKind.EFFORT)),
            sessionTags(Session(id = "s", effort = "turbo")),
        )
    }

    @Test fun `null or blank model and effort add no tags`() {
        assertEquals(emptyList<SessionTag>(), sessionTags(Session(id = "s", model = null, effort = "  ")))
    }

    @Test fun `ultracode mode adds a leading ultracode tag and suppresses the redundant effort chip`() {
        // ultracode locks effort to xhigh, so the effort chip would just echo the ultracode pill.
        val tags = sessionTags(
            Session(id = "s", mode = "ultracode", repos = listOf("agentic-dev"),
                model = "claude-opus-4-8", effort = "xhigh"),
        )
        assertEquals(
            listOf(
                SessionTag("ultracode", TagKind.ULTRA),
                SessionTag("agentic-dev", TagKind.REPO),
                SessionTag("Opus 4.8", TagKind.MODEL),
            ),
            tags,
        )
    }

    @Test fun `non-ultra mode adds no ultracode tag`() {
        assertEquals(
            listOf(SessionTag("agentic-dev", TagKind.REPO)),
            sessionTags(Session(id = "s", mode = null, repos = listOf("agentic-dev"))),
        )
    }
}
