package dev.agentic.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Tap on the empty space of this container to drop TextField focus and dismiss the soft keyboard.
 *
 * Why this is a screen-level modifier and not part of a text field: a field cannot detect taps that
 * land OUTSIDE itself. In Compose, a focused TextField keeps its focus (cursor + IME) until something
 * actively clears it — tapping elsewhere does nothing by default. That is exactly the "home search
 * cursor never goes away" bug. So the dismiss handler has to live on the screen root that owns the
 * list / form, applied to the OUTERMOST container.
 *
 * Safe to put over a [LazyColumn], clickable rows, Buttons, a draggable pane splitter, swipe-to-
 * dismiss, and pull-to-refresh: pointer events are consumed child-first in the Main pass, so those
 * children handle their taps/drags before this detector ever classifies a gesture, and
 * [detectTapGestures] never engages a drag (scrolling/swiping/resizing keep working). It fires only
 * on a press-and-release over genuinely empty space. It adds no ripple and — unlike a no-indication
 * `clickable` — no click semantics / TalkBack click target, so it stays invisible to accessibility.
 *
 * On foundation 1.8.2 `focusManager.clearFocus()` alone normally also hides the IME (losing field
 * focus ends the input session); we additionally call `keyboard?.hide()` first to cover the known
 * stuck-keyboard case where a focused field leaves composition during navigation/recomposition
 * (Google issuetracker 237308379). This mirrors what `SearchResultsPanel` already did by hand.
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
