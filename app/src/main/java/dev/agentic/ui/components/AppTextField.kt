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
 * App-wide outlined text field. Every form input (login, password, template-variable dialog,
 * transcript answer/deny/plan cards, voice dictation) routes through this so shape, colors, and
 * a11y contract stay identical.
 *
 * Tap-outside-to-dismiss is NOT this concern — the screen root uses [clearFocusOnTap] (a field can't
 * observe taps that miss it). Keep that split.
 *
 * Two overloads: the [String] `value`/`onValueChange` one below keeps `visualTransformation` (password
 * masking) and `keyboardActions`; a [TextFieldState] one further down is for fields written from
 * outside the IME (voice dictation, prefill), often paired with [rememberSyncedTextFieldState] to
 * bridge a ViewModel `StateFlow<String>`. The state overload is `@ExperimentalMaterial3Api` on
 * material3 1.4.0-alpha18.
 *
 * A11y: pass a real [label] whenever the design shows one; a non-null [errorMessage] flips isError
 * AND maps to `semantics { error(..) }` so TalkBack announces it.
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
    // extraSmall matches OutlinedTextField's own default shape — pinned here so every field shares one source.
    shape: Shape = MaterialTheme.shapes.extraSmall,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val isError = errorMessage != null
    val helper = errorMessage ?: supportingText
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // Announce error reason to TalkBack — isError alone only changes the color.
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
 * [TextFieldState]-backed twin of [AppTextField]. State owns text + caret + composing region together,
 * so an external (non-IME) text change can place the caret at the END of the new text instead of
 * stranding it — see [rememberSyncedTextFieldState]. Drops `visualTransformation` and
 * `keyboardActions` (no equivalent on the Material3 state overload); password masking stays on the
 * String overload.
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
        // Announce error reason to TalkBack.
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
