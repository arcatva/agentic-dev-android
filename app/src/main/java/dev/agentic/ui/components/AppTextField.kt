package dev.agentic.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The app's one outlined text field. Every form-style input — login host, the password field, the
 * new-request template-variable dialog, the transcript answer / deny-reason / plan-feedback cards,
 * and the voice-dictation prompt — routes through this so they share a single shape, color set,
 * error / supporting-text contract, and accessibility wiring instead of each call site re-deriving
 * ~25 `OutlinedTextField` parameters and drifting apart.
 *
 * Focus dismissal is deliberately NOT this component's concern: tapping outside any field is handled
 * once per screen by [Modifier.clearFocusOnTap] on the screen root (a field can't observe taps that
 * miss it). Keep that split — don't add tap-to-blur logic here.
 *
 * Two overloads:
 *  - the plain `value` / `onValueChange` [String] one below — for form fields edited ONLY by the IME
 *    (login host/password, template-variable dialog, transcript answer/deny/plan cards). It also
 *    keeps `visualTransformation` (password masking) and `keyboardActions`, which the state API drops.
 *  - a `state: TextFieldState` one (further down) — for fields whose text is ALSO written from outside
 *    the IME (voice dictation, prefill) and/or recomposed by unrelated async emits, where the String
 *    overload would strand the caret behind appended text. Pair it with [rememberSyncedTextFieldState]
 *    to bridge a ViewModel `StateFlow<String>`. This opts into the Material3 `TextFieldState` overload
 *    (still `@ExperimentalMaterial3Api` on material3 1.4.0-alpha18).
 *
 * Accessibility: pass a real [label] whenever the design shows one (TalkBack reads it; a [placeholder]
 * disappears on input and is hint-only). A non-null [errorMessage] both flips `isError` and is mapped
 * to a spoken `semantics { error(..) }` so the failure is announced, not just colored red.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // extraSmall is exactly OutlinedTextField's own default shape — pinned here so every field shares
    // one source of truth without depending on OutlinedTextFieldDefaults.shape's API surface.
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val isError = errorMessage != null
    val helper = errorMessage ?: supportingText
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // Announce the error reason to TalkBack — isError alone only changes the color.
        modifier = if (isError) modifier.semantics { error(errorMessage!!) } else modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = helper?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = shape,
        colors = colors,
    )
}

/**
 * [TextFieldState]-backed twin of [AppTextField]. The state owns text + caret + composing region
 * together, so an external (non-IME) text change can place the caret at the end of the new text
 * instead of stranding it — see [rememberSyncedTextFieldState], which most call sites use to bridge
 * a ViewModel `StateFlow<String>` into the `state`.
 *
 * Drops `visualTransformation` and `keyboardActions` (no equivalent on the Material3 state overload).
 * Password masking still belongs on the String overload above. `label` / `labelPosition` and the
 * other newer parameters are left at their defaults via named arguments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val isError = errorMessage != null
    val helper = errorMessage ?: supportingText
    val lineLimits = if (singleLine) {
        TextFieldLineLimits.SingleLine
    } else {
        TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines)
    }
    OutlinedTextField(
        state = state,
        // Announce the error reason to TalkBack — isError alone only changes the color.
        modifier = if (isError) modifier.semantics { error(errorMessage!!) } else modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = helper?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        lineLimits = lineLimits,
        shape = shape,
        colors = colors,
    )
}
