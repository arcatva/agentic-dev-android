package dev.agentic.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentic.domain.PendingAttachment
import dev.agentic.domain.UploadState

/**
 * One chip in a pending-attachment row. Shows the file name (truncated), a state indicator (spinner
 * for in-flight, check for done, error for failed), and an X to remove. Shared by the session
 * composer ([dev.agentic.ui.session.SessionScreen]) and the New-request form
 * ([dev.agentic.ui.newrequest.NewRequestScreen]) so attachments render identically in both places.
 *
 * Built with Surface + Row rather than AssistChip because we need a tightly sized leading state slot
 * (16dp) and a short 28dp X button — the chip defaults fight those.
 */
@Composable
fun AttachmentChip(att: PendingAttachment, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        ) {
            // 16dp state slot — same column width regardless of state so the name doesn't reflow
            // when Pending → Uploading → Done lands.
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (att.state) {
                    UploadState.Pending, UploadState.Uploading -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    is UploadState.Done -> Icon(
                        Icons.Rounded.Check,
                        contentDescription = "uploaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    is UploadState.Failed -> Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = att.state.reason,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                att.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "remove attachment",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
