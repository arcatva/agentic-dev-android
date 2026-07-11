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
import androidx.compose.material.icons.rounded.StopCircle
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

/**
 * The core chat/transcript screen. Stateless: all state lives in [SessionViewModel] and is read via
 * `realVm.uiState.collectAsStateWithLifecycle()`; every action calls a VM event. No api/repo/network
 * calls live here.
 *
 * Two intentional chat-rendering upgrades over the old [dev.agentic.ui.SessionScreen]:
 *  1. The [Transcript] uses `reverseLayout` (see Transcript.kt) — no pin/alpha/scrollToItem loop.
 *  2. The input bar renders the instant `s.session != null`, so it no longer "appears last" after the
 *     transcript pins. Its enabled/placeholder/buttons are driven purely by the VM's derived flags.
 *
 * VM creation note (conventions CORRECTION): [appContainer] is @Composable so it is resolved in the
 * body first, then captured into the non-composable `initializer {}`. The nullable [vm] parameter
 * allows injection in tests/previews.
 *
 * DEFERRED (noted in /tmp/mvvm-sdd/ui3-report.md): the in-screen wide workflow rail (single-pane
 * here; workflows open full-screen via [onOpenWorkflows]) and AttachmentNode download/preview.
 *
 * Attachment UPLOAD is wired (06-23): the input bar's attach icon opens a system file picker, picks
 * upload in the background via the VM's `attachFiles`, and submit() composes a `[attached: ...]`
 * marker that the transcript reducer already parses into AttachmentNode cards. The DOWNLOAD side of
 * the round-trip is still pending.
 */
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
                SessionViewModel(container.sessionsRepo, container.workflowsRepo, container.filesRepo, container.api, createSavedStateHandle())
            }
        },
    )
    val s by realVm.uiState.collectAsStateWithLifecycle()
    // The turn the user long-pressed to rewind to; non-null shows the confirm dialog (rendered by the
    // shared SessionDetailOverlays below). Saveable so the dialog survives a rotation.
    var pendingRewindTurn by rememberSaveable { mutableStateOf<Int?>(null) }
    // Discord-style: opening this session acknowledges its current "your turn" point.
    // Keyed on session.unreadEventId so it fires AFTER the session is loaded (the ViewModel
    // LaunchedEffect key fires before uiState emits session → ackEvent would return early).
    LaunchedEffect(s.session?.unreadEventId) {
        val eid = s.session?.unreadEventId ?: return@LaunchedEffect
        realVm.ackEvent(eid)
    }
    // Warm-return self-heal: on every ON_RESUME — the app foregrounded, OR this destination navigated
    // back to from another session — force a fresh transcript reseed from the authoritative log. Without
    // it, a parked AskUserQuestion's picker card that went missing on a background-time reconnect reseed
    // stays gone until the app is killed (the cached flow is never re-loaded on warm re-entry). The
    // destination's own NavBackStackEntry is the LifecycleOwner here, so this also fires on back-nav,
    // not only on app foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { realVm.refresh() }
    val session = s.session

    // Fork navigation: when the VM surfaces a new id, navigate once and clear. forkedTo is a
    // StateFlow (not a plain var), so collecting it here fires reliably — it does NOT depend on the
    // outer composable recomposing (forkState is read in a separate scope below, so the outer body
    // wouldn't recompose on fork success and a plain-var read would be missed). acknowledgeFork
    // resets it to null, which the null-guard ignores (no navigation loop).
    LaunchedEffect(realVm) {
        realVm.forkedTo.collect { id ->
            if (id != null) {
                onForked(id)
                realVm.acknowledgeFork()
            }
        }
    }

    // Outbox download: the VM fetches bytes off this one-shot channel; the screen (which has a
    // Context) writes them to the device's Downloads and toasts the outcome.
    val context = LocalContext.current
    LaunchedEffect(realVm) {
        realVm.downloads.collect { eff ->
            when (eff) {
                is DownloadEffect.Started ->
                    Toast.makeText(context, "Downloading ${eff.name}…", Toast.LENGTH_SHORT).show()
                is DownloadEffect.Ready -> {
                    // The VM streamed into a temp file; copy it into Downloads and ALWAYS drop the temp.
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

    // Fork failures: surface the server's error so a failed fork isn't silent. Without this the
    // Fork button just re-enables and the user can't tell the fork didn't happen (404/409/500/etc.).
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
                    // fillMaxWidth is REQUIRED for the fade: the M3 TopAppBar title slot otherwise lets a
                    // bare wrap-content Column take the prompt's full intrinsic width, so FadingText's
                    // softWrap=false Text never reports hasVisualOverflow (the app bar hard-clips it
                    // externally instead) and the fade never triggers. Filling the (bounded) title width
                    // gives FadingText a real constraint to overflow against — like the weight(1f) the
                    // Home/Workflows/card FadingTexts sit in.
                    //
                    // widthIn(max = 300.dp) keeps the title's right edge well clear of the actions
                    // block (git / workflow / fork buttons, ~150dp wide) so the FadingText's right-edge
                    // gradient lands on the prompt text, not under the buttons. Without the cap, in
                    // landscape the title slot is ~650dp wide and a typical prompt fits without
                    // overflowing — so the fade never fires at all.
                    Column(Modifier.fillMaxWidth().widthIn(max = 300.dp)) {
                        // FadingText (like the Home/Workflows titles): show as much of the prompt as
                        // fits on one line and fade the right edge on overflow, instead of hard-clipping.
                        // No style/color passed, so it inherits the TopAppBar title text style + color.
                        FadingText((session?.prompt ?: "").ifBlank { "Session" })
                        Text(
                            realVm.sessionId.take(8),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Commit history (commit graph) — always available now. When the session is still
                    // running we pass live=true so the graph screen flags that the worktree may change.
                    IconButton(onClick = { onOpenHistory(!s.terminal) }) {
                        Icon(
                            Icons.Rounded.Commit, "commit history",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Workflows open full-screen (the side-by-side rail now lives at the app level
                    // in AdaptiveHome, not in this screen).
                    if (s.hasRuns) {
                        IconButton(onClick = onOpenWorkflows) {
                            Icon(Icons.Rounded.AccountTree, "workflows")
                        }
                    }
                    // Fork — independent session branched off this one. Disabled while the
                    // request is in flight; on success, onForked receives the new id and the
                    // caller navigates to it.
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
                    // Move session to a group.
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

    // Shared chrome: the destructive Rewind confirm dialog + the rewind toast. Identical wiring to the
    // wide WideThreePaneHome right pane (single source of truth).
    SessionDetailOverlays(
        vm = realVm,
        pendingRewindTurn = pendingRewindTurn,
        onDismissRewind = { pendingRewindTurn = null },
    )
}

/**
 * The chat column — the busy indicator, repos row, resume banner, transcript and input bar — as a
 * Scaffold-free composable so it can be hosted either inside the narrow [SessionScreen]'s Scaffold or
 * directly as the right pane of the app-level 3-pane layout (AdaptiveHome). All state comes from [s];
 * every action calls a [realVm] event. The download SAVE effect (which needs a Context) stays in the
 * [SessionScreen] wrapper, not here. By default the root Column applies `.imePadding()` so the input
 * bar lifts above the keyboard. The 3-pane host (AdaptiveHome) sets [applyImePadding] = false and
 * applies the IME inset to the whole pane Row instead, so the splitter and side panes lift too —
 * avoiding double padding.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionContent(
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

    // Tap the transcript / empty composer chrome to blur the chat input and drop the keyboard.
    // Transcript card taps, long-press text selection, and scrolling still win (children consume
    // their pointers first). Covers both the narrow SessionScreen host and the AdaptiveHome chat pane.
    // key(realVm.sessionId) so the entire content subtree is destroyed and rebuilt on session switch.
    // No composable state (AnimatedVisibility, animateContentHeight, MutableTransitionState, etc.)
    // survives across sessions — every animation seeds to its final value and nothing slides.
    key(realVm.sessionId) {
    Column((if (applyImePadding) modifier.imePadding() else modifier).clearFocusOnTap()) {
        // Live indicator — the expressive wavy bar runs while a turn is generating.
        if (s.busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())

        // Info row — the run markers for this session, annotated as chips under the title: ultracode,
        // fork (when branched off a parent), the repos (folder) + skills (✦) it used, model and effort.
        // The fork chip replaces the old standalone "Forked from …" chip; the parent is still reachable
        // through the collapsible Forked-from card below.
        session?.let {
            SessionTagRow(
                it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                onOpenParent = onOpenParent,
            )
        }

        // Error banner — shown for any session that currently carries a (non-benign) error, whether
        // terminal OR idle-streaming. In streaming mode a usage/rate/claude error leaves the session
        // idle (status="running") with errorKind set rather than going "failed", so we surface it via
        // domain.hasError, not from terminal status. Watchdog caps (wall/idle timeout) and a manual
        // Stop are not errors → no banner (the input bar lets the user just type to continue).
        // Captured into a local val so the AnimatedVisibility content (which also renders during the
        // exit animation) keeps a smart-cast non-null session after the error clears.
        val errSession = session
        val errVisible = errSession != null && hasError(errSession.status, errSession.errorKind)
        // Seed the transition state per session so the banner does NOT play its expand-in on a session
        // SWITCH (landing on an already-errored session otherwise slid the transcript down — the "下滑"
        // bug). It still animates when an error appears DURING a session (id stable → state persists).
        // When MutableTransitionState is freshly created (session switch) with initial == target,
        // AnimatedVisibility observes no change and snaps — so the banner lands at its full height
        // in one frame with no slide. In-session state changes animate normally (expandVertically).
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
                        // Recover button for any errored, no-longer-progressing session — terminal (failed/
                        // killed) OR a still-"running" session whose last turn errored and is now idle
                        // (awaitingInput=true), e.g. "Not logged in". Resume continues via --resume; Retry
                        // re-runs the prompt fresh. (The input bar below also stays enabled to just type on.)
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

        // Stuck terminal — a session that ended with no claudeSessionId to --resume and no live process
        // to type into (e.g. the watchdog reaped a turn that stalled before Claude initialized:
        // status="done", no error, no claudeSessionId). Neither the input bar (no canFollowUp) nor the
        // error banner above (no error) shows any button, so offer a Retry that re-runs the original
        // prompt fresh. Gated on !composable so a still-actionable session (e.g. a live workflow) never
        // sees it.
        val stuckSession = session
        val stuckVisible = stuckSession != null && !s.composable &&
            !hasError(stuckSession.status, stuckSession.errorKind) &&
            isStuckTerminal(stuckSession.status, stuckSession.claudeSessionId)
        // Same per-session MutableTransitionState pattern as the error banner — on switch the
        // fresh state (initial == target) snaps to full height with no slide; in-session changes
        // animate the expand normally.
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

        // Forked-from: a collapsible, read-only preview of the parent conversation, shown at the top
        // so a fork makes clear where it branched from. Collapsed by default; the live transcript
        // renders below. Null (nothing shown) unless this session is a fork.
        val parentPreview by realVm.parentPreview.collectAsStateWithLifecycle()
        parentPreview?.let { pp -> ForkedHistoryCard(preview = pp, onOpenParent = onOpenParent) }

        // Transcript — reverseLayout best-practice rendering (Transcript.kt). During the initial
        // connect we show a centered MD3E flywheel instead of the transcript.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // "Loading" = the real conversation hasn't seeded yet. We can NOT key on s.nodes.isEmpty():
            // the outbox poll (pollFlow emits its first block immediately) usually returns the session's
            // download cards BEFORE the transcript stream connects, and interleaveShared(emptyTranscript,
            // shared) then yields a list of lone AttachmentNodes — which made the chat flash a bare
            // "download card" under the fallback title. Key on "still connecting AND nothing but
            // attachment cards so far" so the flywheel covers the whole connect, download cards included.
            val loadingTranscript = s.connecting && s.nodes.all { it is AttachmentNode }
            if (s.loadError && s.session == null) {
                // First load failed (server unreachable on open). Show an error + retry instead of a
                // permanent blank/spinner — realVm.reload() re-runs the fetch on the same cached flow.
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
                // Reveal the flywheel only if the connect is genuinely slow (>150ms) — a fast connect
                // renders the transcript directly, with no loading->content flash.
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

        // Input bar — rendered instantly whenever a session exists (no gating on transcript/pin).
        if (session != null) {
            val ctx = LocalContext.current
            // Captured here (not inside InputBar) so the ContentResolver comes from the SAME context
            // that owns the activity result that produced the URIs — guarantees the read grant is in
            // scope when the VM's upload coroutine opens the stream.
            val onAttachFiles: (List<Uri>) -> Unit = { uris -> realVm.attachFiles(uris, ctx.contentResolver) }
            val mentionCandidates by realVm.mentionCandidates.collectAsStateWithLifecycle()
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
            )
        }
    } // key(realVm.sessionId)
    }
}

/**
 * The chat-input surface: a single rounded surface laid out as a two-row composer — the pending-
 * attachment chips and the full-width text field stack on top, and an action bar sits below holding
 * the attach button (opens the system file picker) and session-settings on the left, then a mic
 * dictation button and send/stop on the right. Stacking the field above its own action bar keeps the
 * chips, the text and the leading icons on one shared left margin, and lets multi-line text use the
 * full width (it grows upward while the action bar stays put) — md3 chat-input style.
 * Purely flag-driven from VM state — no local input-state-machine logic. Attach is gated on
 * [composable] (same gate as Send) because only an actionable session can take the next turn; the
 * pending chips row is shown whenever the user has attachments, regardless of [composable], so a
 * user who attached files and then the session went terminal can still see (and remove) them.
 *
 * Mic dictation: the recognizer + its launcher/listener are created with unconditional `remember`/
 * `DisposableEffect`/`rememberLauncherForActivityResult` (Compose rules-of-hooks) and only used inside
 * the conditional rendering below. Recognized speech is appended to the current input via [onInput].
 */
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
) {
    // All dictation logic (engine choice, permission, first-run model download, live results) lives
    // in this shared controller. On arm64 it uses sherpa-onnx (bilingual zh-en, offline); otherwise
    // it falls back to the platform SpeechRecognizer. Recognized speech is appended to `input`.
    val dict = rememberDictationController(currentText = { input }, onText = onInput)
    // TextFieldState bridge: the composer text lives in the ViewModel's `input` StateFlow, which
    // re-emits on unrelated combine ticks (per-token transcript stream, 2s session poll, 2.5s
    // workflow polls) and is also written by dictation (onText above). A TextFieldState owns text +
    // caret + composing region together, so those re-emits no longer rebuild a stale caret while the
    // user types (the String-overload bug: appended/poll-fed text landing behind a frozen caret).
    val inputState = rememberSyncedTextFieldState(input, onInput)
    // System file picker — Storage Access Framework (SAF) so no runtime permission is needed on any
    // API level we ship (minSdk 26). The contract hands us read-granted URIs scoped to our activity;
    // SessionScreen captures the ContentResolver from the same context and hands both to the VM in
    // attachFiles() so the upload coroutine can openInputStream().
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) onAttachFiles(uris)
    }

    // @-mention state: reading inputState.text/selection here is snapshot-aware, so this block
    // re-evaluates as the user types or moves the caret. A mention is active while the caret sits
    // inside an `@`-word (see activeMentionQuery); the panel shows only when something matches.
    // Gated on !dict.listening like manual editing (readOnly above): while the recognizer streams,
    // every partial result rewrites the whole field through the sync bridge, which would clobber a
    // just-picked mention token — so no picking mid-dictation.
    val mentionActive = !dict.listening && inputState.selection.collapsed &&
        activeMentionQuery(inputState.text.toString(), inputState.selection.end) != null
    val mentionMatches =
        if (!mentionActive) emptyList()
        else activeMentionQuery(inputState.text.toString(), inputState.selection.end)
            ?.let { filterMentionCandidates(mentionCandidates, it.query) }
            .orEmpty()
    // Refresh the candidates list once per activation (not per keystroke) — the panel renders
    // instantly from the VM's last list while the refresh is in flight.
    LaunchedEffect(mentionActive) { if (mentionActive) onMentionActive() }

    Column(Modifier.fillMaxWidth()) {
    if (mentionMatches.isNotEmpty()) {
        MentionCandidatesPanel(
            candidates = mentionMatches,
            onPick = { picked ->
                // Recompute the mention from the CURRENT field state (never a stale composition
                // capture): replace `@<query>` with the literal token and park the caret after it.
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
            // Dictation status: permission prompt, first-run model download %, or an error.
            dict.status?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp),
                )
            }
            // First-run model-download chooser (Standard / High accuracy).
            DictationDownloadDialog(dict)
            // A send failed (e.g. network, or the session couldn't take the turn) — tell the user; the
            // input keeps their text so they can retry. Cleared on the next submit.
            if (queueError != null) {
                Text(
                    queueError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
                )
            }
            // Pending attachment chips — shown whenever the user has picked files, regardless of
            // `composable`, so they can still see (and remove) attachments even if the session goes
            // terminal. Each chip shows the upload state; remove is always available so a stuck
            // upload can be cancelled. The left padding matches the text field below so the chips, the
            // typed text and the leading action icon all sit on the same left margin.
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
            // Two-row composer: the text field gets its own full-width row ABOVE the action bar. This
            // fixes the two reported layout issues — (1) the chips above and the action icons below now
            // share this field's left margin, so attachments / input / controls all line up; and (2)
            // multi-line text uses the FULL width (wraps onto fewer lines) and grows upward while the
            // action row stays pinned at the bottom, instead of competing with the icons for width.
            BasicTextField(
                state = inputState,
                // Lock manual editing while the recognizer streams text in, so keystrokes and the
                // live rewrite don't fight (the "jumping text" bug). Editable again once stopped.
                readOnly = dict.listening,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                // No placeholder text in the input field (per request) — just the cursor when empty.
                // No decorator: a null decorator renders the inner text field directly (a passthrough
                // decorator would only add per-recomposition allocation).
            )
            // Action bar: leading attach + settings, a flexible spacer, then mic / stop / send. The
            // spacer keeps the send cluster hugging the right edge no matter how tall the field grew.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Attach — opens the system file picker. Gated on `composable` (same gate as Send):
                // only an actionable session can take a turn, so picking files into a terminal one
                // would be misleading. The VM keeps attachments alive across composable flips so they
                // re-show when the session resumes.
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
                // ⚙ session settings — opens the SessionSettingsScreen so the user can override
                // model/effort/mode/permissionMode for the NEXT turn. Gated on `composable` (the same
                // gate that exposes Send) because only an actionable session has a "next turn".
                if (composable) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Tune, contentDescription = "session settings")
                    }
                }
                Spacer(Modifier.weight(1f))
                // Mic — shown when a dictation engine is available.
                if (dict.available) {
                    IconButton(onClick = { dict.onMicClick() }) {
                        Icon(
                            if (dict.listening) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (dict.listening) "stop recording" else "voice input",
                            tint = if (dict.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Stop is offered while a turn is actively generating — onStop interrupts the turn
                // (the session stays alive and idle, ready for the next message).
                if (busy) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Icon(Icons.Rounded.StopCircle, "stop") }
                }
                if (composable) {
                    FilledIconButton(
                        // Sending auto-ends an in-progress dictation (and mutes its trailing result so
                        // it can't repopulate the field after submit clears it).
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

/**
 * Save [file]'s contents as [name] into the device's public Downloads — a streamed 64 KB-chunk
 * copy, so a 44 MB APK never materializes as one ByteArray. API 29+ uses MediaStore (no runtime
 * permission needed); older devices fall back to the app-specific external Downloads dir (also no
 * permission). Returns true on success. Call off the main thread (it does blocking IO). The caller
 * owns [file]'s lifetime (delete it after this returns).
 */
internal fun saveToDownloads(context: Context, name: String, file: File): Boolean {
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

/** Best-effort MIME type from a filename extension — drives how Downloads/installers treat the file
 *  (notably .apk → the package-installer MIME). Falls back to a generic binary type. */
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

/**
 * Collapsible, read-only card showing the parent (forked-from) conversation at the top of a fork.
 * Collapsed by default (a one-line header); expanding reveals the parent's user/assistant messages
 * in a bounded, internally-scrolling column so it never fights the live transcript's scroll. The
 * full parent (with tool calls etc.) is one tap away via "Open full conversation".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ForkedHistoryCard(preview: ForkParentPreview, onOpenParent: (String) -> Unit) {
    var expanded by rememberSaveable(preview.parentId) { mutableStateOf(false) }
    // Expressive motion specs for the expand — the SAME spring family WorkflowScreen's per-run accordion
    // uses, so the two reveals feel consistent. expandFrom/shrinkTowards Top anchors the body under the
    // header so it unrolls downward instead of snapping in.
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
