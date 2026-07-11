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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.ui.globalsettings.GlobalSettingsScreen
import dev.agentic.ui.globalsettings.GlobalSettingsViewModel
import dev.agentic.ui.globalsettings.SkillStoreScreen
import dev.agentic.ui.home.HomeAdaptive
import dev.agentic.ui.home.isWideHome
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
// they ride as query params (URL-encoded) — `path` may contain slashes, which a path-segment arg would break.
@Serializable data class FileDiffRoute(val id: String, val repo: String = "", val sha: String = "", val path: String = "")
@Serializable data class SessionSettings(val id: String)
@Serializable object Diagnostics
@Serializable object GlobalSettings
@Serializable object SkillStore

// ── NavHost ──────────────────────────────────────────────────────────────────

/**
 * Single type-safe [NavHost] wiring all stateless screens.
 *  - Auth redirect: [AppContainer.authRepo.isLoggedIn] observed; transition to false (logout/401)
 *    immediately pops everything and navigates to [Login].
 *  - Start destination: composition-time from the current [isLoggedIn] snapshot — logged-in lands
 *    on [Home], else [Login].
 *  - Deep link: Session accepts `agentic://session/{id}` via [navDeepLink] (Navigation-Compose 2.8+
 *    generic basePath). NavController auto-picks from Activity intent / onNewIntent.
 *  - id-for-nav: [backStackEntry.toRoute<Session>().id] extracts the typed session id for use in
 *    lambdas without touching SavedStateHandle. SessionViewModel reads the same id from its own
 *    SavedStateHandle (key "id") populated from the typed route args.
 */
// ── Screen transitions: clean full-width horizontal slide (no cross-fade) ──────────
// Forward: new slides in from right, old out to the left; back reverses. NO fadeIn/fadeOut: a
// cross-fade leaves both momentarily semi-transparent, letting the light window background flash
// through on our dark app — the "转场闪一下" bug. Full-width slide keeps both fully opaque, tiling
// the viewport every frame. ~300ms MD3.
private const val NAV_MOTION_MS = AppMotion.DurationNav
private val navEasing = AppMotion.Emphasized

/**
 * True for routes that render the full-screen adaptive Home ([Home] and [Session]). In wide mode a
 * transition between two of them is a Home→Home slide of visually identical screens — the exact
 * animation the "two-layer Home" bug shows — so NavHost transition lambdas SNAP (no animation)
 * instead. This also makes the render-time normalization in `composable<Session>` invisible: the
 * duplicate entry is swapped out without a slide.
 */
private fun NavDestination.isHomeFamily(): Boolean =
    hasRoute(Home::class) || hasRoute(Session::class)

