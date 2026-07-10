package dev.agentic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
 * Shared tonal containment card — the single source of truth for section cards across the app.
 * Sits one tonal step above the page canvas (surfaceContainer on the background), uses shapes.medium
 * corners, and carries an emphasized section header. Used by NewRequest / Models / Session settings /
 * Global settings / Diagnostics so they never drift apart.
 *
 * Heading hierarchy (app-wide): TopAppBar page title (titleLarge) → this card header
 * (titleMedium SemiBold) → in-card subsection headers (titleSmall SemiBold). Keep the card header
 * one step above the subsections so nested headers don't read as siblings.
 *
 * [trailing] is an optional slot rendered at the end of the header row — an "Add" TextButton,
 * a Switch, etc. — so callers don't rebuild their own header rows outside the card.
 *
 * MD3 Expressive pattern: related controls sit inside a single tonal card instead of floating as
 * uncontained rows. The card's own background steps one level up from the page, and fields inside
 * the card step one more level up (surfaceContainerHigh) — a clear tonal elevation hierarchy.
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
                    // Cap the trailing control to a compact 32dp so its 48dp minimum touch
                    // target doesn't inflate the header row — otherwise the gap between the
                    // title and the card content reads ~12dp taller than on cards without a
                    // trailing control (Router / Sub-agent models).
                    Box(
                        Modifier.heightIn(max = 32.dp),
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
 * The fill is surfaceContainerHigh — one tonal step above the card's surfaceContainer — so the
 * field reads as a raised element within the card, not a flat patch. The unfocused border is
 * transparent (filled look, not a bordered box); focus shows a thin primary ring. Paired with
 * shapes.small (12dp symmetric radius) this yields a rounded filled field WITHOUT the stock
 * OutlinedTextField's square-bottom anatomy or the roundedShape API (only in material3 1.5+).
 *
 * [mutedUnfocusedText]: when true, unfocused text renders in onSurfaceVariant (de-emphasized)
 * instead of full-emphasis onSurface. Use for pre-filled defaults / boilerplate that should read
 * as background until the user focuses the field.
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
