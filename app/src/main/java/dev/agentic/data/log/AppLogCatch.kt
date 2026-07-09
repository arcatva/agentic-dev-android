package dev.agentic.data.log

/**
 * Log the caught exception at [level] then return [fallback].
 *
 * Call sites of silent catches that currently swallow everything should use this so the exception
 * is recorded in the log, making failures visible in logcat captures.
 *
 * Default level is 'w' (warning) — use 'd' only for truly expected failures (e.g. "model catalog
 * not available = show empty list"), and 'e' for fatal-but-caught errors.
 *
 * Example:
 * ```kotlin
 * val data = logCatch("Store", "decode ReadState failed", fallback = emptyMap()) {
 *     json.decodeFromString<Map<String, Long>>(raw)
 * }
 * ```
 */
inline fun <T> logCatch(
    tag: String,
    msg: String,
    level: Char = 'w',
    fallback: T,
    block: () -> T,
): T = try {
    block()
} catch (e: Exception) {
    when (level) {
        'e' -> AppLog.e(tag, "$msg: ${e.message}", e)
        'w' -> AppLog.w(tag, "$msg: ${e.message}", e)
        'd' -> AppLog.d(tag, "$msg: ${e.message}")
        else -> AppLog.w(tag, "$msg: ${e.message}", e)
    }
    fallback
}
