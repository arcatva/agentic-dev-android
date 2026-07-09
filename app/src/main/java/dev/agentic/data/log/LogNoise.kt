package dev.agentic.data.log

/**
 * Line filter for [LogcatCollector]: drops known framework spam BEFORE it is written to the
 * capture file, so the rotation budget holds hours of app history instead of seconds.
 *
 * Motivating case: Samsung Android 16 (One UI, e.g. SM-F966B) logs `View: setRequestedFrameRate`
 * at INFO on every Compose draw pass. In a real diagnostic export this was 8288 of 8957 lines
 * (92.5%), shrinking the 4 MB budget to ~38 seconds — the Nav/Auth lines needed to diagnose a
 * navigation bug were long gone.
 *
 * Keep the patterns NARROW (message substrings, not whole tags): a blanket "drop tag View" would
 * also discard genuine View warnings/errors that a crash run-up might need.
 */
internal object LogNoise {

    /** A line containing any of these substrings is dropped. */
    private val PATTERNS = listOf(
        // Samsung Android 15/16: android.view.View logs every setRequestedFrameRate call.
        "setRequestedFrameRate",
    )

    fun isNoise(line: String): Boolean = PATTERNS.any { line.contains(it) }
}
