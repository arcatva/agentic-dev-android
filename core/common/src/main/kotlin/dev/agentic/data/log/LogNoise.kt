package dev.agentic.data.log

/** Line filter for [LogcatCollector]: drops known framework spam (e.g. Samsung `setRequestedFrameRate` per Compose draw) before it hits the capture file so the rotation budget holds hours instead of seconds. Keep patterns NARROW (message substrings, not whole tags) — blanket tag drops would discard genuine View warnings/errors a crash run-up might need. */
internal object LogNoise {

    private val PATTERNS = listOf(
        // Samsung Android 15/16: android.view.View logs every setRequestedFrameRate call.
        "setRequestedFrameRate",
    )

    fun isNoise(line: String): Boolean = PATTERNS.any { line.contains(it) }
}
