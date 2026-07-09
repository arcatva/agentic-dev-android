# Login: LAN Scan + Manual Entry — Design

Date: 2026-06-22
Repo: `agentic-dev-android`
Status: Approved (design), pending implementation plan

## Goal

Rework the login screen to offer two ways to reach a backend, both styled with
Material 3 Expressive (MD3E):

- **(a) Scan the LAN** for agentic-dev servers on port 7420 and let the user pick
  one when several are found.
- **(b) Enter the host manually** — the current behavior, kept as-is.

## Scope

In scope:
- A two-card chooser landing screen (Scan / Manual) that drills into a sub-screen.
- LAN discovery: scan the device's local Wi-Fi /24 subnet for TCP `:7420`, confirm
  each hit is an agentic-dev server via `GET /healthz`, list results with IP +
  round-trip latency + reachable check, single-select via radio.
- Auto-start the scan when the login screen opens (background); the Scan card
  shows live status.
- A password show/hide toggle (small MD3 improvement over the current field).
- Keep manual entry exactly as today: one full Host-URL field, prefilled with the
  last-used host.

Out of scope (explicitly decided):
- Any change to the `agentic-dev` backend. We reuse the existing unauthenticated
  `GET /healthz` endpoint; no new endpoint, no mDNS/NSD advertisement.
- Per-server passwords / usernames. The backend authenticates by password only,
  so discovery resolves *which host*; the password is still typed by the user.
- Configurable port or port-range scan. Port is fixed at 7420.
- Scanning anything other than the device's local private-IPv4 /24 (no Tailscale
  CGNAT `100.64/10` sweep, no multi-subnet).
- New runtime permissions (uses the already-declared `ACCESS_NETWORK_STATE`).
- New navigation routes (the auth gate keys off `isLoggedIn`; left untouched).

## Background (current state)

- 100% Jetpack Compose, `MaterialExpressiveTheme` (forced `DarkExpressive`,
  `ExpressiveShapes`, custom `AppMotionScheme`). Material 3
  `androidx.compose.material3:material3:1.4.0-alpha18` (exposes Expressive APIs:
  `LoadingIndicator`, `ContainedLoadingIndicator`, button groups, etc.).
- Login is MVVM and stateless:
  - `ui/login/LoginScreen.kt` — stateless composable: icon + title, Host + Password
    `OutlinedTextField`s, error text, "Log in" `Button`. Navigates out via a
    `LaunchedEffect(s.done)`.
  - `ui/login/LoginViewModel.kt` — `LoginUiState(host, password, busy, error, done)`;
    `submit()` calls `authRepo.login(host, password)`.
- Auth model: `AuthRepository.login(host, password)` sets `api.baseUrl = host.trim()`,
  calls `api.login(password)` (`POST /api/login` → token), persists host + token to
  `SettingsStore`. `host` is a full URL, e.g. `https://192.0.2.1:7420` (a placeholder example — the default
  is a Tailscale address — the user normally connects over Tailscale, hence the LAN
  scan is a distinct, explicit "on the local network" path).
- Backend (`agentic-dev/server-rs`): `GET /healthz` is reachable with **no auth**
  (it is not under `/api/` and is exempt from the auth gate) and returns the
  plaintext body `ok`. This is the discovery probe target.
- Networking: Ktor + OkHttp via `KtorAgenticApi`, a single client with one mutable
  `baseUrl`. DI is manual via `AppContainer`. Tests use `FakeAgenticApi` /
  `FakeSettingsStore`; ViewModel tests drive `Dispatchers.setMain(testDispatcher)`.

## Design

### Navigation within login (no new nav routes)

Keep the single `Login` destination in `AppNav`. Inside the login feature, a
`step` state drives one of three sub-screens, swapped with `AnimatedContent` using
the app's existing horizontal-slide motion (`AppMotion` tokens) so it matches
global nav feel:

```
LoginStep.Chooser   ← landing: brand + two cards
LoginStep.Scan      ← scan results sub-screen
LoginStep.Manual    ← manual host entry sub-screen
```

- Sub-screens (Scan, Manual) show a `TopAppBar` with a back arrow + title.
- System back (`BackHandler`) on a sub-screen returns to `Chooser`.
- On a successful login, `LoginUiState.done` flips and the existing
  `LaunchedEffect(s.done) { onLoggedIn() }` navigates to Home (unchanged).

#### Chooser + auto-scan reconciliation

The landing renders **both cards**. The scan **auto-starts in the background** the
moment the chooser appears (resolves the "open → auto-scan" decision). The "Scan
LAN" card reflects live status in its supporting text:

- scanning → "Scanning…" with a `ContainedLoadingIndicator`,
- found N → "Found N server(s)",
- found 0 (done) → "No servers found",
- not on LAN → "Not on a local network".

