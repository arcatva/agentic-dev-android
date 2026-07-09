package dev.agentic.ui.nav

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.tween
import dev.agentic.ui.AppMotion
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import dev.agentic.data.log.AppLog
import dev.agentic.di.appContainer
import dev.agentic.ui.adopt.AdoptPickerSheet
import dev.agentic.ui.diagnostics.DiagnosticsScreen
import dev.agentic.ui.globalsettings.GlobalSettingsScreen
import dev.agentic.ui.home.HomeAdaptive
import dev.agentic.ui.home.isWideHome
import dev.agentic.ui.providers.ProvidersScreen
import dev.agentic.ui.login.LoginScreen
import dev.agentic.ui.newrequest.NewRequestScreen
import dev.agentic.ui.session.SessionSettingsScreen
import dev.agentic.ui.tree.CommitGraphScreen
import dev.agentic.ui.tree.FileDiffScreen
import dev.agentic.ui.workflow.WorkflowScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// ── Type-safe route objects ──────────────────────────────────────────────────

@Serializable object Login
@Serializable object Home
@Serializable object NewRequest
@Serializable data class Session(val id: String, val initialPrompt: String? = null)
@Serializable data class Workflow(val id: String)
@Serializable data class History(val id: String, val live: Boolean = false)
// Named *Route to avoid clashing with the data.net.FileDiff model. repo/sha/path carry defaults so
// they ride as query params (URL-encoded) — `path` may contain slashes, which a path-segment arg
// would break.
@Serializable data class FileDiffRoute(val id: String, val repo: String = "", val sha: String = "", val path: String = "")
@Serializable data class SessionSettings(val id: String)
@Serializable object Diagnostics
@Serializable object Providers
@Serializable object GlobalSettings

// ── NavHost ──────────────────────────────────────────────────────────────────

/**
 * Single type-safe [NavHost] wiring all new stateless screens.
 *
 * Auth redirect: [AppContainer.authRepo.isLoggedIn] is observed; any transition to false
 * (logout or 401 → token cleared) immediately pops everything and navigates to [Login].
 *
 * Start destination: determined at composition time from the current [isLoggedIn] snapshot —
 * if already logged in, the user lands on [Home]; otherwise on [Login].
 *
 * Deep link: the Session route accepts `agentic://session/{id}` via [navDeepLink] with the
 * generic basePath form introduced in Navigation-Compose 2.8.0. The NavController picks up
 * deep-link intents automatically from the Activity's intent / onNewIntent (Navigation's
 * built-in handling; MainActivity sets the intent via setIntent in onNewIntent).
 *
 * Id-for-nav in Session: [backStackEntry.toRoute<Session>().id] extracts the typed session id
 * from the back-stack entry for use in the [onOpenWorkflows] and [onOpenHistory] lambdas without
 * touching SavedStateHandle. SessionViewModel independently reads the same id from its own
 * SavedStateHandle (key "id") populated by Navigation-Compose from the typed route args.
 */
// ── Screen transitions: a clean full-width horizontal slide (no cross-fade) ──────────
// Forward: the new screen slides in from the right while the old slides out to the left;
// back/pop reverses it. NO fadeIn/fadeOut on purpose: a cross-fade leaves both screens
// momentarily semi-transparent, which let the (light) window background flash through on
// our dark app — the "转场闪一下" bug. A full-width slide keeps both screens fully opaque
// and tiling the viewport at every frame, so nothing flashes. ~300ms standard MD3 motion.
private const val NAV_MOTION_MS = AppMotion.DurationNav
private val navEasing = AppMotion.Emphasized

/**
 * True for the routes that render the full-screen adaptive Home ([Home] and [Session]). In wide
 * mode a transition between two of them is a Home→Home slide of visually identical screens — the
 * exact animation the "two-layer Home" bug shows — so the NavHost transition lambdas snap
 * (no animation) instead. This also makes the render-time normalization in `composable<Session>`
 * invisible: the duplicate entry is swapped out without a slide.
 */
