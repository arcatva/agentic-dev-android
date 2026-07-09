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

/**
 * Shared session-detail chrome — the rewind overlays — used by BOTH the phone [SessionScreen] and the
 * wide [dev.agentic.ui.home.WideThreePaneHome] right pane, so the rewind action is wired in exactly
 * ONE place and can't drift between the two layouts.
 *
 * The session-detail overlays: the destructive Rewind confirm dialog and the one-shot rewind-result
 * toast. All driven by [vm].
 *
 * [pendingRewindTurn] is owned by the caller (a single rememberSaveable per session) and set from the
 * transcript's long-press (`SessionContent(onRewind = { … })`); [onDismissRewind] clears it.
 */
@Composable
fun SessionDetailOverlays(
    vm: SessionViewModel,
    pendingRewindTurn: Int?,
    onDismissRewind: () -> Unit,
) {
    val context = LocalContext.current
    val rewindResult by vm.rewindResult.collectAsStateWithLifecycle()
    val rewinding by vm.rewinding.collectAsStateWithLifecycle()

    // Toast the one-shot rewind result, then acknowledge so it doesn't re-fire on recomposition.
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

    // Rewind confirm dialog — restoring the worktree is destructive of edits made since that turn.
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
