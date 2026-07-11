package dev.agentic.ui.components

import android.Manifest
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentic.data.log.AppLog
import dev.agentic.voice.SherpaDictation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-facing state + actions for voice dictation, shared by every mic button.
 *
 * Engine choice hidden by [rememberDictationController]: arm64 → [SherpaDictation] (on-device,
 * bilingual zh-en, real-time, offline; first use downloads a model the user picks); otherwise the
 * platform [SpeechRecognizer] (device-locale, real-time partials).
 *
 * While [listening] callers render the text field READ-ONLY — recognizer rewrites live; manual edits
 * would fight it. Mic button reads [available]/[listening]/[downloading]/[status], calls [onMicClick];
 * first-run dialog rendered by [DictationDownloadDialog].
 */
@Stable
class DictationController internal constructor(val available: Boolean) {
    var listening by mutableStateOf(false)
        internal set
    var downloading by mutableStateOf(false)
        internal set
    /** 0f..1f model-download progress; only meaningful while [downloading]. */
    var downloadProgress by mutableFloatStateOf(0f)
        internal set
    /** Short human-readable status (permission denied, download %, error), or null when idle/clean. */
    var status by mutableStateOf<String?>(null)
        internal set
    /** True when the first-run download dialog should be shown. */
    var promptDownload by mutableStateOf(false)
        internal set

    internal var onMic: () -> Unit = {}
    internal var onConfirm: (SherpaDictation.Quality) -> Unit = {}
    internal var onDismiss: () -> Unit = {}
    internal var onStop: () -> Unit = {}
    // Engine callbacks stop writing to the field when true — set when stopping for a send so the
    // recognizer's late/async final result can't repopulate the field after submit cleared it.
    internal var suppressWrites = false

    /** Toggle: start listening (request permission / prompt download), cancel a download, or stop. */
    fun onMicClick() = onMic()

    /** Called by the dialog: download [quality], then start listening. */
    fun confirmDownload(quality: SherpaDictation.Quality) = onConfirm(quality)

    /** Called by the dialog: dismiss without downloading. */
    fun dismissDownload() = onDismiss()

    /** Stop dictation because the text is being sent. Mutes trailing final-result writes so they
     *  can't land back in the field after submit clears it. */
    fun stopForSend() {
        // Set unconditionally before the early return — a just-stopped dictation can have a trailing
        // async final result still queued that would repopulate after submit clears the field.
        suppressWrites = true
        if (!listening) return
        listening = false
        onStop()
    }
}

/** Creates a [DictationController] bound to the current composition.
 *  @param currentText field text at the moment listening starts; recognized speech appends.
 *  @param onText full field text (existing + recognized), live, on the main thread. */
