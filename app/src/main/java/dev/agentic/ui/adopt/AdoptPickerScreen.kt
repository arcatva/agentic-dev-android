package dev.agentic.ui.adopt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.Adoptable
import dev.agentic.di.appContainer
import dev.agentic.domain.relativeAge
import kotlinx.coroutines.delay

/**
 * Modal bottom sheet showing the Claude Code sessions the server can adopt. Closed by [onDismiss].
 *
 * On a successful adopt the picker writes the new id to [AdoptPickerUiState.adoptedId]. The screen
 * listens for that emission and invokes [onAdopted] — a one-shot, then [AdoptPickerViewModel.acknowledgeAdopt]
 * clears it (guard against re-firing on recomposition). [onAdopted] navigates to the new session.
 *
 * Scoped VM via `viewModel(key = "adoptPicker", factory = ...)` so a fresh sheet owns a fresh VM
 * instance (and the GET fires once per open).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AdoptPickerSheet(
    onDismiss: () -> Unit,
    onAdopted: (String) -> Unit,
) {
    val container = appContainer()
    val vm: AdoptPickerViewModel = viewModel(
        key = "adoptPicker",
        factory = viewModelFactory {
            initializer { AdoptPickerViewModel(container.sessionsRepo) }
        },
    )
    val s by vm.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

    // First composition → fire the GET. Re-runs only on viewModel identity change (practically never).
    LaunchedEffect(vm) { vm.load() }

    // The VM outlives this sheet (scoped to the Home back-stack entry), so cancel in-flight work and
    // clear the one-shot adoptedId when the sheet is dismissed — otherwise an adopt that completes
    // after dismiss would auto-navigate the next time the sheet opens.
    DisposableEffect(vm) { onDispose { vm.cancelAll() } }

    // Honor a successful adopt with a navigation; then ack so we don't re-navigate on recomposition.
    LaunchedEffect(s.adoptedId) {
        val id = s.adoptedId ?: return@LaunchedEffect
        // Small grace period so any in-flight close animation finishes before we lose our parent.
        delay(150)
        onAdopted(id)
        vm.acknowledgeAdopt()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
        ) {
            // ── Header ──────────────────────────────────────────────────────────────────────
            Text(
                "Adopt Claude Code session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Import a session from the Claude Code CLI on this machine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))

            // ── Body ───────────────────────────────────────────────────────────────────────
            when {
                s.loading -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        // Expressive LoadingIndicator — the app-wide screen-level loading spinner.
                        LoadingIndicator()
                    }
                }
                s.error != null && s.items.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                s.error ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { vm.load() }) {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
                s.items.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        Text(
                            "No Claude Code sessions found on this machine.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        // An adopt() failure keeps the candidate list visible with an inline banner,
                        // instead of replacing the whole list with the full-screen error state (which
                        // is reserved for a load failure — items empty).
                        s.error?.let { err ->
                            item {
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(s.items, key = { it.sessionId }) { item ->
                            AdoptRow(
                                item = item,
                                now = System.currentTimeMillis(),
                                isAdopting = s.adoptingCsid == item.sessionId,
                                onTap = { vm.adopt(item) },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One Claude Code session row in the picker. Tapping anywhere POSTs /api/sessions/adopt for this
 * candidate. Disabled visuals (reduced alpha) when [item.resumable] == false instead of a hard
 * disable — the user can still adopt, just as a read-only server session; the helper text below
 * the prompt spells that out.
 */
@Composable
private fun AdoptRow(
    item: Adoptable,
    now: Long,
    isAdopting: Boolean,
    onTap: () -> Unit,
) {
    val muted = !item.resumable
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isAdopting, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // State dot: resumable (resumable CAN be resumed when adopted) vs read-only.
        Surface(
            color = if (muted)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(10.dp),
            content = {},
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (item.firstPrompt.isNotBlank()) item.firstPrompt else "(no prompt)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.6f else 1f),
                maxLines = 2,
            )
            Text(
                buildString {
                    append(item.cwd)
                    if (item.mtimeMs > 0.0) {
                        append(" · ")
                        append(relativeAge(item.mtimeMs.toLong(), now))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (muted) {
                Text(
                    "Read-only — log ended without a terminal status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isAdopting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Spacer(Modifier.width(8.dp))
            Text(
                "Adopt",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
