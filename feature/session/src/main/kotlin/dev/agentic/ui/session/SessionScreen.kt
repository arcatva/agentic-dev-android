package dev.agentic.ui.session

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.data.net.Session
import dev.agentic.di.appContainer
import dev.agentic.domain.AttachmentNode
import dev.agentic.domain.PendingAttachment
import dev.agentic.domain.RecoverAction
import dev.agentic.domain.UploadState
import dev.agentic.domain.hasError
import dev.agentic.domain.isStuckTerminal
import dev.agentic.domain.recoverAction
import dev.agentic.domain.stopReason
import dev.agentic.ui.FadingText
import dev.agentic.ui.appEffectsSpec
import dev.agentic.ui.appSpatialSpec
import dev.agentic.ui.components.AttachmentChip
import dev.agentic.ui.components.clearFocusOnTap
import dev.agentic.ui.components.DictationDownloadDialog
import dev.agentic.ui.components.rememberDictationController
import dev.agentic.ui.components.rememberSyncedTextFieldState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chat/transcript screen. Stateless; all state from [SessionViewModel]. Transcript uses `reverseLayout` (see Transcript.kt). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionScreen(
    onBack: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onOpenHistory: (live: Boolean) -> Unit,
onFork: () -> Unit,
    onForked: (newId: String) -> Unit,
    onOpenParent: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onMoveToGroup: () -> Unit = {},
    vm: SessionViewModel? = null,
) {
    val container = appContainer()
    val realVm: SessionViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer {
                SessionViewModel(
                    container.sessionsRepo,
                    container.workflowsRepo,
                    container.filesRepo,
                    createSavedStateHandle(),
                )
            }
        },
    )
    val s by realVm.uiState.collectAsStateWithLifecycle()
    // Saveable so the rewind dialog survives rotation.
    var pendingRewindTurn by rememberSaveable { mutableStateOf<Int?>(null) }
    // Keyed on unreadEventId (not session.id) so ack fires AFTER session loads; VM LaunchedEffect keyed on id races the uiState emit.
    LaunchedEffect(s.session?.unreadEventId) {
        val eid = s.session?.unreadEventId ?: return@LaunchedEffect
        realVm.ackEvent(eid)
    }
    // Warm-return self-heal: reseed from authoritative log on ON_RESUME (back-nav too, NavBackStackEntry is the LifecycleOwner).
    // Without this, a parked AskUserQuestion picker lost on a background-time reconnect reseed stays gone until app kill.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { realVm.refresh() }
    val session = s.session

    // forkedTo is a StateFlow (not a plain var) so collection fires reliably even when the outer body doesn't recompose.
    LaunchedEffect(realVm) {
        realVm.forkedTo.collect { id ->
            if (id != null) {
                onForked(id)
                realVm.acknowledgeFork()
            }
        }
    }

    // VM owns network fetch; View (with Context) writes to Downloads and toasts the outcome.
    val context = LocalContext.current
    LaunchedEffect(realVm) {
        realVm.downloads.collect { eff ->
            when (eff) {
                is DownloadEffect.Started ->
                    Toast.makeText(context, "Downloading ${eff.name}…", Toast.LENGTH_SHORT).show()
                is DownloadEffect.Ready -> {
                    // VM streamed to temp; copy into Downloads and ALWAYS delete the temp.
                    val ok = withContext(Dispatchers.IO) {
                        try { saveToDownloads(context, eff.name, eff.file) } finally { eff.file.delete() }
                    }
                    Toast.makeText(
                        context,
                        if (ok) "Saved to Downloads: ${eff.name}" else "Couldn't save ${eff.name}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is DownloadEffect.Failed ->
                    Toast.makeText(context, "Download failed: ${eff.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Surface fork failures — without this a re-enabled Fork button masks 404/409/500.
    LaunchedEffect(realVm) {
        realVm.forkState.collect { st ->
            if (st is ForkState.Failed) {
                Toast.makeText(context, "Couldn't fork: ${st.message}", Toast.LENGTH_LONG).show()
                realVm.acknowledgeForkError()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "back")
                    }
                },
                title = {
                    // fillMaxWidth + widthIn(max=300dp): gives FadingText a real overflow constraint.
                    // TopAppBar's bare title slot is ~650dp wide in landscape so a typical prompt fits
                    // without ever overflowing → fade never fires.
                    Column(Modifier.fillMaxWidth().widthIn(max = 300.dp)) {
                        FadingText((session?.prompt ?: "").ifBlank { "Session" })
                        Text(
                            realVm.sessionId.take(8),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // live=true while not terminal so the graph screen warns the worktree may change.
                    IconButton(onClick = { onOpenHistory(!s.terminal) }) {
                        Icon(
                            Icons.Rounded.Commit, "commit history",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (s.hasRuns) {
                        IconButton(onClick = onOpenWorkflows) {
                            Icon(Icons.Rounded.AccountTree, "workflows")
                        }
                    }
                    val forkState by realVm.forkState.collectAsStateWithLifecycle()
                    IconButton(
                        onClick = onFork,
                        enabled = forkState !is ForkState.InFlight,
                    ) {
                        Icon(
                            Icons.Rounded.CallSplit,
                            contentDescription = "fork session",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onMoveToGroup) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = "move to group",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { pad ->
        SessionContent(
            s = s,
            realVm = realVm,
onOpenParent = onOpenParent,
            onOpenSettings = onOpenSettings,
            onRewind = { turn -> pendingRewindTurn = turn },
            modifier = Modifier.fillMaxSize().padding(pad),
        )
    }

    // Shared chrome with the wide WideThreePaneHome right pane (single source of truth for rewind).
    SessionDetailOverlays(
        vm = realVm,
        pendingRewindTurn = pendingRewindTurn,
        onDismissRewind = { pendingRewindTurn = null },
    )
}

/** Chat column (Scaffold-free) hostable by narrow [SessionScreen] or the 3-pane AdaptiveHome right pane. Set [applyImePadding]=false to apply IME inset at the pane row instead. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionContent(
    s: SessionUiState,
    realVm: SessionViewModel,
    onOpenWorkflow: (dev.agentic.domain.WorkflowNode) -> Unit = {},
onOpenParent: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Long-press a prompt bubble → rewind code to before that turn. Default no-op (wide layout). */
    onRewind: (turnIndex: Int) -> Unit = {},
    applyImePadding: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val session = s.session
    val dl by realVm.downloadProgress.collectAsStateWithLifecycle()

    // key(realVm.sessionId) so the entire content subtree is destroyed+rebuilt on session switch —
    // AnimatedVisibility/animateContentHeight/MutableTransitionState must not slide across switches.
    key(realVm.sessionId) {
    Column((if (applyImePadding) modifier.imePadding() else modifier).clearFocusOnTap()) {
        if (s.busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())

        session?.let {
            SessionTagRow(
                it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                onOpenParent = onOpenParent,
            )
        }

        // domain.hasError surfaces streaming errors too (status="running" + errorKind set); watchdog caps and manual Stop are not errors.
        val errSession = session
        val errVisible = errSession != null && hasError(errSession.status, errSession.errorKind)
        // Seed per-session so a freshly-created state with initial==target snaps with no slide on session switch.
        val errState = remember(session?.id) { MutableTransitionState(errVisible) }
        errState.targetState = errVisible
        AnimatedVisibility(
            visibleState = errState,
            enter = expandVertically(animationSpec = appSpatialSpec()) + fadeIn(animationSpec = appEffectsSpec()),
            exit = shrinkVertically(animationSpec = appSpatialSpec()) + fadeOut(animationSpec = appEffectsSpec()),
        ) {
            if (errSession != null) {
                val recover = recoverAction(errSession.status, errSession.claudeSessionId)
                OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stopReason(errSession.errorKind, errSession.error, errSession.status),
                            fontWeight = FontWeight.SemiBold,
                        )
                        errSession.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Resume reuses claudeSessionId via --resume; Retry re-runs the prompt fresh.
                        if (recover != RecoverAction.NONE) {
                            val retrying = recover == RecoverAction.RETRY
                            Button(
                                onClick = { if (retrying) realVm.retry(errSession.prompt) else realVm.resume() },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Icon(if (retrying) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow, null)
                                Text(if (retrying) " Retry" else " Resume")
                            }
                        }
                    }
                }
            }
        }

        // Gated on !composable so a still-actionable session (e.g. live workflow) never sees it.
        val stuckSession = session
        val stuckVisible = stuckSession != null && !s.composable &&
            !hasError(stuckSession.status, stuckSession.errorKind) &&
            isStuckTerminal(stuckSession.status, stuckSession.claudeSessionId)
        val stuckState = remember(session?.id) { MutableTransitionState(stuckVisible) }
        stuckState.targetState = stuckVisible
        AnimatedVisibility(
            visibleState = stuckState,
            enter = expandVertically(animationSpec = appSpatialSpec()) + fadeIn(animationSpec = appEffectsSpec()),
            exit = shrinkVertically(animationSpec = appSpatialSpec()) + fadeOut(animationSpec = appEffectsSpec()),
        ) {
            if (stuckSession != null) {
                OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("This turn stopped before it could continue.", fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = { realVm.retry(stuckSession.prompt) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, null)
                            Text(" Retry")
                        }
                    }
                }
            }
        }

        val parentPreview by realVm.parentPreview.collectAsStateWithLifecycle()
        parentPreview?.let { pp -> ForkedHistoryCard(preview = pp, onOpenParent = onOpenParent) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Don't key on s.nodes.isEmpty(): outbox poll fires before the transcript stream, so the
            // first frame can show lone AttachmentNodes — flash a bare "download card" under the fallback title.
            val loadingTranscript = s.connecting && s.nodes.all { it is AttachmentNode }
            if (s.loadError && s.session == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Couldn't load this conversation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = realVm::reload) { Text("Retry") }
                    }
                }
            } else if (loadingTranscript) {
                // 150ms threshold: a fast connect renders the transcript directly, no loading->content flash.
                val showSpinner by produceState(initialValue = false, loadingTranscript) {
                    value = false
                    delay(150)
                    value = true
                }
                if (showSpinner) Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LoadingIndicator()
                        Text(
                            "Loading conversation…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Transcript(
                    nodes = s.nodes,
                    onAnswerAsk = realVm::answerAsk,
                    onRespondPermission = { id, decision, feedback -> realVm.respondPermission(id, decision, feedback) },
                    onDownloadAttachment = realVm::downloadAttachment,
                    downloadProgress = dl,
                    canAnswer = s.composable,
                    onOpenWorkflow = onOpenWorkflow,
                    loadImageBytes = realVm::attachmentBytes,
                    onRewind = onRewind,
                    hasMore = s.hasMore,
                    loadingEarlier = s.loadingEarlier,
                    onLoadEarlier = { realVm.loadEarlier() },
                    hasNewer = s.hasNewer,
                    loadingNewer = s.loadingNewer,
                    onLoadNewer = { realVm.loadNewer() },
                    latestEventId = s.latestEventId,
                    firstEventLine = s.firstEventLine,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (session != null) {
            val ctx = LocalContext.current
            // ContentResolver from the SAME context that produced the URIs — read grant is in scope for the upload stream.
            val onAttachFiles: (List<Uri>) -> Unit = { uris -> realVm.attachFiles(uris, ctx.contentResolver) }
            val mentionCandidates by realVm.mentionCandidates.collectAsStateWithLifecycle()
            val commandCandidates by realVm.commandCandidates.collectAsStateWithLifecycle()
            InputBar(
                input = s.input,
                onInput = realVm::onInput,
                onSubmit = realVm::submit,
                onStop = realVm::stop,
                busy = s.busy,
                composable = s.composable,
                canSend = s.canSend,
                streaming = s.streaming,
                workflowActive = s.workflowActive,
                queueError = s.queueError,
                onOpenSettings = onOpenSettings,
                attachments = s.attachments,
                onAttachFiles = onAttachFiles,
                onRemovePending = realVm::removePending,
                mentionCandidates = mentionCandidates,
                onMentionActive = realVm::refreshMentionCandidates,
                commandCandidates = commandCandidates,
                onCommandsNeeded = realVm::refreshCommands,
            )
        }
    } // key(realVm.sessionId)
    }
}

/** Two-row composer surface: pending chips + full-width text field stack above, action bar (attach/settings/mic/send-stop) below. Flag-driven from VM state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputBar(
    input: String,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    composable: Boolean,
    canSend: Boolean,
    streaming: Boolean,
    workflowActive: Boolean,
    queueError: String? = null,
    onOpenSettings: () -> Unit = {},
    attachments: List<PendingAttachment> = emptyList(),
    onAttachFiles: (List<Uri>) -> Unit = {},
    onRemovePending: (String) -> Unit = {},
    mentionCandidates: List<Session> = emptyList(),
    onMentionActive: () -> Unit = {},
    commandCandidates: List<dev.agentic.ui.components.CommandItem> = emptyList(),
    onCommandsNeeded: () -> Unit = {},
) {
    // Dictation lives in a shared controller; arm64 → sherpa-onnx offline, else platform SpeechRecognizer.
    val dict = rememberDictationController(currentText = { input }, onText = onInput)
    // TextFieldState owns text + caret + composing together, so unrelated VM re-emits (poll ticks, dictation writes) don't rebuild a stale caret behind the user's typing.
    val inputState = rememberSyncedTextFieldState(input, onInput)
    // SAF picker: no runtime permission needed; URIs are read-granted only for our activity.
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onAttachFiles(uris)
    }

    // Block @-mention picking while the recognizer streams — each partial result rewrites the field through the sync bridge and would clobber a just-picked token.
    val mentionActive = !dict.listening && inputState.selection.collapsed &&
        activeMentionQuery(inputState.text.toString(), inputState.selection.end) != null
    val mentionMatches =
        if (!mentionActive) emptyList()
        else activeMentionQuery(inputState.text.toString(), inputState.selection.end)
            ?.let { filterMentionCandidates(mentionCandidates, it.query) }
            .orEmpty()
    // Refresh once per activation, not per keystroke; panel renders instantly from the VM's last list.
    LaunchedEffect(mentionActive) { if (mentionActive) onMentionActive() }

    // `/`-command palette — active only when the message STARTS with a command being typed.
    val commandActive = !dict.listening && inputState.selection.collapsed &&
        dev.agentic.ui.components.activeCommandQuery(inputState.text.toString(), inputState.selection.end) != null
    val commandMatches =
        if (!commandActive) emptyList()
        else dev.agentic.ui.components.activeCommandQuery(inputState.text.toString(), inputState.selection.end)
            ?.let { dev.agentic.ui.components.filterCommands(commandCandidates, it.query) }
            .orEmpty()
    LaunchedEffect(commandActive) { if (commandActive) onCommandsNeeded() }

    Column(Modifier.fillMaxWidth()) {
    if (commandMatches.isNotEmpty()) {
        dev.agentic.ui.components.CommandPalette(
            candidates = commandMatches,
            onPick = { picked ->
                val caret = inputState.selection.end
                dev.agentic.ui.components.activeCommandQuery(inputState.text.toString(), caret)?.let { q ->
                    val (newText, newCaret) = dev.agentic.ui.components.applyCommand(
                        inputState.text.toString(), q, picked.name,
                    )
                    inputState.edit {
                        replace(0, length, newText)
                        selection = TextRange(newCaret)
                    }
                }
            },
        )
    }
    if (mentionMatches.isNotEmpty()) {
        MentionCandidatesPanel(
            candidates = mentionMatches,
            onPick = { picked ->
                // Recompute from CURRENT field state — never a stale composition capture.
                val caret = inputState.selection.end
                activeMentionQuery(inputState.text.toString(), caret)?.let { m ->
                    val token = mentionToken(picked.id)
                    inputState.edit {
                        replace(m.start, caret, token)
                        selection = TextRange(m.start + token.length)
                    }
                }
            },
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (dict.downloading) LinearProgressIndicator(
                progress = { dict.downloadProgress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) else if (dict.listening) LinearWavyProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
            // Dictation status: permission prompt, first-run download %, or error.
            dict.status?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
                )
            }
            DictationDownloadDialog(dict)
            // queueError keeps the input text so the user can retry; cleared on next submit.
            if (queueError != null) {
                Text(
                    queueError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
                )
            }
            // Chips show regardless of `composable` so a terminal session still lets the user see/remove attachments.
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(attachments, key = { it.id }) { att ->
                        AttachmentChip(att = att, onRemove = { onRemovePending(att.id) })
                    }
                }
            }
            BasicTextField(
                state = inputState,
                // readOnly while listening: keystrokes would fight the live rewrite ("jumping text" bug).
                readOnly = dict.listening,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                // No decorator: a null decorator renders the inner field directly without per-recomposition allocation.
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Attach gated on `composable` (same gate as Send) — VM keeps attachments alive across composable flips.
                IconButton(
                    onClick = { attachLauncher.launch(arrayOf("*/*")) },
                    enabled = composable,
                ) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = "attach file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (composable) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Tune, contentDescription = "session settings")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (dict.available) {
                    IconButton(onClick = { dict.onMicClick() }) {
                        Icon(
                            if (dict.listening) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (dict.listening) "stop recording" else "voice input",
                            tint = if (dict.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Stop interrupts the turn; session stays alive (not kill()).
                if (busy) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Icon(Icons.Rounded.Stop, "stop") }
                }
                if (composable) {
                    FilledIconButton(
                        // stopForSend mutes the trailing partial so it can't repopulate after submit clears the field.
                        onClick = { dict.stopForSend(); if (canSend) onSubmit() },
                        enabled = canSend,
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, "send") }
                }
            }
        }
    }
    } // mention panel + composer column
}

