package dev.agentic.domain

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDisplayTest {
    private fun obj(vararg p: Pair<String, String>) =
        JsonObject(p.associate { it.first to JsonPrimitive(it.second) })

    @Test fun read_summary_is_basename() =
        assertEquals("File.kt", toolSummary("Read", obj("file_path" to "/a/b/File.kt")))

    @Test fun bash_summary_first_line_trimmed() =
        assertEquals("echo hi", toolSummary("Bash", obj("command" to "  echo hi\nmore")))

    @Test fun bash_detail_full_command() =
        assertEquals("echo hi\nmore", toolDetail("Bash", obj("command" to "echo hi\nmore")))
}
