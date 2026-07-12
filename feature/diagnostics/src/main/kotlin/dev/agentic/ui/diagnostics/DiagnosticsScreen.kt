package dev.agentic.ui.diagnostics

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.di.appContainer
import dev.agentic.ui.components.AppTextField
import dev.agentic.ui.components.SectionCard
import dev.agentic.ui.components.cardFieldColors
import dev.agentic.ui.home.SessionRow
import kotlinx.coroutines.launch

/**
 * Diagnostics / logs screen: tail of rolling logcat, capture on/off, crash count, refresh,
 * share (zip via system share sheet), and clear actions. Reached from the Home top bar; also
 * opened from the next-launch crash prompt.
 *
 * [onOpenSession] navigates to a session; fired after a successful attach+send so the user lands
 * on the session and sees the log-bundle message arrive.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
) {
    val container = appContainer()
    val vm: DiagnosticsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DiagnosticsViewModel(
                    container.logStore,
                    container.logcatCollector,
                    container.filesRepo,
                    container.sessionsRepo,
                )
            }
        },
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Clear logs?") },
            text = { Text("This permanently deletes the captured logs and crash reports.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                title = { Text("Diagnostics & logs") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                    IconButton(
                        onClick = { vm.showPicker() },
                        enabled = s.attachingSessionId == null,
                    ) {
                        if (s.attachingSessionId != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.AttachFile, "Attach logs to session")
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
                    IconButton(onClick = { scope.launch { shareLogs(context, vm) } }) {
                        Icon(Icons.Rounded.Share, "Share logs")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteOutline, "Clear")
                    }
                },
            )
        },
    ) { pad ->
        LaunchedEffect(Unit) {
            vm.toast.collect { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        LaunchedEffect(Unit) {
            vm.openSession.collect { id -> onOpenSession(id) }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            // Single switch turns on verbose capture across the app (network, streaming, lifecycle,
            // flow churn) AND mirrors logcat to a file.
            SectionCard(
                title = "Verbose logging",
                modifier = Modifier.padding(16.dp),
                trailing = {
                    Switch(
                        checked = s.captureEnabled,
                        onCheckedChange = { vm.setCaptureEnabled(it) },
                    )
                },
            ) {
                Text(
                    "Capture detailed network, streaming and lifecycle logs. Turn on, reproduce the issue, then Share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.crashCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Warning, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${s.crashCount} crash report" + (if (s.crashCount > 1) "s" else "") + " captured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            when {
                s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                s.logText.isBlank() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "No logs captured yet. Turn on Verbose logging above, reproduce the issue, then Share.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                else -> {
                    val vScroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    // Jump to the newest lines whenever captured text changes (best-effort).
                    // yield() lets layout pass run first so maxValue reflects the new text.
                    LaunchedEffect(s.logText) {
                        kotlinx.coroutines.yield()
                        runCatching { vScroll.scrollTo(vScroll.maxValue) }
                    }
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            s.logText,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScroll)
                                .horizontalScroll(hScroll)
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }

    // Sheet rendered OUTSIDE the Scaffold so it overlays the full screen.
    if (s.showSessionPicker) {
        SessionPickerSheet(
            sessions = s.sessions,
            loading = s.loadingSessions,
            crashCount = s.crashCount,
            bundleBytes = s.bundleBytes,
            onDismiss = { vm.dismissPicker() },
            onSessionPicked = { id, message -> vm.attachToSession(id, message) },
        )
    }
}

/** "2.3 MB" / "412 kB" / "97 B" — compact human size. Locale.US keeps the decimal separator stable. */
private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} kB"
    else -> "$bytes B"
}

/**
 * Full-screen picker: bundle summary, pre-filled message (editable), session list — tap sends the
 * bundle + message there. [Dialog] over ModalBottomSheet for max API compatibility (sheet failed
 * to render on some devices — see f72f17e).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionPickerSheet(
    sessions: List<dev.agentic.data.net.Session>,
    loading: Boolean,
    crashCount: Int,
    bundleBytes: Long,
    onDismiss: () -> Unit,
    onSessionPicked: (String, String) -> Unit,
) {
    var message by remember(crashCount) {
        mutableStateOf(
            if (crashCount > 0)
                "Attached the app's diagnostic logs (logcat + $crashCount crash report" +
                    (if (crashCount > 1) "s" else "") + "). Please investigate the crash."
            else
                "Attached the app's diagnostic logs (logcat capture). Please take a look.",
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows=false: take over inset handling below. Default (true) sets
        // softInputMode to UNSPECIFIED → adjustPan for Compose dialogs, IME covers session list
        // instead of shrinking it. No-op on targetSdk 35+ (always edge-to-edge) so we must pad for
        // status/nav bars ourselves either way.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // systemBarsPadding + imePadding: header clear of status bar, list clear of nav bar;
            // column shrinks above keyboard, keeping session rows tappable while message focused.
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, "Close")
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("Send logs to a session", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "The agent gets the bundle as an attachment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Bundle summary — shapes.medium matches the app's section-card corner treatment.
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Rounded.FolderZip, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp).size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Diagnostics bundle", style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildString {
                                    if (crashCount > 0) {
                                        append("$crashCount crash report")
                                        if (crashCount > 1) append("s")
                                        append(" · ")
                                    }
                                    append("logcat capture · ~${humanBytes(bundleBytes)}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (crashCount > 0) {
                            Icon(
                                Icons.Rounded.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // Message sent alongside the attachment (editable) — shared field family.
                AppTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = "Message to the agent",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.small,
                    colors = cardFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Text(
                    "Send to",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No sessions",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(sessions, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    now = now,
                                    openHighlight = false,
                                    inSelectionMode = false,
                                    checked = false,
                                    onClick = { onSessionPicked(session.id, message) },
                                    onLongClick = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Builds the export zip and fires the system share sheet via [FileProvider]. Temporary read grant
 *  ([Intent.FLAG_GRANT_READ_URI_PERMISSION]) lets the chosen app read the zip. */
private suspend fun shareLogs(context: Context, vm: DiagnosticsViewModel) {
    runCatching {
        val zip = vm.buildExport()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "agentic-dev logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