/** Streamed 64 KB-chunk copy to device Downloads (MediaStore on API 29+, app-external dir on older). Caller owns [file]'s lifetime; call off the main thread. */
fun saveToDownloads(context: Context, name: String, file: File): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val pending = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: return false
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null,
            )
            true
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
            file.inputStream().use { input ->
                File(dir, name).outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            true
        }
    } catch (e: Exception) {
        false
    }
}

/** Best-effort MIME from filename extension; falls back to binary octet-stream. */
private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "apk" -> "application/vnd.android.package-archive"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "txt", "log" -> "text/plain"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

/** Collapsible read-only card showing the parent (forked-from) conversation at the top of a fork. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ForkedHistoryCard(preview: ForkParentPreview, onOpenParent: (String) -> Unit) {
    var expanded by rememberSaveable(preview.parentId) { mutableStateOf(false) }
    // Top-anchored expand so the body unrolls downward, matching WorkflowScreen's per-run accordion.
    val motion = MaterialTheme.motionScheme
    val sizeSpec = remember(motion) { motion.defaultSpatialSpec<IntSize>() }
    val fadeSpec = remember(motion) { motion.defaultEffectsSpec<Float>() }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.CallSplit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Forked from ${preview.title}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (expanded) "Hide previous conversation"
                    else "Show previous conversation · ${preview.messages.size} messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "collapse" else "expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = sizeSpec, expandFrom = Alignment.Top) +
                fadeIn(animationSpec = fadeSpec),
            exit = shrinkVertically(animationSpec = sizeSpec, shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = fadeSpec),
        ) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider()
                if (preview.messages.isEmpty()) {
                    Text(
                        "No earlier messages to show.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        preview.messages.forEach { m ->
                            Column {
                                Text(
                                    if (m.fromUser) "You" else "Assistant",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (m.fromUser) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondary,
                                )
                                Text(m.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(
                            onClick = { onOpenParent(preview.parentId) },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Open full conversation") }
                    }
                }
            }
        }
    }
}
