package dev.agentic.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read [k] as a primitive string. `as? JsonPrimitive` avoids `?.jsonPrimitive` throwing on object/array inputs (e.g. Write with object payload), which would have force-closed the app. */
private fun JsonObject.prim(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Short tool chip label (most relevant input field per tool). */
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

/** Expanded tool chip detail. */
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
