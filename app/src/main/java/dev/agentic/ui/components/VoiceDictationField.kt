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
 * An OutlinedTextField paired with a mic IconButton for on-device voice dictation.
 * Recognized speech is APPENDED to the current [value] (not replaced) and streams in live.
 *
 * The dictation engine is chosen by [rememberDictationController]: on arm64 it uses sherpa-onnx
 * (on-device, bilingual Chinese+English, offline after a one-time model download); otherwise it
 * falls back to the platform SpeechRecognizer. This composable just renders the controller's state.
 *
 * This is a shared replacement for the near-identical copies formerly in
 * NewRequestScreen.kt (VoiceDictationField) and SessionScreen.kt (follow-up dictation bar).
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
    // Shape + colors are forwarded to the inner AppTextField so a screen can give the dictation
    // field the same look as its other inputs (e.g. New request's filled, tonal, rounded fields).
    // Defaults match AppTextField's own defaults, so existing call sites are unchanged.
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val dict = rememberDictationController(currentText = { value }, onText = onValueChange)

    // TextFieldState-backed field bridged to the hoisted String. Dictation appends via the SAME
    // onValueChange, and the bridge re-writes the field placing the caret at the END, so streamed
    // speech follows the caret instead of landing behind it (the String overload's stale-caret bug).
    val fieldState = rememberSyncedTextFieldState(value, onValueChange)

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            // First-run model download: show determinate progress (it's ~189 MB).
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
            // While the recognizer is streaming text in, lock manual editing so the user's keystrokes
            // and the live rewrite don't fight (which made the text "jump"). Editable again on stop.
            readOnly = dict.listening,
            singleLine = false,
            maxLines = maxLines,
            shape = shape,
            colors = colors,
            // Status covers permission/download/error messaging; "unavailable" is shown when there is
            // no usable engine at all.
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
    // First-run model-download chooser (Standard / High accuracy).
    DictationDownloadDialog(dict)
}

/**
 * Builds the free-form dictation Intent for the platform SpeechRecognizer fallback (used by
 * [rememberDictationController] when sherpa-onnx isn't available on the device).
 *
 * - EXTRA_PARTIAL_RESULTS=true → the recognizer streams interim hypotheses (real-time display).
 * - EXTRA_LANGUAGE = device locale → the primary language, and the fallback used when the device's
 *   recognition service can't auto-detect the spoken language (or isn't confident).
 * - On Android 14+ (API 34 — where the language-detection/switch extras and the onLanguageDetection
 *   callback are available) we also ask the recognizer to auto-detect the spoken language and switch
 *   to it mid-utterance. Best-effort: depends on the device's recognition service having the model.
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