Tapping the Scan card opens the Scan sub-screen (already populated → instant).
Tapping the Manual card opens the Manual sub-screen. Re-entering Chooser does not
restart a scan that already completed (results are cached in the VM); a manual
rescan is available on the Scan sub-screen.

Wireframe:

```
┌─ Chooser ──────────┐   ┌─ Scan ───────────────┐   ┌─ Manual ─────────────┐
│   ✦ agentic-dev    │   │ ← Scan LAN      ⟳    │   │ ← Manual entry       │
│ ┌────────────────┐ │   │ ◉ 192.168.1.10:7420  │   │ Host [____________]  │
│ │ 🔍 Scan LAN     │ │   │     12 ms        ✓   │   │ Password [____] 👁   │
│ │ Found 2 ›       │ │   │ ○ 192.168.1.23:7420  │   │                      │
│ └────────────────┘ │   │     31 ms        ✓   │   │ [      Log in     ]  │
│ ┌────────────────┐ │   │ ─────────────────    │   └──────────────────────┘
│ │ ⌨ Manual entry ›│ │   │ Password [____] 👁   │
│ └────────────────┘ │   │ [      Connect    ]  │
└────────────────────┘   └──────────────────────┘
```

### Data layer: `LanScanner` (new, dependency-injected, unit-testable)

New file `app/src/main/java/dev/agentic/data/net/LanScanner.kt`. Two collaborators
behind interfaces so orchestration is testable without real I/O:

1. `NetworkInfoProvider` (interface) → `RealNetworkInfoProvider`
   - Enumerates `java.net.NetworkInterface.getNetworkInterfaces()`, picks the first
     interface that is **up, not loopback, has a site-local IPv4** (10/8, 172.16/12,
     192.168/16), **preferring `wlan*`**. Returns `LocalNet(ip, prefixLen)` or null.
   - Deliberately avoids the Tailscale CGNAT (`100.64/10`) interface so the scan
     targets the real LAN.
   - Needs no permission beyond the already-declared `ACCESS_NETWORK_STATE`.

