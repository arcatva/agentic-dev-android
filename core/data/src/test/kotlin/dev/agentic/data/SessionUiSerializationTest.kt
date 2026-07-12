package dev.agentic.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression for the release crash "Serializer for class 'SessionUi' is not found": the module was
 *  missing the kotlin-serialization plugin, so @Serializable SessionUi generated no serializer and the
 *  reflective encode/decode threw at runtime. This round-trip fails without the plugin, passes with it. */
class SessionUiSerializationTest {
    @Test fun sessionUi_round_trips_through_json() {
        val json = Json { ignoreUnknownKeys = true }
        val ui = SessionUi(railHidden = true, railWidth = 320f, expandedRuns = setOf("a", "b"))
        assertEquals(ui, json.decodeFromString<SessionUi>(json.encodeToString(ui)))
    }
}
