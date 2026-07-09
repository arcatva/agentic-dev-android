package dev.agentic.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read input field [k] as a primitive string, tolerating a non-primitive value (object/array).
 *  `?.jsonPrimitive` THROWS IllegalArgumentException when the element is a JsonObject/JsonArray, and
 *  some tools carry a structured (object) input field (e.g. a Write whose payload is an object) — that
 *  throw used to escape buildFromLog → seedFromLog → load() uncaught on the app scope and force-close
 *  the whole app on opening such a session. `as? JsonPrimitive` returns null instead, so a structured
 *  field just renders blank rather than crashing. */
private fun JsonObject.prim(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Short label for a tool chip — the most relevant input field per tool. */
fun toolSummary(name: String, input: JsonObject?): String {
    fun f(k: String) = input?.prim(k)
    return when (name) {
        "Read", "Edit", "Write", "NotebookEdit", "MultiEdit" -> f("file_path")?.substringAfterLast('/') ?: ""
        "Bash" -> (f("command") ?: "").lineSequence().firstOrNull()?.trim()?.take(64) ?: ""
        "Glob", "Grep" -> f("pattern") ?: ""
        "ToolSearch" -> f("query") ?: ""
        "TaskCreate" -> f("subject") ?: ""
        "TaskUpdate" -> f("status") ?: f("taskId") ?: ""
        "WebFetch", "WebSearch" -> f("url") ?: f("query") ?: ""
        else -> ""
    }
}

/** Expanded detail for a tool chip. */
fun toolDetail(name: String, input: JsonObject?): String {
    if (input == null) return ""
    fun f(k: String) = input.prim(k) ?: ""
    return when (name) {
        "Bash" -> f("command")
        "Edit", "MultiEdit" -> "- " + f("old_string") + "\n\n+ " + f("new_string")
        "Write" -> f("contents").ifEmpty { f("content") }.take(4000)
        else -> input.entries.joinToString("\n") { (k, v) -> "$k: ${v.toString().take(800)}" }
    }
}