private fun NavDestination.isHomeFamily(): Boolean =
    hasRoute(Home::class) || hasRoute(Session::class)

@Composable
fun AppNav() {
    val container = appContainer()
    val nav = rememberNavController()
    val loggedIn by container.authRepo.isLoggedIn.collectAsStateWithLifecycle()

    // Whether the window currently uses the wide 3-pane Home. Same rule [HomeAdaptive] uses, so the
    // navigation decision below stays in lock-step with what actually renders. Recomputed on config
    // changes (rotation / fold), so [openSessionAdaptive] and the effects keyed on it stay fresh.
    val wide = isWideHome(LocalConfiguration.current)

    /**
     * Open (select) a session, choosing the arrangement-correct path:
     *  - wide → hand the id to the single existing [Home] via [AppContainer.homeSelectRequest] and
     *    reveal it (`launchSingleTop` reuses the existing Home entry — no list reload). We do NOT push
     *    a [Session] route, because in wide mode that renders a *second* full-screen Home and stacks
     *    it on the back stack (the "two-layer Home" bug).
     *  - narrow → push the [Session] route as before (there it's a distinct detail screen with the
     *    pane scaffold's own Back-to-list), popping anything above [Home].
     */
    fun openSessionAdaptive(id: String, prompt: String? = null) {
        if (id.isBlank()) return
        if (wide) {
            container.homeSelectRequest.value = id
            nav.navigate(Home) {
                popUpTo<Home>()        // drop NewRequest / any screen above Home, keep the Home entry
                launchSingleTop = true // reuse the existing Home instead of stacking a new one
            }
        } else {
            nav.navigate(Session(id, prompt)) {
                popUpTo<Home>()
                launchSingleTop = true
            }
        }
    }

    // ND-1: cold-start deep-link auth gate.
    // If a Session deep link arrives while logged out, stash the session id here and show Login.
    // After login the LaunchedEffect below navigates to the pending Session.
    val context = LocalContext.current
    var pendingSessionId by remember { mutableStateOf<String?>(null) }

    // Capture any deep-link session id from the launch intent before the first composition.
    // agentic://session/<id> → extract <id> from the path segment.
    remember(context) {
        val activity = context as? ComponentActivity
        val intentUri = activity?.intent?.data
        if (intentUri != null && intentUri.scheme == "agentic" && intentUri.host == "session") {
            val id = intentUri.pathSegments.firstOrNull()
            if (!id.isNullOrBlank()) {
                AppLog.d("Nav", "deep link cold-start session=$id")
                // Replay this deep link ourselves (width-aware) via the LaunchedEffect below — for a
                // logged-in cold start immediately, for a logged-out one after login. CONSUME the
                // intent so Navigation's built-in deep-link handling doesn't ALSO auto-push the
                // Session route; in wide mode that auto-push would stack a duplicate full-screen Home.
                pendingSessionId = id
                activity?.intent = Intent()
            }
        }
        // Return value is unused; remember runs the block exactly once per composition key change.
        Unit
    }

    // Fix A: handle warm deep links (notification tap while app is already running).
    // Cold-start deep links are intercepted above; warm ones come via onNewIntent.
    // Keyed on [wide]/[loggedIn] too so the listener captures the current arrangement and auth state.
    DisposableEffect(nav, context, wide, loggedIn) {
        val activity = context as? ComponentActivity
        val listener = Consumer<Intent> { intent ->
            val uri = intent.data
            val id = if (uri != null && uri.scheme == "agentic" && uri.host == "session")
                uri.pathSegments.firstOrNull() else null
            when {
                id.isNullOrBlank() -> nav.handleDeepLink(intent) // not a session link → default handling
                else -> {
                    if (loggedIn) {
                        // Logged in: open it now (width-aware, so wide doesn't stack a second Home).
                        AppLog.d("Nav", "deep link warm session=$id")
                        openSessionAdaptive(id)
                    } else {
                        // Logged out: stash and let the replay effect open it after login (don't
                        // reveal a session past the auth gate).
                        AppLog.d("Nav", "deep link warm deferred (logged out) session=$id")
                        pendingSessionId = id
                    }
                    // CONSUME the sticky intent (MainActivity publishes it via setIntent BEFORE
                    // dispatching to this listener). If it stayed set, a later graph reset (the
                    // NavHost startDestination flips on login/logout) would make NavController
                    // re-run handleDeepLink(activity.intent) on the STALE uri and auto-synthesize
                    // a [Home, Session] back stack — the deep-link variant of the two-layer Home
                    // bug. Mirrors the cold-start consume above.
                    activity?.intent = Intent()
                }
            }
        }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    // Fix B: guard logout redirect — skip if already on Login or graph not yet ready.
    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            AppLog.d("Nav", "logout redirect to Login")
            // Drop any stale "open this session" handoff so it can't fire against the next account.
            container.homeSelectRequest.value = null
            val current = nav.currentBackStackEntry?.destination
            val onLogin = current?.hasRoute(Login::class) == true
            if (current != null && !onLogin) {
                nav.navigate(Login) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    // ND-1 replay: once logged in, open the pending deep-link session (if any). Width-aware via
    // openSessionAdaptive so wide funnels into the single Home instead of stacking a Session route.
    // Keyed on [wide] too: if it changed before the replay fires, we use the current arrangement
    // (pendingSessionId is cleared after the first run, so a later re-fire is a no-op).
    LaunchedEffect(loggedIn, pendingSessionId, wide) {
        val pending = pendingSessionId
        if (loggedIn && pending != null) {
            pendingSessionId = null
            openSessionAdaptive(pending)
        }
    }

    NavHost(
        navController = nav,
        startDestination = if (loggedIn) Home else Login,
        enterTransition = {
            if (wide && initialState.destination.isHomeFamily() && targetState.destination.isHomeFamily())
                EnterTransition.None
            else slideInHorizontally(animationSpec = tween(NAV_MOTION_MS, easing = navEasing)) { it }
        },
        exitTransition = {
            if (wide && initialState.destination.isHomeFamily() && targetState.destination.isHomeFamily())
                ExitTransition.None
            else slideOutHorizontally(animationSpec = tween(NAV_MOTION_MS, easing = navEasing)) { -it }
        },
        popEnterTransition = {
            if (wide && initialState.destination.isHomeFamily() && targetState.destination.isHomeFamily())
                EnterTransition.None
            else slideInHorizontally(animationSpec = tween(NAV_MOTION_MS, easing = navEasing)) { -it }
        },
        popExitTransition = {
            if (wide && initialState.destination.isHomeFamily() && targetState.destination.isHomeFamily())
                ExitTransition.None
            else slideOutHorizontally(animationSpec = tween(NAV_MOTION_MS, easing = navEasing)) { it }
        },
    ) {
        composable<Login> {
            LoginScreen(
                onLoggedIn = {
                    // launchSingleTop guards the race with the NavHost's dynamic startDestination:
                    // when isLoggedIn flips, the graph is rebuilt with start=Home, which resets the
                    // stack to [Home] and pops Login — but the LoginScreen can still be composed
                    // (animating out) when its LaunchedEffect fires this callback. Then popUpTo
                    // finds no Login and, without singleTop, a SECOND Home would be pushed
                    // ([Home, Home] — back on the home slides to an identical home).
                    nav.navigate(Home) {
                        popUpTo<Login> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<Home> {
            // ONE adaptive tree for every size (list-detail pane scaffold). No more wide-vs-narrow
            // fork — the same SessionScreen renders in both, so the detail UI can't drift.
            var showAdoptPicker by remember { mutableStateOf(false) }
            HomeAdaptive(
                onNewRequest = { nav.navigate(NewRequest) },
                onOpenHistory = { id, live -> nav.navigate(History(id, live)) },
                onOpenSettings = { id -> nav.navigate(SessionSettings(id)) },
                onOpenDiagnostics = { nav.navigate(Diagnostics) },
                onOpenProviders = { nav.navigate(Providers) },
                onOpenAdoptPicker = { showAdoptPicker = true },
                onOpenGlobalSettings = { nav.navigate(GlobalSettings) },
            )
            if (showAdoptPicker) {
                AdoptPickerSheet(
                    onDismiss = { showAdoptPicker = false },
                    onAdopted = { id -> showAdoptPicker = false; openSessionAdaptive(id) },
                )
            }
        }

        composable<NewRequest> {
            NewRequestScreen(
                onBack = { nav.popBackStack() },
                // Width-aware: wide selects the new session in the single Home (no second Home on the
                // back stack); narrow pushes the Session route. Either way the NewRequest layer is
                // dropped (popUpTo<Home>).
                onCreated = { id, prompt -> openSessionAdaptive(id, prompt) },
            )
        }

        composable<Session>(
            deepLinks = listOf(
                // Navigation-Compose 2.8.x generic deep-link: basePath = "agentic://session"
                // matches agentic://session/{id} and populates Session.id automatically.
                // This aligns with the manifest intent-filter (scheme=agentic, host=session).
                navDeepLink<Session>(basePath = "agentic://session"),
            ),
        ) { backStackEntry ->
            val sessionId = remember(backStackEntry) { backStackEntry.toRoute<Session>().id }
            // ND-3: guard against a blank id (e.g. deep link with no path segment after "session/").
            // Bounce to Home immediately rather than rendering with an empty id.
            if (sessionId.isBlank()) {
                LaunchedEffect(Unit) {
                    nav.navigate(Home) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                return@composable
            }
            // Render-time normalization for the "two-layer Home" bug: in wide mode this route
            // renders the SAME full-screen adaptive Home as the [Home] route, so having both on the
            // back stack makes system-back animate Home→Home. [openSessionAdaptive] prevents that
            // for in-app navigation, but entries can still get here without passing through it:
            //  - the route was pushed while narrow (outer foldable screen / split-screen), then the
            //    window turned wide (unfold) — the stacked entry now renders a duplicate Home;
            //  - Navigation's own deep-link handling synthesized [Home, Session] (e.g. a stale
            //    activity intent re-handled on a graph reset), bypassing our width-aware helper.
            // Normalize exactly like the wide branch of [openSessionAdaptive]: hand the id to the
            // single Home and remove this entry. Keyed on [wide] so a later narrow→wide resize of
            // an already-composed entry normalizes too.
            if (wide) {
                // Only normalize while THIS entry is the current top — if it sits beneath another
                // screen (e.g. SessionSettings) when the window turns wide, popUpTo<Session> would
                // sweep that screen away too. The top is observed via currentBackStackEntryFlow
                // INSIDE the effect (not as composition state) so back-stack changes don't
                // recompose this whole subtree; the flow emits the current entry immediately on
                // collect, and the effect relaunches if this entry re-enters composition later, so
                // popping back down to a stale duplicate still normalizes it.
                LaunchedEffect(sessionId, wide) {
                    nav.currentBackStackEntryFlow.collect { current ->
                        if (current != backStackEntry) return@collect
                        AppLog.d("Nav", "wide normalize: Session($sessionId) → Home")
                        // Navigate FIRST, hand off the id AFTER (Codex review): this entry renders
                        // the same WideThreePaneHome, whose homeSelectRequest consumer could
                        // otherwise swallow + clear the value before the real Home is revealed.
                        // Popping first tears the duplicate's collector down; the StateFlow then
                        // retains the id until the real Home entry collects it. (No suspension
                        // point between the two statements, so cancellation can't split them.)
                        nav.navigate(Home) {
                            popUpTo<Session> { inclusive = true } // drop THIS duplicate-Home entry
                            launchSingleTop = true                // reuse the Home below, never stack
                        }
                        container.homeSelectRequest.value = sessionId
                    }
                }
                // Keep rendering the same adaptive Home beneath the (animation-less, see the
                // transition lambdas) swap so there is no blank flash while the effect runs.
            }
            // Deep link / notification tap / just-created session: the SAME adaptive home as the Home
            // route, with this session pre-selected in the detail pane (so unfolding keeps the list
            // beside it, and a phone shows the session with the scaffold's built-in Back to the list).
            var showAdoptPicker by remember { mutableStateOf(false) }
            HomeAdaptive(
                onNewRequest = { nav.navigate(NewRequest) },
                onOpenHistory = { id, live -> nav.navigate(History(id, live)) },
                onOpenSettings = { id -> nav.navigate(SessionSettings(id)) },
                onOpenDiagnostics = { nav.navigate(Diagnostics) },
                onOpenProviders = { nav.navigate(Providers) },
                onOpenAdoptPicker = { showAdoptPicker = true },
                onOpenGlobalSettings = { nav.navigate(GlobalSettings) },
                initialSelectedId = sessionId,
            )
            if (showAdoptPicker) {
                AdoptPickerSheet(
                    onDismiss = { showAdoptPicker = false },
                    onAdopted = { id -> showAdoptPicker = false; openSessionAdaptive(id) },
                )
            }
        }

        composable<Workflow> {
            WorkflowScreen(onBack = { nav.popBackStack() })
        }

        composable<History> { backStackEntry ->
            val route = backStackEntry.toRoute<History>()
            CommitGraphScreen(
                onBack = { nav.popBackStack() },
                live = route.live,
                onOpenDiff = { repo, sha, path ->
                    nav.navigate(FileDiffRoute(id = route.id, repo = repo, sha = sha, path = path))
                },
            )
        }

        composable<FileDiffRoute> {
            FileDiffScreen(onBack = { nav.popBackStack() })
        }

        // SessionSettings: full-screen session-persistent settings picker. Tapping Save PATCHes
        // /api/sessions/:id; the screen pops back with no per-turn handoff or parent-VM sharing.
        composable<SessionSettings> { backStackEntry ->
            val id = backStackEntry.toRoute<SessionSettings>().id
            SessionSettingsScreen(
                sessionId = id,
                onBack = { nav.popBackStack() },
            )
        }

        composable<Diagnostics> {
            DiagnosticsScreen(
                onBack = { nav.popBackStack() },
                onOpenProviders = { nav.navigate(Providers) },
                // After attach+send, land on the session so the user sees the log message arrive.
                onOpenSession = { id -> openSessionAdaptive(id) },
            )
        }

        composable<Providers> {
            ProvidersScreen(onBack = { nav.popBackStack() })
        }

        composable<GlobalSettings> {
            GlobalSettingsScreen(onBack = { nav.popBackStack() })
        }
    }

    // Crash-on-last-run prompt: if the previous run left an unacknowledged crash report, offer to
    // open the diagnostics screen so the user can review / share it. Detected once per composition;
    // tapping "查看日志" (or opening the screen) marks the crashes seen so this won't re-fire.
    var showCrashPrompt by remember { mutableStateOf(false) }
    // Detect an unacknowledged crash from the previous run off the main thread (filesDir IO).
    LaunchedEffect(Unit) {
        showCrashPrompt = withContext(Dispatchers.IO) {
            container.logStore.unseenCrashes().isNotEmpty()
        }
        if (showCrashPrompt) AppLog.d("Nav", "crash prompt shown")
    }
    if (showCrashPrompt) {
        AlertDialog(
            onDismissRequest = {
                AppLog.d("Nav", "crash prompt dismissed (backdrop)")
                showCrashPrompt = false
                container.logStore.markCrashesSeen()
            },
            icon = { Icon(Icons.Rounded.Warning, null) },
            title = { Text("上次运行发生崩溃") },
            text = { Text("检测到上次运行有未处理的崩溃，已生成日志。要现在查看或分享吗?") },
            confirmButton = {
                TextButton(onClick = {
                    AppLog.d("Nav", "crash prompt confirm → diagnostics")
                    showCrashPrompt = false
                    nav.navigate(Diagnostics)
                }) { Text("查看日志") }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppLog.d("Nav", "crash prompt dismissed (ignore)")
                    showCrashPrompt = false
                    container.logStore.markCrashesSeen()
                }) { Text("忽略") }
            },
        )
    }
}
