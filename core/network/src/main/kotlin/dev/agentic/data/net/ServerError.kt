package dev.agentic.data.net

private val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** The server's {"error": …} payload extracted from a Ktor exception message (which otherwise
 *  dumps the whole transport line incl. URL and status), unescaped. Falls back to the raw message. */
fun Throwable.serverError(): String {
    val m = message ?: return toString()
    val body = ERROR_FIELD.find(m)?.groupValues?.get(1)
    return body
        ?.replace("\\n", "\n")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
        ?.trim()
        ?: m
}
