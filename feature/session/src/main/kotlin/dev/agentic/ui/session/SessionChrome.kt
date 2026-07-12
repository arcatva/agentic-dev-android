package dev.agentic.ui.session

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shared rewind overlays (confirm dialog + result toast) for narrow and wide session hosts. */
@Composable
fun SessionDetailOverlays(
    vm: SessionViewModel,
    pendingRewindTurn: Int?,
    onDismissRewind: () -> Unit,
) {
    val context = LocalContext.current
    val rewindResult by vm.rewindResult.collectAsStateWithLifecycle()
    val rewinding by vm.rewinding.collectAsStateWithLifecycle()

    // Toast then acknowledge so the one-shot rewind result doesn't re-fire on recomposition.
    LaunchedEffect(rewindResult) {
        when (val r = rewindResult) {
            is RewindResult.Done -> {
                Toast.makeText(context, "Rewound code to before turn ${r.turnIndex + 1}", Toast.LENGTH_SHORT).show()
                vm.acknowledgeRewind()
            }
            is RewindResult.Failed -> {
                Toast.makeText(context, "Rewind failed: ${r.message}", Toast.LENGTH_LONG).show()
                vm.acknowledgeRewind()
            }
            null -> {}
        }
    }

    pendingRewindTurn?.let { turn ->
        AlertDialog(
            onDismissRequest = onDismissRewind,
            title = { Text("Rewind to this point?") },
            text = {
                Text(
                    "Restore the code to just before turn ${turn + 1}. Edits made since are reverted; " +
                        "files created since are kept. Chat history is unchanged. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(enabled = !rewinding, onClick = { vm.rewind(turn); onDismissRewind() }) { Text("Rewind") }
            },
            dismissButton = { TextButton(onClick = onDismissRewind) { Text("Cancel") } },
        )
    }
}
