package dev.agentic.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Inline filter-as-you-type search field: a flat, filled "pill" [TextField] with a leading magnifier
 * and no trailing icon (clearing is by backspace). Stateless — the caller owns the query.
 *
 * Why a plain [TextField] and NOT Material3 `SearchBar`/`DockedSearchBar`: the native search bar has a
 * fixed minimum width and ignores a weighted layout slot, so inline in the top bar it overran the
 * action buttons. A plain TextField respects `weight(1f).fillMaxWidth()` — fills exactly the space left
 * after the title + actions, full-width in any orientation, no overlap. With no trailing icon there's
 * nothing in the (vertically-biased) trailing slot to look crooked. Results render in the list body.
 *
 * Note: the unprompted-keyboard-on-entry (the list/pane scaffold auto-focusing this field on
 * composition) is handled by the host clearing focus when it lands on the list — see
 * `NarrowScaffoldHome`. A plain field keeps tap-to-focus working normally.
 *
 * @param searching accepted for API stability; loading feedback belongs OUTSIDE the field.
 * @param onSearch optional IME "Search" action handler.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") searching: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = if (onSearch != null) {
            KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search)
        } else {
            KeyboardOptions.Default
        },
        keyboardActions = if (onSearch != null) KeyboardActions(onSearch = { onSearch() }) else KeyboardActions.Default,
    )
}
