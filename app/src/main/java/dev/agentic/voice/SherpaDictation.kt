package dev.agentic.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.agentic.data.log.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * On-device streaming speech recognition via sherpa-onnx, using the bilingual (Chinese + English)
 * streaming-zipformer model. Unlike the platform [android.speech.SpeechRecognizer], this does NOT
 * depend on the device having Google's Chinese model — it ships its own and runs fully offline.
 *
 * - Recognizes Chinese and English (including mixed speech) in real time.
 * - First use downloads the model into [Context.getFilesDir]; after that, fully offline. Two model
 *   qualities are offered (see [Quality]): a smaller quantized one and a larger, more accurate one.
 * - Native libs are shipped for arm64-v8a only; on other ABIs [isSupported] is false and callers
 *   should fall back to the platform recognizer.
 *
 * Threading: capture + decode run on a private background thread; every callback is posted to the
 * main thread. Not thread-safe for concurrent start() calls — drive it from the UI (main) thread.
 */
class SherpaDictation(context: Context) {

    // Hold only the application context to avoid leaking an Activity/Composition.
    private val appContext = context.applicationContext

    /**
     * A downloadable model variant. Both are the same bilingual zh-en streaming zipformer; [STANDARD]
     * is int8-quantized (smaller, faster, lower accuracy) and [HIGH] is fp32 (larger, more accurate).
     * The two file sets have different names, so they can coexist on disk.
     */
    enum class Quality(
        internal val files: Map<String, Long>, // filename -> exact byte size (download integrity check)
        internal val encoder: String,
        internal val decoder: String,
        internal val joiner: String,
    ) {
        STANDARD(
            files = linkedMapOf(
                "tokens.txt" to 56_317L,
                "encoder-epoch-99-avg-1.int8.onnx" to 181_895_032L,
                "decoder-epoch-99-avg-1.int8.onnx" to 13_091_040L,
                "joiner-epoch-99-avg-1.int8.onnx" to 3_228_404L,
            ),
            encoder = "encoder-epoch-99-avg-1.int8.onnx",
            decoder = "decoder-epoch-99-avg-1.int8.onnx",
            joiner = "joiner-epoch-99-avg-1.int8.onnx",
        ),
        HIGH(
            files = linkedMapOf(
                "tokens.txt" to 56_317L,
                "encoder-epoch-99-avg-1.onnx" to 330_083_505L,
                "decoder-epoch-99-avg-1.onnx" to 13_876_452L,
                "joiner-epoch-99-avg-1.onnx" to 12_833_618L,
            ),
            encoder = "encoder-epoch-99-avg-1.onnx",
            decoder = "decoder-epoch-99-avg-1.onnx",
            joiner = "joiner-epoch-99-avg-1.onnx",
        );

        /** Total bytes to download for this variant. */
        val totalBytes: Long get() = files.values.sum()
    }

    companion object {
        /** sherpa-onnx model zoo: streaming bilingual zh-en zipformer, hosted on Hugging Face. */
        private const val MODEL_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"

        /** We ship sherpa native libs for arm64-v8a only. */
        val isSupported: Boolean
            get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }

    private val main = Handler(Looper.getMainLooper())
    private val modelDir = File(appContext.filesDir, "sherpa-zh-en")

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var builtQuality: Quality? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    // CAS guard: true from a successful start() until that session's thread fully finishes (cleared in
    // the thread's finally). Stops two overlapping recognition sessions / concurrent recognizer frees.
    private val running = AtomicBoolean(false)
    // Each session's OWN run flag — the audio loop checks this, not [running] — so claiming the engine
    // for a new session can never accidentally resurrect a previous (already-stopping) loop.
    @Volatile private var sessionActive: AtomicBoolean? = null
    private val downloadCancelled = AtomicBoolean(false)

    /** True once every file for [quality] is present on disk with its expected size. */
    fun isModelReady(quality: Quality): Boolean =
        quality.files.all { (name, size) -> File(modelDir, name).let { it.exists() && it.length() == size } }

