package dev.agentic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared tonal containment card — single source for section cards. One tonal step above the page
 * canvas (surfaceContainer on the background); shapes.medium corners; emphasized header. Used by
 * NewRequest / Models / Session settings / Global settings / Diagnostics so they never drift apart.
 *
 * Heading hierarchy: TopAppBar page title (titleLarge) → this card header (titleMedium SemiBold) →
 * in-card subsection headers (titleSmall SemiBold). Keep the card header one step above subsections
 * so nested headers don't read as siblings.
 *
 * [trailing] is an optional slot in the header row (an Add TextButton, a Switch) so callers don't
 * rebuild their own header rows. MD3 Expressive: fields inside the card step one more level up
 * (surfaceContainerHigh) for clear tonal elevation hierarchy.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (trailing != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    // Trailing control gets a fixed 32dp LAYOUT slot so its 48dp min touch target
                    // doesn't inflate the header row; wrapContentHeight(unbounded) keeps the full
                    // natural size centered, touch target stays ≥48dp, overflow hides in card padding.
                    Box(
                        Modifier
                            .height(32.dp)
                            .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
                        contentAlignment = Alignment.Center,
                    ) {
                        trailing()
                    }
                }
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

/**
 * Filled, borderless, rounded field colors for inputs INSIDE a [SectionCard].
 *
 * Fill = surfaceContainerHigh (one step above the card's surfaceContainer) so the field reads as
 * raised. Unfocused border transparent (filled look); focus shows a thin primary ring. Paired with
 * shapes.small (12dp symmetric) for a rounded filled field without stock OutlinedTextField's
 * square-bottom anatomy or the roundedShape API (only in material3 1.5+).
 *
 * [mutedUnfocusedText]: unfocused text in onSurfaceVariant (de-emphasized) instead of onSurface —
 * for pre-filled defaults / boilerplate that should read as background until focus.
 */
@Composable
fun cardFieldColors(mutedUnfocusedText: Boolean = false): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = if (mutedUnfocusedText)
            MaterialTheme.colorScheme.onSurfaceVariant
        else
            MaterialTheme.colorScheme.onSurface,
    )
