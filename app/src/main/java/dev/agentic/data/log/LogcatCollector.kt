package dev.agentic.data.log

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Streams this process's logcat into [LogStore.logFile], rotating the file by size. Captures
 * everything our process emits to logcat — our own [AppLog] lines, framework + library logs under
 * our pid, and the full stack trace ART prints on an uncaught exception — so a shared log shows the
 * whole run-up to a crash, not just instrumented lines.
 *
 * Reading our OWN pid's logcat needs no permission: the log driver only returns the caller's own
 * entries to an unprivileged app, so this works on stock release builds (no READ_LOGS required).
 * On locked-down OEM ROMs that deny exec'ing logcat, the read loop fails and degrades quietly —
 * crash reports are still written directly by [CrashHandler], independent of this collector.
 *
 * Lifecycle: at most one worker thread + one `logcat` subprocess exists at a time. [stop] quiesces
 * the worker (destroys the subprocess and joins the thread) BEFORE returning, so a fast off→on
 * toggle can never leave two writers open on the same file. Publishing the subprocess handle is
 * done under the instance lock with a `running` re-check to close the start/stop ordering race.
 */
class LogcatCollector(private val store: LogStore) {

    @Volatile private var running = false
    private var process: java.lang.Process? = null   // guarded by `this`
    private var thread: Thread? = null               // guarded by `this`

    @Synchronized
    fun start() {
        // Never spawn while a previous worker is still alive (stop() joins, so this is belt-and-braces).
        if (running || thread?.isAlive == true) return
        running = true
        thread = Thread({ runLoop() }, "logcat-collector").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        // Update shared state under the lock, then join OUTSIDE the lock. Joining inside would
        // deadlock the worker, which needs the same lock to publish its subprocess handle.
        var worker: Thread? = null
        synchronized(this) {
            running = false
            runCatching { process?.destroy() }
            process = null
            worker = thread
            thread = null
        }
        // process.destroy() forces the worker's readLine() to EOF, so it normally exits within
        // milliseconds; the bounded join just caps the worst case (e.g. a wedged logcat subprocess).
        runCatching { worker?.join(1_000) }
    }

    private fun runLoop() {
        var proc: java.lang.Process? = null
        try {
            val pid = Process.myPid()
            // -v threadtime → "MM-DD HH:MM:SS.mmm  pid  tid  LEVEL  TAG: message".
            // -T 1 → start from the most recent line instead of re-dumping the entire ring buffer on
            //        every (re)start, which would duplicate history and churn rotations.
            // --pid → only our process (both flags supported since API 24; minSdk is 26).
            proc = ProcessBuilder("logcat", "-v", "threadtime", "-T", "1", "--pid=$pid")
                .redirectErrorStream(true)
                .start()

            // Publish the handle under the lock, re-checking `running` so a stop() that raced our
            // ProcessBuilder.start() reliably tears this subprocess down instead of orphaning it.
            synchronized(this) {
                if (!running) {
                    runCatching { proc.destroy() }
                    return
                }
                process = proc
            }

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                // Explicit UTF-8 so the captured text is encoded correctly regardless of the device
                // default charset. The rotation counter uses char length (line.length) as a cheap,
                // allocation-free approximation of byte size — good enough for a soft size cap.
                var writer = utf8Writer(append = true)
                var written = store.logFile.length()
                var sinceFlush = 0
                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        writer.write(line)
                        writer.write("\n")
                        written += line.length + 1L
                        if (++sinceFlush >= FLUSH_EVERY_LINES) {
                            writer.flush()
                            sinceFlush = 0
                        }
                        if (written >= MAX_BYTES) {
                            writer.flush()
                            writer.close()
                            rotate()
                            writer = utf8Writer(append = false)
                            written = 0L
                        }
                    }
                } finally {
                    runCatching { writer.flush(); writer.close() }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "logcat capture unavailable: ${t.message}")
        } finally {
            // Always tear down on ANY exit path (stop(), EOF, or exception): clear `running` so a
            // later start() can restart the collector, and destroy the subprocess so an EOF/exception
            // exit never leaks a logcat child. Idempotent if stop() already destroyed it.
            synchronized(this) {
                if (process === proc) process = null
                running = false
            }
            runCatching { proc?.destroy() }
        }
    }

    private fun utf8Writer(append: Boolean): BufferedWriter =
        BufferedWriter(OutputStreamWriter(FileOutputStream(store.logFile, append), Charsets.UTF_8))

    /** logcat.log → logcat.log.1 → … shifting older rotations up and dropping the oldest. */
    private fun rotate() {
        runCatching {
            val base = store.logFile
            val dir = base.parentFile ?: return
            val oldest = File(dir, "${base.name}.$ROTATIONS")
            if (oldest.exists()) oldest.delete()
            for (i in ROTATIONS - 1 downTo 1) {
                val src = File(dir, "${base.name}.$i")
                if (src.exists()) src.renameTo(File(dir, "${base.name}.${i + 1}"))
            }
            base.renameTo(File(dir, "${base.name}.1"))
        }
    }

    companion object {
        private const val TAG = "LogcatCollector"
        private const val MAX_BYTES = 1L * 1024 * 1024   // 1 MB active file
        private const val ROTATIONS = 3                  // logcat.log.1 .. logcat.log.3
        private const val FLUSH_EVERY_LINES = 20
    }
}