    /**
     * Downloads any missing files for [quality]. BLOCKS — call from a background thread. Reports
     * cumulative downloaded bytes via [onProgress]. Returns true on success, false on error/cancel.
     * Each file goes to a ".part" temp and is renamed only after its size is verified, so an
     * interrupted download never leaves a corrupt model behind.
     */
    fun downloadModel(quality: Quality, onProgress: (downloaded: Long, total: Long) -> Unit): Boolean {
        if (isModelReady(quality)) return true
        downloadCancelled.set(false)
        if (!modelDir.exists() && !modelDir.mkdirs()) return false

        val total = quality.totalBytes
        var done = 0L
        for ((name, size) in quality.files) {
            val target = File(modelDir, name)
            if (target.exists() && target.length() == size) {
                done += size
                main.post { onProgress(done, total) }
                continue
            }
            val tmp = File(modelDir, "$name.part")
            try {
                val conn = (URL("$MODEL_BASE/$name").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                try {
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                if (downloadCancelled.get()) { tmp.delete(); return false }
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                main.post { onProgress(done, total) }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (tmp.length() != size || !tmp.renameTo(target)) {
                    tmp.delete()
                    return false
                }
            } catch (t: Throwable) {
                AppLog.w("Voice", "Model download failed", t)
                tmp.delete()
                return false
            }
        }
        return isModelReady(quality)
    }

    /** Cancels an in-flight [downloadModel]. */
    fun cancelDownload() = downloadCancelled.set(true)

    /** True if the most recent [downloadModel] returned false because it was cancelled (not an error). */
    val downloadWasCancelled: Boolean
        get() = downloadCancelled.get()

    private fun ensureRecognizer(quality: Quality): OnlineRecognizer {
        recognizer?.let { if (builtQuality == quality) return it else { it.release(); recognizer = null } }
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, quality.encoder).absolutePath,
                    decoder = File(modelDir, quality.decoder).absolutePath,
                    joiner = File(modelDir, quality.joiner).absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                modelType = "zipformer",
            ),
            enableEndpoint = true,
        )
        // assetManager = null -> the recognizer loads from the absolute file paths above (newFromFile).
        return OnlineRecognizer(config = cfg).also { recognizer = it; builtQuality = quality }
    }

    /**
     * Starts microphone capture + streaming recognition with the given [quality] model (which must
     * already be downloaded). [onText] is called on the main thread with the full running
     * transcription so far (finalized segments + the current partial). RECORD_AUDIO must be granted.
     * [onError] reports a fatal start/runtime failure (also on the main thread).
     */
    fun start(quality: Quality, onText: (String) -> Unit, onError: (String) -> Unit) {
        // Ensure any previous session's thread has fully finished BEFORE we (re)build or release the
        // recognizer below — otherwise ensureRecognizer() could free a recognizer the old thread is
        // still decoding on (native use-after-free), e.g. on a quick stop -> start with a new quality.
        audioThread?.let { prev ->
            if (prev.isAlive) {
                sessionActive?.set(false)
                try { audioRecord?.stop() } catch (_: Throwable) { AppLog.d("Voice", "AudioRecord.stop() on dead recorder") } // unblock a parked read()
                prev.join(1500)
                if (prev.isAlive) { onError("busy"); return }
            }
        }
        audioThread = null
        // Claim the engine atomically so two concurrent start()s can't both proceed.
        if (!running.compareAndSet(false, true)) { onError("busy"); return }

        if (!isModelReady(quality)) { running.set(false); onError("model-not-ready"); return }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) { running.set(false); onError("permission-denied"); return }

        val rec = try {
            ensureRecognizer(quality)
        } catch (t: Throwable) {
            AppLog.e("Voice", "ensureRecognizer failed", t)
            running.set(false); onError("init-failed: ${t.message}"); return
        }
        val stream = rec.createStream()

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, sampleRate * 2)
        val audio = try {
            @Suppress("MissingPermission") // checked above
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (t: Throwable) {
            AppLog.w("Voice", "AudioRecord init failed", t)
            running.set(false); stream.release(); onError("audio-init-failed: ${t.message}"); return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            running.set(false); audio.release(); stream.release(); onError("audio-init-failed"); return
        }

        audioRecord = audio
        try {
            audio.startRecording()
        } catch (t: Throwable) {
            AppLog.w("Voice", "AudioRecord.startRecording failed", t)
            running.set(false)
            audio.release(); audioRecord = null; stream.release()
            onError("audio-start-failed: ${t.message}"); return
        }
        val active = AtomicBoolean(true)
        sessionActive = active
        audioThread = thread(name = "sherpa-audio") {
            val chunk = ShortArray(sampleRate / 10) // 0.1 s per read
            val committed = StringBuilder()           // finalized segments (after endpoints)
            try {
                while (active.get()) {
                    val read = audio.read(chunk, 0, chunk.size)
                    // Negative return = a real AudioRecord error (e.g. dead object / invalid op):
                    // surface it via onError instead of busy-spinning. Zero = no data yet: back off.
                    if (read < 0) throw RuntimeException("AudioRecord.read error $read")
                    if (read == 0) { Thread.sleep(10); continue }
                    val samples = FloatArray(read) { chunk[it] / 32768.0f }
                    stream.acceptWaveform(samples, sampleRate)
                    while (rec.isReady(stream)) rec.decode(stream)
                    val cur = rec.getResult(stream).text
                    val isEnd = rec.isEndpoint(stream)
                    if (isEnd) {
                        // Fold the just-finished segment into `committed` exactly once, then reset.
                        if (cur.isNotBlank()) {
                            if (committed.isNotEmpty()) committed.append(' ')
                            committed.append(cur)
                        }
                        rec.reset(stream)
                    }
                    // After an endpoint `cur` is already in `committed`, so don't append it again
                    // (doing so duplicated the last segment at every sentence boundary).
                    val display = if (isEnd) committed.toString() else join(committed, cur)
                    main.post { onText(display) }
                }
                // Drain whatever is left after the user stops, then emit the final text once.
                stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                val tail = rec.getResult(stream).text
                if (tail.isNotBlank()) {
                    if (committed.isNotEmpty()) committed.append(' ')
                    committed.append(tail)
                }
                val finalText = committed.toString()
                main.post { onText(finalText) }
            } catch (t: Throwable) {
                AppLog.w("Voice", "Recognition error: ${t.message}", t)
                main.post { onError("recognition-error: ${t.message}") }
            } finally {
                try { audio.stop() } catch (_: Throwable) { AppLog.d("Voice", "AudioRecord.stop() on dead recorder") }
                audio.release()
                audioRecord = null
                stream.release()
                running.set(false) // release the engine claim so the next session can start
            }
        }
    }

    /** Stops capture; the final transcription is delivered via the start() onText callback. */
    fun stop() { sessionActive?.set(false) }

    /** Releases the recognizer and stops everything. Call when the owner leaves composition. */
    fun destroy() {
        sessionActive?.set(false)
        downloadCancelled.set(true)
        // Stop the recorder so a thread parked in a blocking audio.read() returns at once and the
        // loop can exit (stop() is also guarded in the thread's finally, so calling it here is safe).
        try { audioRecord?.stop() } catch (_: Throwable) { AppLog.d("Voice", "AudioRecord.stop() on dead recorder") }
        val t = audioThread
        t?.join(2000)
        audioThread = null
        // Only free the native recognizer once the audio thread has actually stopped using it;
        // releasing it while decode()/getResult() is still running would crash natively.
        if (t == null || !t.isAlive) {
            recognizer?.release()
            recognizer = null
            builtQuality = null
        }
    }

    private fun join(committed: CharSequence, current: String): String = when {
        committed.isEmpty() -> current
        current.isBlank() -> committed.toString()
        else -> "$committed $current"
    }
}
