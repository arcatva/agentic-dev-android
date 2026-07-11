package dev.agentic.ui.components

import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * OutlinedTextField paired with a mic IconButton for on-device voice dictation. Recognized speech
 * is APPENDED (not replaced) and streams in live.
 *
 * Engine chosen by [rememberDictationController]: arm64 → sherpa-onnx (on-device, bilingual zh+en,
 * offline after one-time model download); else platform SpeechRecognizer. This composable just
 * renders the controller's state. Shared replacement for the near-identical copies formerly in
 * NewRequestScreen and SessionScreen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoiceDictationField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    maxLines: Int = 6,
    // Shape + colors forwarded to the inner AppTextField so a screen can give the dictation field
    // the same look as its other inputs. Defaults match AppTextField's own, so existing sites unchanged.
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val dict = rememberDictationController(currentText = { value }, onText = onValueChange)

    // TextFieldState-backed field bridged to the hoisted String. Dictation appends via the SAME
    // onValueChange; the bridge re-writes with caret at END so streamed speech follows caret instead
    // of landing behind it (String overload's stale-caret bug).
    val fieldState = rememberSyncedTextFieldState(value, onValueChange)

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            // First-run model download: determinate progress (~189 MB).
            dict.downloading -> LinearProgressIndicator(
                progress = { dict.downloadProgress },
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
            dict.listening -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 2.dp))
        }
        AppTextField(
            state = fieldState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder.takeIf { it.isNotEmpty() },
            enabled = enabled,
            // Lock manual edit while recognizer streams in — editing fights the live rewrite ("jumps").
            // Editable again on stop.
            readOnly = dict.listening,
            singleLine = false,
            maxLines = maxLines,
            shape = shape,
            colors = colors,
            supportingText = dict.status ?: if (!dict.available) "此设备不支持语音识别" else null,
            trailingIcon = {
                if (dict.available) {
                    IconButton(
                        enabled = enabled,
                        onClick = { dict.onMicClick() },
                    ) {
                        Icon(
                            if (dict.listening) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (dict.listening) "stop recording" else "start voice input",
                            tint = if (dict.listening) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
    // First-run model-download chooser (Standard / High).
    DictationDownloadDialog(dict)
}

/**
 * Free-form dictation Intent for the platform SpeechRecognizer fallback.
 *
 * - EXTRA_PARTIAL_RESULTS=true → interim hypotheses (real-time display)
 * - EXTRA_LANGUAGE = device locale → primary + fallback when recognizer can't auto-detect
 * - On Android 14+ (API 34 — where language-detection/switch extras + onLanguageDetection exist) also
 *   auto-detect and mid-utterance-switch. Best-effort: depends on device's recognition service model.
 */
internal fun buildDictationIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    val locale = java.util.Locale.getDefault()
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.language)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
    }
}
