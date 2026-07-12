package dev.agentic.data.log

/** Run [block], log the caught exception at [level] ('w' default, 'e' or 'd'), and return [fallback]. For silent catches that should still record into the captured log. */
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
