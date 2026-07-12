package dev.agentic.domain

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AskParsingTest {
    @Test fun parses_current_schema() {
        val arr = buildJsonArray {
            addJsonObject {
                put("question", "Pick")
                putJsonArray("options") {
                    addJsonObject { put("label", "A") }
                    addJsonObject { put("label", "B") }
                }
            }
        }
        val q = parseAskQuestions(arr).single()
        assertEquals("Pick", q.text)
        assertEquals(listOf("A", "B"), q.options)
    }

    @Test fun parses_legacy_schema() {
        val arr = buildJsonArray {
            addJsonObject {
                put("text", "Q")
                putJsonArray("options") { add("x") }
            }
        }
        assertEquals(listOf("x"), parseAskQuestions(arr).single().options)
    }

    @Test fun empty_is_empty() = assertTrue(parseAskQuestions(null).isEmpty())

    @Test fun captures_multiSelect() {
        val arr = buildJsonArray {
            addJsonObject {
                put("question", "Pick many")
                put("multiSelect", true)
                putJsonArray("options") { add("a"); add("b") }
            }
            addJsonObject {
                put("question", "Pick one")
                putJsonArray("options") { add("x") }
            }
        }
        val qs = parseAskQuestions(arr)
        assertTrue("multiSelect captured", qs[0].multiSelect)
        assertFalse("defaults to single-select", qs[1].multiSelect)
    }

    @Test fun drops_questions_with_no_text_and_no_options() {
        val arr = buildJsonArray {
            addJsonObject { put("question", ""); putJsonArray("options") {} }   // empty → dropped
            addJsonObject { put("question", "Real?"); putJsonArray("options") { add("y") } }
        }
        val qs = parseAskQuestions(arr)
        assertEquals(1, qs.size)
        assertEquals("Real?", qs.single().text)
    }
}
