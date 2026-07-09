package dev.agentic.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * A [TextFieldState] kept in two-way sync with a hoisted plain-[String] `value` / `onValueChange`
 * pair, for fields whose text lives in a ViewModel `StateFlow` (the session composer's `input`, the
 * new-request `prompt` / `claudeMd`) but which ALSO get written from outside the IME — voice
 * dictation appends, prefill/restore, clear-on-send — and are recomposed by unrelated async emits
 * (token streaming, 2s/2.5s polls).
 *
 * Why this exists: the plain `value: String` overload of `BasicTextField`/`OutlinedTextField` keeps
 * the caret (a `TextRange`) inside its own remembered `TextFieldValue` and, on an EXTERNAL value
 * swap, does `copy(text = value)` — it keeps the OLD caret offset and just swaps the text. So
 * appended/streamed text lands AFTER a stationary caret, and a poll re-emit arriving mid-IME-edit
 * (e.g. Chinese pinyin composition) freezes the caret. `TextFieldState` instead owns text + caret +
 * composing region together; this helper drives it so:
 *
 *  - PULL: when `value` changes from a non-IME source, the new text is written with the caret parked
 *    at the END ([setTextAndPlaceCursorAtEnd]), so appended/streamed text is followed by the caret.
 *    Keyed on `value` and guarded by `value != current`, so it NEVER fires for the user's own
 *    keystroke (after which `value` already equals the field text). That guard is exactly what keeps
 *    an active IME composing region intact and stops a same-string poll re-emit from disturbing it.
 *  - PUSH: the field's own edits flow back up to the hoisted String via [onValueChange].
 *
 * The two directions can't loop: an external pull sets text==value, so the push's resulting
 * `onValueChange(value)` is a no-op write; a user keystroke makes value==text, so the pull's guard
 * skips. [rememberUpdatedState] keeps the latest [onValueChange] without restarting the push.
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
        // Skip snapshotFlow's initial emission — it equals the seed `value`, so forwarding it would be
        // a redundant onValueChange (e.g. a no-op draft write) at composition. Later emits are edits.
        var isFirst = true
        snapshotFlow { state.text.toString() }
            .collect { text ->
                if (isFirst) isFirst = false else latestOnValueChange.value(text)
            }
    }

    return state
}