@Composable
fun AppNav() {
    val container = appContainer()
    val nav = rememberNavController()
    val loggedIn by container.authRepo.isLoggedIn.collectAsStateWithLifecycle()

    // Whether the window currently uses the wide 3-pane Home. Same rule [HomeAdaptive] uses so the
    // navigation decision stays in lock-step with what actually renders. Recomputed on config
    // changes (rotation / fold).
    val wide = isWideHome(LocalConfiguration.current)

    /**
     * Open (select) a session, choosing the arrangement-correct path — wide mode fan-out to the
     * single full-screen Home via [AppContainer.homeSelectRequest] (reveals existing Home with
     * `launchSingleTop`; does NOT push Session or it stacks a second full-screen Home —
     * the "two-layer Home" bug). Narrow pushes [Session] (distinct detail with its own back-to-list).
     */
    fun openSessionAdaptive(id: String, prompt: String? = null) {
        if (id.isBlank()) return
        if (wide) {
            container.homeSelectRequest.value = id
            nav.navigate(Home) {
                popUpTo<Home>()
                launchSingleTop = true
            }
        } else {
            nav.navigate(Session(id, prompt)) {
                popUpTo<Home>()
                launchSingleTop = true
            }
        }
    }

    // ND-1: cold-start deep-link auth gate.
    // If a Session deep link arrives while logged out, stash the id here and show Login. After
    // login the LaunchedEffect below navigates to the pending Session.
    val context = LocalContext.current
    var pendingSessionId by remember { mutableStateOf<String?>(null) }

    // Capture any deep-link session id from the launch intent before first composition.
    // agentic://session/<id> → extract <id> from the path segment.
    remember(context) {
        val activity = context as? ComponentActivity
        val intentUri = activity?.intent?.data
        if (intentUri != null && intentUri.scheme == "agentic" && intentUri.host == "session") {
            val id = intentUri.pathSegments.firstOrNull()
            if (!id.isNullOrBlank()) {
                AppLog.d("Nav", "deep link cold-start session=$id")
                // Replay ourselves (width-aware) via LaunchedEffect below — logged in cold start: immediately;
                // logged out: after login. CONSUME the intent so Navigation's built-in deep-link handling
                // doesn't ALSO auto-push the Session route (in wide mode that would stack a duplicate Home).
                pendingSessionId = id
                activity?.intent = Intent()
            }
        }
        Unit
    }

    // Fix A: warm deep links (notification tap while app already running). Cold-start intercepted
    // above; warm come via onNewIntent. Keyed on [wide]/[loggedIn] too so listener captures the
    // current arrangement and auth state.
    DisposableEffect(nav, context, wide, loggedIn) {
        val activity = context as? ComponentActivity
        val listener = Consumer<Intent> { intent ->
            val uri = intent.data
            val id = if (uri != null && uri.scheme == "agentic" && uri.host == "session")
                uri.pathSegments.firstOrNull() else null
            when {
                id.isNullOrBlank() -> nav.handleDeepLink(intent)
                else -> {
                    if (loggedIn) {
                        AppLog.d("Nav", "deep link warm session=$id")
                        openSessionAdaptive(id)
                    } else {
                        AppLog.d("Nav", "deep link warm deferred (logged out) session=$id")
                        pendingSessionId = id
                    }
                    // CONSUME the sticky intent (MainActivity publishes it via setIntent BEFORE
                    // dispatching this listener). If it stayed set, a later graph reset (startDestination
                    // flips on login/logout) would make NavController re-run handleDeepLink on the STALE
                    // uri and auto-synthesize a [Home, Session] back stack — the deep-link variant of
                    // the two-layer Home bug. Mirrors cold-start consume above.
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
            // Drop stale "open this session" handoff so it can't fire against the next account.
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

    // ND-1 replay: once logged in, open pending deep-link session. Width-aware via openSessionAdaptive
    // so wide funnels into the single Home instead of stacking Session. Keyed on [wide] too: if it
    // changed before replay fires, use current arrangement (pendingSessionId cleared after first run).
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
        // POPs between home-family routes snap in EVERY width (no `wide` gate): in narrow, a Session
        // entry is only popped once its scaffold is back on the list — both sides render the identical
        // list, and narrow deselect-normalization relies on this being invisible. Forward pushes
        // keep the slide in narrow (opening a detail).
        popEnterTransition = {
            if (initialState.destination.isHomeFamily() && targetState.destination.isHomeFamily())
                EnterTransition.None
            else slideInHorizontally(animationSpec = tween(NAV_MOTION_MS, easing = navEasing)) { -it }
        },
        popExitTransition = {
            if (initialState.destination.isHomeFamily() && targetState.destination.isHomeFamily())
                ExitTransition.None
            else slideOutHorizontally(animationSpec = tween(NAV_MOTION_MS, easing = navEasing)) { it }
        },
    ) {
        composable<Login> {
            LoginScreen(
                onLoggedIn = {
                    // launchSingleTop guards the race with NavHost's dynamic startDestination:
                    // when isLoggedIn flips, the graph is rebuilt with start=Home which resets the
                    // stack to [Home] and pops Login — but LoginScreen can still be composed (animating
                    // out) when its LaunchedEffect fires this callback. Without singleTop, popUpTo
                    // finds no Login and a SECOND Home is pushed ([Home, Home]).
                    nav.navigate(Home) {
                        popUpTo<Login> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<Home> {
            // ONE adaptive tree for every size (list-detail pane scaffold).
            var showAdoptPicker by remember { mutableStateOf(false) }
            HomeAdaptive(
                onNewRequest = { nav.navigate(NewRequest) },
                onOpenHistory = { id, live -> nav.navigate(History(id, live)) },
                onOpenSettings = { id -> nav.navigate(SessionSettings(id)) },
                onOpenDiagnostics = { nav.navigate(Diagnostics) },
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
                // Width-aware: wide selects the new session in the single Home; narrow pushes Session.
                onCreated = { id, prompt -> openSessionAdaptive(id, prompt) },
            )
        }

        composable<Session>(
            deepLinks = listOf(
                // Navigation-Compose 2.8.x generic deep-link: basePath = "agentic://session"
                // matches agentic://session/{id} and populates Session.id automatically.
                navDeepLink<Session>(basePath = "agentic://session"),
            ),
        ) { backStackEntry ->
            val sessionId = remember(backStackEntry) { backStackEntry.toRoute<Session>().id }
            // ND-3: guard blank id (e.g. deep link with no path after "session/").
            // Bounce to Home immediately rather than rendering with empty id.
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
            // renders the SAME full-screen adaptive Home as the [Home] route, so both on the back
            // stack makes system-back animate Home→Home. [openSessionAdaptive] prevents that for
            // in-app navigation, but entries can still land here bypassing it:
            //   - route pushed while narrow (foldable outer/split-screen), then window turned wide —
            //     stacked entry renders a duplicate Home;
            //   - Navigation's own deep-link handling synthesized [Home, Session] (e.g. stale
            //     activity intent re-handled on graph reset).
            // Normalize exactly like the wide branch of [openSessionAdaptive]: hand id to the single
            // Home and remove this entry. Keyed on [wide] so a later narrow→wide resize of an
            // already-composed entry normalizes too.
            if (wide) {
                // Only normalize while THIS entry is current top — if it sits beneath another screen
                // (e.g. SessionSettings) when the window turns wide, popUpTo<Session> would sweep that
                // screen away too. Top observed via currentBackStackEntryFlow INSIDE the effect (not
                // composition state) so back-stack changes don't recompose this whole subtree.
                LaunchedEffect(sessionId, wide) {
                    nav.currentBackStackEntryFlow.collect { current ->
                        if (current != backStackEntry) return@collect
                        AppLog.d("Nav", "wide normalize: Session($sessionId) → Home")
                        // Navigate FIRST, hand off the id AFTER: this entry renders the same
                        // WideThreePaneHome whose homeSelectRequest consumer could swallow + clear the
                        // value before the real Home is revealed. Popping first tears the duplicate's
                        // collector down; StateFlow then retains the id until the real Home entry
                        // collects it. (No suspension point between the two statements, so cancellation
                        // can't split them.)
                        nav.navigate(Home) {
                            popUpTo<Session> { inclusive = true }
                            launchSingleTop = true
                        }
                        container.homeSelectRequest.value = sessionId
                    }
                }
                // Keep rendering the same adaptive Home beneath the (animation-less) swap so no blank flash.
            }
            var showAdoptPicker by remember { mutableStateOf(false) }
            HomeAdaptive(
                onNewRequest = { nav.navigate(NewRequest) },
                onOpenHistory = { id, live -> nav.navigate(History(id, live)) },
                onOpenSettings = { id -> nav.navigate(SessionSettings(id)) },
                onOpenDiagnostics = { nav.navigate(Diagnostics) },
                onOpenAdoptPicker = { showAdoptPicker = true },
                onOpenGlobalSettings = { nav.navigate(GlobalSettings) },
                initialSelectedId = sessionId,
                // Narrow leg of the two-layer Home bug: while a session is selected, this route is a
                // legitimate detail screen — but once the scaffold returns to its list (system back
                // from the detail pane), the entry renders the SAME list Home renders beneath it, so
                // the user must press back TWICE through identical "homes". Scaffold reports that
                // moment; pop the duplicate right there (a home-family POP is animation-less).
                onDeselected = {
                    AppLog.d("Nav", "narrow normalize: Session($sessionId) deselected → pop duplicate")
                    if (nav.currentBackStackEntry == backStackEntry) nav.popBackStack()
                },
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
                // Models management merged into Global settings — shortcut lands there now.
                onOpenSettings = { nav.navigate(GlobalSettings) },
                // After attach+send, land on the session so the user sees the log message arrive.
                onOpenSession = { id -> openSessionAdaptive(id) },
            )
        }

        composable<GlobalSettings> { backStackEntry ->
            // Shared with the SkillStore route (scoped to THIS back-stack entry): mutations in the
            // store are immediately visible here — no duplicate fetches or reload-on-return
            // choreography between two ViewModel instances.
            val container = appContainer()
            val sharedVm: GlobalSettingsViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
                factory = viewModelFactory {
                    initializer { GlobalSettingsViewModel(container.api) }
                },
            )
            GlobalSettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenSkillStore = { nav.navigate(SkillStore) },
                vm = sharedVm,
            )
        }

        composable<SkillStore> {
            // Store only reached FROM Settings — reuse its entry's ViewModel.
            val container = appContainer()
            val settingsEntry = remember(nav) { nav.getBackStackEntry(GlobalSettings) }
            val sharedVm: GlobalSettingsViewModel = viewModel(
                viewModelStoreOwner = settingsEntry,
                factory = viewModelFactory {
                    initializer { GlobalSettingsViewModel(container.api) }
                },
            )
            SkillStoreScreen(onBack = { nav.popBackStack() }, vm = sharedVm)
        }
    }

    // Crash-on-last-run prompt: if the previous run left an unacknowledged crash report, offer to
    // open the diagnostics screen so the user can review/share. Detected once per composition;
    // tapping "查看日志" (or opening the screen) marks the crashes seen so this won't re-fire.
    var showCrashPrompt by remember { mutableStateOf(false) }
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
