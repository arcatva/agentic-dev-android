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
 * Inline filter-as-you-type search field: a flat filled "pill" [TextField] with a leading magnifier,
 * no trailing icon (clearing by backspace). Stateless — caller owns the query.
 *
 * Plain [TextField], NOT material3 SearchBar/DockedSearchBar: the native search bar has a fixed
 * min-width and ignores a weighted slot, so inline in the top bar it overran action buttons. A
 * plain TextField respects `weight(1f).fillMaxWidth()` — fills the space left after title+actions.
 *
 * Auto-focus-on-entry is handled by the host clearing focus when the scaffold lands on the list.
 *
 * @param searching kept for API stability; loading feedback belongs OUTSIDE the field.
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
