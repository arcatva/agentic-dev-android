package dev.agentic.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Tap empty space of this container to drop TextField focus and dismiss the soft keyboard.
 *
 * Screen-level modifier, not a field modifier: a field can't observe taps that land OUTSIDE itself.
 * In Compose, a focused TextField keeps cursor + IME until something actively clears focus — tapping
 * elsewhere is a no-op. Dismiss has to live on the screen root.
 *
 * Safe over LazyColumn, clickable rows, Buttons, a draggable pane splitter, swipe-to-dismiss, and
 * pull-to-refresh: pointer events are consumed child-first in the Main pass, children handle their
 * taps/drags first, [detectTapGestures] never engages a drag (scroll/swipe/resize keep working).
 * Fires only on press-and-release over genuinely empty space. No ripple and — unlike a no-indication
 * clickable — no click semantics / TalkBack click target, so stays invisible to a11y.
 *
 * We additionally call `keyboard?.hide()` first to cover the known stuck-keyboard case where a
 * focused field leaves composition during navigation/recomposition (issuetracker 237308379).
 */
fun Modifier.clearFocusOnTap(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    pointerInput(Unit) {
        detectTapGestures(onTap = {
            keyboard?.hide()
            focusManager.clearFocus()
        })
    }
}
