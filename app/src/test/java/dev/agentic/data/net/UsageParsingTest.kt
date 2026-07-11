package dev.agentic.data.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// The real `GET /api/usage` body is Anthropic's usage JSON passed through verbatim by the backend:
class UsageParsingTest {
    // Mirrors KtorAgenticApi's client config.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Trimmed but faithful sample of a real /api/usage response (float utilization, extra fields,
    // null sibling windows).
    private val realBody = """
        {
          "five_hour": {"utilization": 37.0, "resets_at": "2026-06-21T15:50:00+00:00", "limit_dollars": null, "used_dollars": null},
          "seven_day": {"utilization": 57.0, "resets_at": "2026-06-23T23:00:00+00:00"},
          "seven_day_sonnet": {"utilization": 9.0},
          "seven_day_opus": null,
          "limits": [{"kind": "session", "percent": 34}],
          "spend": {"percent": 0, "enabled": false}
        }
    """.trimIndent()

    @Test fun `parses real usage body with float utilization and extra fields`() {
        val usage = json.decodeFromString<Usage>(realBody)
        assertNotNull("five_hour window must parse", usage.five_hour)
        assertEquals("five_hour utilization", 37, usage.five_hour?.utilization?.toInt())
        assertEquals("seven_day utilization", 57, usage.seven_day?.utilization?.toInt())
    }
}