@Composable
fun rememberDictationController(
    currentText: () -> String,
    onText: (String) -> Unit,
): DictationController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestCurrentText by rememberUpdatedState(currentText)
    val latestOnText by rememberUpdatedState(onText)

    // sherpa (on-device bilingual) preferred on arm64; platform recognizer is the fallback.
    val sherpa = remember { if (SherpaDictation.isSupported) SherpaDictation(ctx) else null }
    val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
    val speech = remember {
        if (sherpa == null && speechAvailable) SpeechRecognizer.createSpeechRecognizer(ctx) else null
    }

    val controller = remember { DictationController(available = sherpa != null || speech != null) }

    // Text present when current dictation began; recognized speech is appended to it.
    var base by remember { mutableStateOf("") }
    fun combine(spoken: String): String =
        if (base.isBlank()) spoken else if (spoken.isBlank()) base else "$base $spoken"
    // All engine → field writes go through here so stop-for-send can mute trailing results.
    fun emit(spoken: String) { if (!controller.suppressWrites) latestOnText(combine(spoken)) }

    // ── Platform SpeechRecognizer fallback wiring ──
    val speechListener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val spoken = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) emit(spoken)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            // Keep listening=true until a terminal callback; onEndOfSpeech still has processing pending.
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { controller.listening = false }
            override fun onResults(results: Bundle?) {
                controller.listening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                emit(spoken)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speech?.destroy()
            sherpa?.destroy()
        }
    }

    fun startSherpa(quality: SherpaDictation.Quality) {
        AppLog.v("Voice", "Recognition start: sherpa ${quality.name}")
        base = latestCurrentText()
        controller.suppressWrites = false
        controller.status = null
        controller.listening = true
        sherpa?.start(
            quality,
            onText = { spoken -> emit(spoken) },
            onError = { e ->
                controller.listening = false
                controller.status = when (e) {
                    "permission-denied" -> "需要麦克风权限"
                    else -> "语音识别出错,请重试"
                }
            },
        )
    }

    fun startSpeech() {
        AppLog.v("Voice", "Recognition start: platform SpeechRecognizer")
        base = latestCurrentText()
        controller.suppressWrites = false
        controller.status = null
        controller.listening = true
        speech?.setRecognitionListener(speechListener)
        speech?.startListening(buildDictationIntent())
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            AppLog.d("Voice", "Permission denied: RECORD_AUDIO")
            controller.status = "需要麦克风权限"
            return@rememberLauncherForActivityResult
        }
        controller.status = null
        when {
            sherpa == null -> startSpeech()
            // Prefer a model that's already downloaded (high accuracy wins if both are present).
            sherpa.isModelReady(SherpaDictation.Quality.HIGH) -> startSherpa(SherpaDictation.Quality.HIGH)
            sherpa.isModelReady(SherpaDictation.Quality.STANDARD) -> startSherpa(SherpaDictation.Quality.STANDARD)
            else -> controller.promptDownload = true
        }
    }

    controller.onConfirm = { quality ->
        AppLog.d("Voice", "Model download start: ${quality.name}")
        controller.promptDownload = false
        controller.downloading = true
        controller.downloadProgress = 0f
        controller.status = "下载语音模型 0%"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                sherpa?.downloadModel(quality) { downloaded, total ->
                    // Engine posts this on the main thread, so updating Compose state here is safe.
                    controller.downloadProgress = if (total > 0) downloaded.toFloat() / total else 0f
                    controller.status = "下载语音模型 ${(controller.downloadProgress * 100).toInt()}%"
                } ?: false
            }
            controller.downloading = false
            when {
                ok -> {
                    AppLog.d("Voice", "Model download finish: ${quality.name}")
                    startSherpa(quality)
                }
                sherpa?.downloadWasCancelled == true -> controller.status = null
                else -> {
                    AppLog.d("Voice", "Model download fail: ${quality.name}")
                    controller.status = "模型下载失败,请检查网络后重试"
                }
            }
        }
    }

    controller.onStop = {
        AppLog.v("Voice", "Recognition stop")
        sherpa?.stop()
        speech?.stopListening()
    }

    controller.onDismiss = {
        controller.promptDownload = false
        controller.status = null
    }

    controller.onMic = {
        when {
            controller.listening -> {
                AppLog.d("Voice", "Mic toggle: stop listening")
                controller.listening = false
                sherpa?.stop()
                speech?.stopListening()
            }
            controller.downloading -> {
                AppLog.d("Voice", "Mic toggle: cancel download")
                sherpa?.cancelDownload()
                controller.downloading = false
                controller.status = null
            }
            controller.promptDownload -> { /* dialog is showing; ignore the mic */ }
            else -> {
                AppLog.d("Voice", "Mic toggle: start (requesting permission)")
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    return controller
}

/** First-run model-download dialog. Render near any screen with a mic button driven by [controller]; it
 *  shows only while [DictationController.promptDownload] is true. */
@Composable
fun DictationDownloadDialog(controller: DictationController) {
    if (!controller.promptDownload) return
    AlertDialog(
        onDismissRequest = { controller.dismissDownload() },
        title = { Text("下载语音识别模型") },
        text = {
            Text(
                "首次使用需要下载中英文语音识别模型,下载后离线可用(中文、英文、中英混说都能识别)。\n\n" +
                    "· 标准:约 189 MB,速度快\n" +
                    "· 高精度:约 340 MB,识别更准、更占空间和电量\n\n" +
                    "建议在 Wi-Fi 下下载。",
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { controller.confirmDownload(SherpaDictation.Quality.STANDARD) }) {
                    Text("标准 189MB")
                }
                TextButton(onClick = { controller.confirmDownload(SherpaDictation.Quality.HIGH) }) {
                    Text("高精度 340MB")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { controller.dismissDownload() }) { Text("取消") }
        },
    )
}
