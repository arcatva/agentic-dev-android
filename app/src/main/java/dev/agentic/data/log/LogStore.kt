package dev.agentic.data.log

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Owns the on-disk log directory and everything we do with it: where the rolling logcat capture
 * file lives, where crash reports are written, zipping the whole lot for export/share, reading the
 * tail for the in-app viewer, clearing, and tracking which crashes the user has already seen.
 *
 * Everything lives under app-private storage ([Context.getFilesDir]), so no runtime permission is
 * needed and the files survive a crash — [LogcatCollector] flushes the capture, and [writeCrash]
 * writes a standalone report synchronously before the process dies.
 *
 * Thread-safety: writes come from at most two places at once — the background logcat thread (which
 * only touches [logFile] + its rotations via [LogcatCollector]) and the main/VM thread (crash
 * report writes, export, clear). Those touch disjoint files, except [clear], which truncates
 * [logFile]. To avoid racing the collector's open writer, the caller ([dev.agentic.ui.diagnostics]
 * DiagnosticsViewModel.clear) stops the collector around [clear] and restarts it afterwards.
 */
class LogStore(private val context: Context) {

    /** filesDir/logs — holds the rolling logcat capture, its rotations, and a crashes/ subdir. */
    val logsDir: File = File(context.filesDir, "logs").apply { mkdirs() }

    /** filesDir/logs/crashes — one text file per uncaught exception. */
    val crashesDir: File = File(logsDir, "crashes").apply { mkdirs() }

    /** The active logcat capture file; [LogcatCollector] appends to it and rotates it by size. */
    val logFile: File = File(logsDir, "logcat.log")

    private val prefs = context.getSharedPreferences("agentic_logs", Context.MODE_PRIVATE)

    /**
     * Whether "Verbose logging" is enabled — drives BOTH the verbose seams ([AppLog.verbose]) and the
     * rolling logcat→file capture. Default OFF: normal runs are quiet and the captured file stays small;
     * the user turns it on from Diagnostics to reproduce a bug. Crash reports are ALWAYS written
     * regardless (the crash handler is independent), so a crash is never lost even with this off.
     */
    var captureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply() }

    // ── Crash reports ─────────────────────────────────────────────────────────

    /**
     * Writes a standalone crash report and prunes old ones. Best-effort and never throws — it runs
     * inside the uncaught-exception handler, where a second failure would be useless.
     */
    fun writeCrash(thread: Thread, error: Throwable) {
        runCatching {
            val file = File(crashesDir, "crash-${fileTs()}.txt")
            file.writeText(
                buildString {
                    appendLine("=== agentic-dev crash report ===")
                    appendLine("time:    ${humanTs()}")
                    appendLine(deviceHeader())
                    appendLine("thread:  ${thread.name}")
                    appendLine()
                    append(stackTraceString(error))
                }
            )
            pruneCrashes()
        }
    }

    /** All crash report files, newest first. */
    fun crashReports(): List<File> =
        crashesDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Crash files written since the user last acknowledged them (see [markCrashesSeen]). */
    fun unseenCrashes(): List<File> {
        val seenAt = prefs.getLong(KEY_CRASHES_SEEN_AT, 0L)
        return crashReports().filter { it.lastModified() > seenAt }
    }

    /**
     * Mark all current crashes as seen so the next-launch prompt doesn't re-fire for them. Anchors
     * the marker to the newest crash file's mtime (not the wall clock) so a backward clock jump
     * between the crash and the acknowledgment can't make an already-seen crash look unseen again.
     */
    fun markCrashesSeen() {
        val newest = crashReports().firstOrNull()?.lastModified() ?: System.currentTimeMillis()
        prefs.edit().putLong(KEY_CRASHES_SEEN_AT, newest).apply()
    }

    private fun pruneCrashes() {
        val files = crashesDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_CRASH_FILES).forEach { runCatching { it.delete() } }
    }

    // ── In-app viewer ─────────────────────────────────────────────────────────

    /**
     * Returns the last [maxBytes] bytes of the active capture file for the in-app viewer. We tail
     * rather than load the whole file so a multi-megabyte log never blows up Compose / memory.
     */
    fun tail(maxBytes: Long = 256L * 1024): String = runCatching {
        val file = logFile
        if (!file.exists()) return@runCatching ""
        val len = file.length()
        if (len <= maxBytes) {
            file.readText()
        } else {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - maxBytes)
                val buf = ByteArray(maxBytes.toInt())
                val read = raf.read(buf).coerceAtLeast(0)
                "…(earlier lines truncated)…\n" + String(buf, 0, read)
            }
        }
    }.getOrDefault("")

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Zips the whole logs dir (rolling capture + rotations + crashes) plus a device-info header
     * into cacheDir/log-exports and returns the zip. The caller shares it via FileProvider. Old
     * exports are deleted first so the share sheet never offers a stale file.
     */
    fun exportZip(): File {
        val exportsDir = File(context.cacheDir, EXPORTS_DIR).apply { mkdirs() }
        exportsDir.listFiles()?.forEach { runCatching { it.delete() } }
        val zip = File(exportsDir, "agentic-logs-${fileTs()}.zip")
        ZipOutputStream(FileOutputStream(zip).buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("device-info.txt"))
            zos.write((deviceHeader() + "\nexported: " + humanTs() + "\n").toByteArray())
            zos.closeEntry()
            addTree(zos, logsDir, logsDir.name)
        }
        return zip
    }

    private fun addTree(zos: ZipOutputStream, file: File, entryPath: String) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { addTree(zos, it, "$entryPath/${it.name}") }
        } else {
            runCatching {
                zos.putNextEntry(ZipEntry(entryPath))
                // closeEntry() in finally: if the copy throws (disk full, concurrent change), the
                // entry is still closed so the stream stays valid and remaining files still zip.
                try {
                    file.inputStream().use { it.copyTo(zos) }
                } finally {
                    zos.closeEntry()
                }
            }
        }
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    /** Empties the active capture file, deletes rotations, and deletes all crash reports. */
    fun clear() {
        runCatching { logFile.writeText("") }
        logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(logFile.name) && it.name != logFile.name }
            ?.forEach { runCatching { it.delete() } }
        crashesDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun deviceHeader(): String = buildString {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("app:     ${context.packageName} ${pkg?.versionName ?: "?"} (build ${pkg?.let { versionCodeOf(it) } ?: "?"})")
        appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL}")
        append("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    companion object {
        const val MAX_CRASH_FILES = 20
        private const val EXPORTS_DIR = "log-exports"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_CRASHES_SEEN_AT = "crashes_seen_at"

        // SimpleDateFormat is not thread-safe; build a fresh instance per call (cheap, and these
        // are not hot paths). Locale.US keeps the timestamps stable regardless of device locale.
        private fun fileTs(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        private fun humanTs(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        fun stackTraceString(error: Throwable): String =
            StringWriter().also { sw -> error.printStackTrace(PrintWriter(sw)) }.toString()

        @Suppress("DEPRECATION")
        private fun versionCodeOf(pkg: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong()
    }
}
