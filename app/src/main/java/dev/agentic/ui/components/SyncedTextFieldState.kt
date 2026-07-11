package dev.agentic.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * A [TextFieldState] kept in two-way sync with a hoisted plain-[String] `value`/`onValueChange` —
 * for fields whose text lives in a ViewModel `StateFlow` (session composer, new-request prompt) but
 * which ALSO get written from outside the IME (voice dictation appends, prefill/restore, clear-on-send)
 * and are recomposed by unrelated async emits.
 *
 * Plain `value: String` overload keeps its caret (TextRange) inside its own remembered
 * TextFieldValue and, on EXTERNAL value swap, does `copy(text = value)` — keeps OLD caret offset,
 * just swaps text. Appended/streamed text lands AFTER a stationary caret; a poll re-emit mid-IME-edit
 * freezes the caret. TextFieldState owns text+caret+composing together; this helper:
 *  - PULL: when value changes from a non-IME source, text written with caret parked at END
 *    ([setTextAndPlaceCursorAtEnd]). Keyed on value + guarded by `value != current`, so it NEVER
 *    fires for the user's own keystroke (after which value already equals field text). That guard
 *    keeps active IME composing region intact + stops same-string poll re-emit from disturbing it.
 *  - PUSH: field edits flow back up via [onValueChange].
 *
 * The two directions can't loop: external pull sets text==value (push's onValueChange(value) is a
 * no-op write); user keystroke makes value==text (pull's guard skips).
 * [rememberUpdatedState] keeps the latest [onValueChange] without restarting push.
 */
@Composable
fun rememberSyncedTextFieldState(
    value: String,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val state = rememberTextFieldState(value)
    val latestOnValueChange = rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (value != state.text.toString()) {
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }

    LaunchedEffect(state) {
        // Skip snapshotFlow's initial emission (equals seed value — would be a redundant onValueChange at composition).
        var isFirst = true
        snapshotFlow { state.text.toString() }
            .collect { text ->
                if (isFirst) isFirst = false else latestOnValueChange.value(text)
            }
    }

    return state
}
