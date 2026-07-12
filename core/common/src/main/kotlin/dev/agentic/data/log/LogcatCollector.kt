package dev.agentic.data.log

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Streams this process's logcat into [LogStore.logFile] with size rotation. At most one worker thread + one logcat subprocess at a time; [stop] joins before returning so a fast off→on toggle can't leave two writers open. Reading our own pid needs no permission; on locked-down ROMs that deny exec'ing logcat the read loop degrades quietly — crash reports still write via [CrashHandler]. */
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
        // Update shared state under the lock, then join OUTSIDE the lock — joining inside would deadlock the worker, which needs the same lock to publish its subprocess handle.
        var worker: Thread? = null
        synchronized(this) {
            running = false
            runCatching { process?.destroy() }
            process = null
            worker = thread
            thread = null
        }
        // Bounded join caps a wedged logcat subprocess; destroy() normally forces readLine to EOF within milliseconds.
        runCatching { worker?.join(1_000) }
    }

    private fun runLoop() {
        var proc: java.lang.Process? = null
        try {
            val pid = Process.myPid()
            // -T 1 → start from the most recent line (avoid re-dumping the entire ring buffer on every (re)start); --pid → only our process.
            proc = ProcessBuilder("logcat", "-v", "threadtime", "-T", "1", "--pid=$pid")
                .redirectErrorStream(true)
                .start()

            // Publish handle under the lock, re-checking `running` so a stop() that raced our start() tears this subprocess down instead of orphaning it.
            synchronized(this) {
                if (!running) {
                    runCatching { proc.destroy() }
                    return
                }
                process = proc
            }

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                // Explicit UTF-8; char length is a cheap proxy for byte size (good enough for a soft cap).
                var writer = utf8Writer(append = true)
                var written = store.logFile.length()
                var sinceFlush = 0
                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        // Drop framework spam (see LogNoise) so it can't wash the rotation budget out.
                        if (LogNoise.isNoise(line)) continue
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
            // Always tear down on ANY exit path (stop(), EOF, or exception): clear running so a later start() can restart, destroy the subprocess so an EOF/exception exit never leaks a logcat child.
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