2. `ServerProbe` (interface) → `HealthzServerProbe`
   - `suspend fun probe(ip: String): DiscoveredServer?`
   - Opens a TCP socket to `ip:7420` with a short connect timeout (~400 ms). On
     connect, issues `GET /healthz` (short read timeout) and accepts the host only
     if the response body trims to `ok` (confirms agentic-dev, not some other
     service occupying 7420). Records round-trip latency (ms). Returns null on any
     failure/timeout.
   - Uses its own short-timeout HTTP path (a dedicated lightweight client or raw
     socket), **not** the shared `KtorAgenticApi` (whose single mutable `baseUrl`
     can't be used for parallel probing).

3. `LanScanner` — pure orchestration:
   - `fun scan(): Flow<ScanUpdate>`
   - Computes the candidate list: the local /24 (host octet 1..254), **capped at a
     /24** even if the real prefix is wider (≤254 probes — bounded work), excluding
     the device's own IP.
   - Probes candidates with bounded concurrency (~48 in flight, `Dispatchers.IO` +
     a `Semaphore`), emitting each `DiscoveredServer` as it is found plus progress,
     so the UI fills in live.
   - If `NetworkInfoProvider` returns null → emits `ScanUpdate.NotOnLan` and
     completes.
   - Cancellation-safe (collected in `viewModelScope`; cancel stops probes).

Models:

```kotlin
data class DiscoveredServer(
    val ip: String,
    val port: Int = 7420,
    val latencyMs: Long,
) {
    val baseUrl: String get() = "http://$ip:$port"
}

sealed interface ScanUpdate {
    data class Progress(val scanned: Int, val total: Int) : ScanUpdate
    data class Found(val server: DiscoveredServer) : ScanUpdate
    data object Done : ScanUpdate
    data object NotOnLan : ScanUpdate
}
```

### State & ViewModel (`LoginViewModel` extended)

Extend `LoginUiState` (keep existing `password`, `busy`, `error`, `done`):

```kotlin
enum class LoginStep { Chooser, Scan, Manual }

data class LoginUiState(
    val step: LoginStep = LoginStep.Chooser,
    // scan
    val scanning: Boolean = false,
    val scanProgress: Float? = null,        // scanned/total, null when indeterminate
    val results: List<DiscoveredServer> = emptyList(),
    val selectedHost: String? = null,       // baseUrl of the chosen server
    val notOnLan: Boolean = false,
    // manual (existing)
    val host: String = "",                  // prefilled with settings.host
    // shared (existing)
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)
```

Behavior / handlers:
- `init` / first Chooser entry → `startScan()` (collect `lanScanner.scan()` in
  `viewModelScope`, updating `scanning` / `results` / `scanProgress` / `notOnLan`).
- `rescan()` → clears results and re-collects.
- `goTo(step)` / `back()` → set `step`.
- `onSelectServer(baseUrl)` → set `selectedHost`. When the scan finishes with
  **exactly one** result, auto-select it.
- `onHost`, `onPassword` (existing), `togglePasswordVisible()`.
- `submit()` → unchanged logic, but the host is `selectedHost` (Scan) or `host`
  (Manual): `authRepo.login(host, password)`. Error mapping (`loginErrorMessage`)
  unchanged.
- Connect/Log-in button enabled only when a host is chosen/typed **and** password
  is non-empty and not busy.

`lanScanner` is injected into the VM (default-constructed in
`AppContainer`; a fake is injected in tests).

### MD3 Expressive components

- **Chooser cards**: `Card(onClick = …)` with `ExpressiveShapes` large radii;
  leading icon + title + supporting text. The Scan card's supporting text and a
  `ContainedLoadingIndicator` reflect live scan state.
- **Scan progress**: Expressive `ContainedLoadingIndicator` (morphing shapes) at
  the top of the results region while `scanning`.
- **Result rows**: `ListItem` — leading icon, headline `IP:7420`, supporting
  `"<latency> ms"`, trailing `RadioButton`; the whole row is `selectable` in a
  single-select group; reachable rows show a check.
- **Rescan**: `PullToRefreshBox` (pull-to-rescan) plus a top-bar `IconButton` (⟳).
- **Password field**: `OutlinedTextField` with `PasswordVisualTransformation`
  toggled by a trailing visibility `IconButton` (show/hide).
- **Primary action**: filled `Button` (full width); shows an inline indicator and
  is disabled while `busy`.
- **Empty / not-on-LAN states**: centered icon + message + actions
  ("Rescan" / "Enter manually") using MD3 typography and `onSurfaceVariant`.

### Edge cases

- **Not on Wi-Fi / no private subnet** → `notOnLan` empty state, with a button to
  switch to Manual.
- **Scan finds 0** (completed) → empty state with "Rescan" + "Enter manually".
- **Single result** → auto-selected so the user only types the password.
- **Manual** → unchanged: full Host-URL `OutlinedTextField`, prefilled from
  `settings.host`.
- **Cleartext HTTP** → already enabled (`usesCleartextTraffic="true"`).

### Strings

Inline English literals (project has no `strings.xml`, matching existing UI):
`agentic-dev`, `Scan LAN`, `Manual entry`, `Scanning…`, `Found %d server(s)`,
`No servers found`, `Not on a local network`, `Host`, `Password`, `Connect`,
`Log in`, `Rescan`, `Enter manually`, `%d ms`.

## File structure (small, focused units)

- New: `app/src/main/java/dev/agentic/data/net/LanScanner.kt`
  (interfaces `NetworkInfoProvider` + `ServerProbe`, impls `RealNetworkInfoProvider`
  + `HealthzServerProbe`, models `DiscoveredServer` / `ScanUpdate`, the `LanScanner`
  orchestrator).
- New UI (split the current single login file):
  - `ui/login/LoginScreen.kt` — shell + `AnimatedContent` step router + `BackHandler`.
  - `ui/login/LoginChooser.kt` — two-card landing.
  - `ui/login/LoginScanScreen.kt` — results list + selection + password + connect.
  - `ui/login/LoginManualScreen.kt` — the existing manual form (host + password).
- Edit: `ui/login/LoginViewModel.kt` — add step + scan state + handlers.
- Edit: `di/AppContainer.kt` — construct and expose `lanScanner`.
- New tests:
  - `app/src/test/.../data/net/LanScannerTest.kt` — fake `NetworkInfoProvider` +
    fake `ServerProbe`: emits progress + results, excludes own IP, `NotOnLan` when
    no LAN, completes, cancellation stops probing.
  - Extend `app/src/test/.../ui/login/LoginViewModelTest.kt` — step transitions,
    auto-scan on open, results populate state, single-result auto-select,
    `onSelectServer` sets host, `submit()` calls `authRepo.login` with the selected
    baseUrl + password, password visibility toggle, button-enable logic.

No backend changes, no new dependencies, no new permissions, no nav-route changes.

## Testing

TDD: write the `LanScanner` orchestration tests and the extended
`LoginViewModelTest` cases first, against fakes, then implement. The probe
implementation (`HealthzServerProbe`) and `RealNetworkInfoProvider` touch real I/O
and the platform, so they stay thin behind their interfaces and are not unit-tested
(optionally exercised by a tiny local `ServerSocket` integration test if cheap).

## Open questions

None blocking. Accepted decisions:
- Scan is capped at the local /24 (≤254 hosts) for bounded latency.
- Discovery confirms agentic-dev via `GET /healthz` body == `ok`.
- The password is always typed by the user; discovery only resolves the host.
