# Login LAN Scan + Manual Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the login screen into a two-card chooser (Scan LAN / Manual entry) that auto-scans the local subnet for agentic-dev servers on port 7420 and lets the user pick one, keeping manual host entry as a second path — all in Material 3 Expressive.

**Architecture:** A new, dependency-injected `LanScanner` (data layer) discovers servers by sweeping the device's local /24, probing `:7420` with a raw socket + `GET /healthz` check, emitting results as a `Flow<ScanUpdate>`. `LoginViewModel` gains a `step` state machine (Chooser/Scan/Manual) plus scan state, collecting the scanner. The single `Login` nav destination renders one of three sub-composables via `AnimatedContent`. No backend, dependency, permission, or nav-route changes.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 Expressive (`androidx.compose.material3:material3:1.4.0-alpha18`), Ktor/OkHttp (existing), kotlinx-coroutines, JUnit4 + kotlinx-coroutines-test. Manual DI via `AppContainer`.

## Global Constraints

- **Material3 pinned at `1.4.0-alpha18`** — do not bump. Expressive APIs (`ContainedLoadingIndicator`, `LoadingIndicator`) require `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`; `TopAppBar`/`PullToRefreshBox` require `@OptIn(ExperimentalMaterial3Api::class)`.
- **No new Gradle dependencies, no new AndroidManifest permissions** (`ACCESS_NETWORK_STATE` and `INTERNET` already declared; `usesCleartextTraffic="true"` already set).
- **No backend changes.** Discovery reuses the existing unauthenticated `GET /healthz` (returns plaintext body `ok`).
- **No new navigation routes.** The auth gate in `AppNav` keys off `isLoggedIn`; the single `Login` destination is kept.
- **Port is fixed at 7420.** Scan is capped at the device's local /24 (≤254 hosts).
- **Discovery resolves the host only; the password is always typed by the user** (backend authenticates by password).
- **UI strings are inline English literals** (project has no `strings.xml`).
- **Build/test execution happens in the MAIN checkout, not this worktree** (the worktree has no Gradle wrapper / keystore). After committing+pushing the worktree branch to `master`, run in `~/src/agentic-dev-android`:
  - Unit tests: `git pull --ff-only origin master && ~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest`
  - Release APK: `~/.local/share/gradle-8.10.2/bin/gradle assembleRelease`
  - Because each test run needs a push+pull, TDD red/green is **confirmed at the checkpoints in Task 3 and Task 5** (and the final build in Task 10). Within each task, author the failing test before the implementation as the design discipline; the checkpoint run is where red→green is observed.
- **Forced dark theme**, blue family, `ExpressiveShapes`, `AppMotion` tokens — reuse, don't redefine.

---

### Task 1: Scanner model, interfaces, and candidate-IP enumeration

Pure, dependency-free building blocks for discovery. The only unit-testable logic here is `candidateIps`.

**Files:**
- Create: `app/src/main/java/dev/agentic/data/net/LanScanner.kt`
- Test: `app/src/test/java/dev/agentic/data/net/CandidateIpsTest.kt`

**Interfaces:**
- Produces:
  - `data class DiscoveredServer(val ip: String, val port: Int = 7420, val latencyMs: Long)` with `val baseUrl: String get() = "http://$ip:$port"`
  - `sealed interface ScanUpdate { Progress(scanned:Int,total:Int); Found(server:DiscoveredServer); Done; NotOnLan }`
  - `data class LocalNet(val ip: String, val prefixLen: Int)`
  - `interface NetworkInfoProvider { fun localNet(): LocalNet? }`
  - `interface ServerProbe { suspend fun probe(ip: String): DiscoveredServer? }`
  - `interface LanScanner { fun scan(): Flow<ScanUpdate> }`
  - `fun candidateIps(localNet: LocalNet): List<String>`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.agentic.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateIpsTest {

    @Test fun `enumerates the full 24 host range`() {
        val ips = candidateIps(LocalNet("192.168.1.50", 24))
        // 1..254 minus the device's own host octet (.50) = 253 candidates.
        assertEquals(253, ips.size)
        assertTrue(ips.contains("192.168.1.1"))
        assertTrue(ips.contains("192.168.1.254"))
    }

    @Test fun `excludes the device's own address`() {
        val ips = candidateIps(LocalNet("192.168.1.50", 24))
        assertFalse(ips.contains("192.168.1.50"))
    }

    @Test fun `excludes network and broadcast addresses`() {
        val ips = candidateIps(LocalNet("192.168.1.50", 24))
        assertFalse("network addr .0 must be excluded", ips.contains("192.168.1.0"))
        assertFalse("broadcast addr .255 must be excluded", ips.contains("192.168.1.255"))
    }

    @Test fun `caps a wide prefix at the local 24`() {
        // A /16 device IP still scans only the device's own /24, never 65k hosts.
        val ips = candidateIps(LocalNet("10.0.5.7", 16))
        assertTrue(ips.all { it.startsWith("10.0.5.") })
        assertEquals(253, ips.size)
    }

    @Test fun `malformed ip yields empty list`() {
        assertEquals(emptyList<String>(), candidateIps(LocalNet("not-an-ip", 24)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Per Global Constraints, this is confirmed at the Task 3 checkpoint. Expected when run: FAIL — `candidateIps` / `LocalNet` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `LanScanner.kt` with the models, interfaces, and `candidateIps` (the `DefaultLanScanner` body comes in Task 2):

```kotlin
package dev.agentic.data.net

import kotlinx.coroutines.flow.Flow

/** A discovered agentic-dev server on the LAN. */
data class DiscoveredServer(
    val ip: String,
    val port: Int = 7420,
    val latencyMs: Long,
) {
    /** Full base URL the auth layer expects, e.g. "http://192.168.1.10:7420". */
    val baseUrl: String get() = "http://$ip:$port"
}

/** Progressive updates emitted while scanning the LAN. */
sealed interface ScanUpdate {
    data class Progress(val scanned: Int, val total: Int) : ScanUpdate
    data class Found(val server: DiscoveredServer) : ScanUpdate
    data object Done : ScanUpdate
    data object NotOnLan : ScanUpdate
}

/** The device's local IPv4 address and subnet prefix length. */
data class LocalNet(val ip: String, val prefixLen: Int)

/** Resolves the device's local private-IPv4 network (null when not on a usable LAN). */
interface NetworkInfoProvider {
    fun localNet(): LocalNet?
}

/** Probes a single host: returns a [DiscoveredServer] iff it is an agentic-dev server. */
interface ServerProbe {
    suspend fun probe(ip: String): DiscoveredServer?
}

/** Scans the local LAN for agentic-dev servers, emitting results as they are found. */
interface LanScanner {
    fun scan(): Flow<ScanUpdate>
}

/**
 * Host addresses to probe for the /24 containing [localNet].ip, excluding the network address
 * (.0), the broadcast address (.255), and the device's own address. Capped at a /24 regardless
 * of the real prefix so a wide subnet can never blow up into thousands of probes.
 */
fun candidateIps(localNet: LocalNet): List<String> {
    val parts = localNet.ip.split(".")
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return emptyList()
    val base = "${parts[0]}.${parts[1]}.${parts[2]}"
    val own = parts[3].toInt()
    return (1..254).filter { it != own }.map { "$base.$it" }
}
```

- [ ] **Step 4: Verify (deferred to Task 3 checkpoint)**

Expected when run: the five `CandidateIpsTest` cases PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/agentic/data/net/LanScanner.kt \
        app/src/test/java/dev/agentic/data/net/CandidateIpsTest.kt
git commit -m "feat(login): add LAN scan models + candidate-IP enumeration"
```

---

### Task 2: `DefaultLanScanner` orchestration

Concurrent probe fan-out over the candidate list, emitting `Found`/`Progress` live and a terminal `Done` (or `NotOnLan`). Unit-tested with fake collaborators.

**Files:**
- Modify: `app/src/main/java/dev/agentic/data/net/LanScanner.kt` (append `DefaultLanScanner`)
- Test: `app/src/test/java/dev/agentic/data/net/DefaultLanScannerTest.kt`

**Interfaces:**
- Consumes: `candidateIps`, `NetworkInfoProvider`, `ServerProbe`, `LocalNet`, `ScanUpdate`, `DiscoveredServer` (Task 1).
- Produces: `class DefaultLanScanner(networkInfo: NetworkInfoProvider, probe: ServerProbe, dispatcher: CoroutineDispatcher = Dispatchers.IO, concurrency: Int = 48) : LanScanner`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.agentic.data.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNetworkInfoProvider(private val net: LocalNet?) : NetworkInfoProvider {
    override fun localNet(): LocalNet? = net
}

/** Records every probed ip; returns a hit (with the given latency) for ips in [hits]. */
private class FakeServerProbe(private val hits: Map<String, Long>) : ServerProbe {
    val calledIps: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun probe(ip: String): DiscoveredServer? {
        calledIps.add(ip)
        return hits[ip]?.let { DiscoveredServer(ip = ip, latencyMs = it) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLanScannerTest {

    @Test fun `emits NotOnLan and nothing else when there is no local network`() = runTest {
        val scanner = DefaultLanScanner(
            networkInfo = FakeNetworkInfoProvider(null),
            probe = FakeServerProbe(emptyMap()),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val updates = scanner.scan().toList()
        assertEquals(listOf(ScanUpdate.NotOnLan), updates)
    }

    @Test fun `finds the responding hosts, probes the whole 24 except own ip, and ends with Done`() = runTest {
        val probe = FakeServerProbe(mapOf("192.168.1.10" to 12L, "192.168.1.23" to 31L))
        val scanner = DefaultLanScanner(
            networkInfo = FakeNetworkInfoProvider(LocalNet("192.168.1.50", 24)),
            probe = probe,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            concurrency = 16,
        )

        val updates = scanner.scan().toList()

        val found = updates.filterIsInstance<ScanUpdate.Found>().map { it.server }
        assertEquals(setOf("http://192.168.1.10:7420", "http://192.168.1.23:7420"), found.map { it.baseUrl }.toSet())
        assertEquals(12L, found.first { it.ip == "192.168.1.10" }.latencyMs)

        assertTrue("must end with Done", updates.last() == ScanUpdate.Done)
        assertEquals("probes every host in the /24 except its own", 253, probe.calledIps.size)
        assertFalse("never probes its own ip", probe.calledIps.contains("192.168.1.50"))

        val lastProgress = updates.filterIsInstance<ScanUpdate.Progress>().last()
        assertEquals(253, lastProgress.scanned)
        assertEquals(253, lastProgress.total)
    }
}
```

- [ ] **Step 2: Run test to verify it fails** — deferred to Task 3 checkpoint. Expected: FAIL — `DefaultLanScanner` unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `LanScanner.kt`:

```kotlin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Default [LanScanner]: enumerates the local /24, probes each host on a bounded thread pool, and
 * emits each [ScanUpdate.Found] the instant it is discovered (so the UI fills in live) plus a
 * [ScanUpdate.Progress] per completed probe, then a terminal [ScanUpdate.Done]. Emits
 * [ScanUpdate.NotOnLan] (and nothing else) when no usable local network is found.
 *
 * channelFlow's [send] is concurrency-safe, so the fan-out coroutines can publish freely.
 * Probing runs inside a [coroutineScope] so the flow only completes once every probe has finished,
 * and cancelling the collector cancels all in-flight probes (structured concurrency).
 */
class DefaultLanScanner(
    private val networkInfo: NetworkInfoProvider,
    private val probe: ServerProbe,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val concurrency: Int = 48,
) : LanScanner {
    override fun scan(): Flow<ScanUpdate> = channelFlow {
        val net = networkInfo.localNet()
        if (net == null) {
            send(ScanUpdate.NotOnLan)
            return@channelFlow
        }
        val candidates = candidateIps(net)
        val total = candidates.size
        if (total == 0) {
            send(ScanUpdate.Done)
            return@channelFlow
        }
        val scanned = AtomicInteger(0)
        val gate = Semaphore(concurrency)
        coroutineScope {
            candidates.forEach { ip ->
                launch(dispatcher) {
                    gate.withPermit {
                        probe.probe(ip)?.let { send(ScanUpdate.Found(it)) }
                    }
                    send(ScanUpdate.Progress(scanned.incrementAndGet(), total))
                }
            }
        }
        send(ScanUpdate.Done)
    }
}
```

- [ ] **Step 4: Verify (deferred to Task 3 checkpoint)** — Expected: both `DefaultLanScannerTest` cases PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/agentic/data/net/LanScanner.kt \
        app/src/test/java/dev/agentic/data/net/DefaultLanScannerTest.kt
git commit -m "feat(login): add DefaultLanScanner concurrent probe orchestration"
```

---

### Task 3: Platform discovery implementations + first test checkpoint

The real `NetworkInfoProvider` and `ServerProbe`. These touch real I/O and the platform, so they are not unit-tested; they stay thin and are verified by the build. This task ends with the **first checkpoint** that actually runs the Task 1–3 unit tests.

**Files:**
- Create: `app/src/main/java/dev/agentic/data/net/LanDiscoveryImpl.kt`

**Interfaces:**
- Consumes: `NetworkInfoProvider`, `ServerProbe`, `LocalNet`, `DiscoveredServer` (Task 1).
- Produces: `class RealNetworkInfoProvider : NetworkInfoProvider`; `class HealthzServerProbe(connectTimeoutMs: Int = 400, readTimeoutMs: Int = 600) : ServerProbe`.

- [ ] **Step 1: Write the implementation** (no unit test — pure platform/socket I/O)

```kotlin
package dev.agentic.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Resolves the device's local private-IPv4 network from the up, non-loopback interfaces,
 * preferring Wi-Fi (`wlan*`). Returns null when only loopback / mobile / VPN-CGNAT interfaces
 * are present. [Inet4Address.isSiteLocalAddress] is true for 10/8, 172.16/12, 192.168/16 and
 * FALSE for the Tailscale CGNAT range 100.64/10 — so a Tailscale interface is never picked.
 */
class RealNetworkInfoProvider : NetworkInfoProvider {
    override fun localNet(): LocalNet? {
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            // Probe wlan* first so a phone with both Wi-Fi and another up interface picks Wi-Fi.
            .sortedByDescending { (it.name ?: "").startsWith("wlan") }
        for (iface in ifaces) {
            for (addr in iface.interfaceAddresses) {
                val ip = addr.address
                if (ip is Inet4Address && ip.isSiteLocalAddress) {
                    val host = ip.hostAddress ?: continue
                    return LocalNet(host, addr.networkPrefixLength.toInt())
                }
            }
        }
        return null
    }
}

/**
 * Confirms a host is an agentic-dev server by opening a raw TCP socket to ip:7420 (short connect
 * timeout) and issuing a minimal HTTP/1.0 `GET /healthz`. Accepts the host only when the response
 * is `200` with a body that trims to `ok` — the backend's healthz contract — so an unrelated
 * service occupying 7420 is rejected. Records round-trip latency. Returns null on any failure.
 *
 * Uses a raw socket rather than the shared KtorAgenticApi: KtorAgenticApi has a single mutable
 * baseUrl and cannot be pointed at 250 hosts in parallel. HTTP/1.0 + `Connection: close` makes the
 * server close the socket so readBytes() terminates; soTimeout guards a stalled read.
 */
class HealthzServerProbe(
    private val connectTimeoutMs: Int = 400,
    private val readTimeoutMs: Int = 600,
    private val port: Int = 7420,
) : ServerProbe {
    override suspend fun probe(ip: String): DiscoveredServer? = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().apply {
                    write("GET /healthz HTTP/1.0\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val resp = socket.getInputStream().readBytes().decodeToString()
                val statusLine = resp.substringBefore("\r\n")
                val body = resp.substringAfter("\r\n\r\n", "").trim()
                val ok = statusLine.startsWith("HTTP/1.") && statusLine.contains(" 200") && body == "ok"
                if (ok) {
                    DiscoveredServer(ip = ip, port = port, latencyMs = (System.nanoTime() - startNanos) / 1_000_000)
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
```

- [ ] **Step 2: Commit the implementation**

```bash
git add app/src/main/java/dev/agentic/data/net/LanDiscoveryImpl.kt
git commit -m "feat(login): real LAN network-info provider + healthz socket probe"
```

- [ ] **Step 3: Push the branch to master**

```bash
git push origin HEAD:master   # if rejected: git pull --rebase origin master && git push origin HEAD:master
```

- [ ] **Step 4: CHECKPOINT — run the data-layer unit tests in the main checkout**

```bash
cd ~/src/agentic-dev-android && git pull --ff-only origin master && \
  ~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest --tests "dev.agentic.data.net.*"
```
Expected: BUILD SUCCESSFUL — `CandidateIpsTest` (5) and `DefaultLanScannerTest` (2) PASS, and the new `LanDiscoveryImpl.kt` compiles. If a test fails or a symbol doesn't compile, fix in the worktree, re-commit, re-push, and re-run this checkpoint before continuing.

---

### Task 4: Extend `LoginViewModel` with the step machine + scan state

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/login/LoginViewModel.kt`
- Modify: `app/src/test/java/dev/agentic/ui/login/LoginViewModelTest.kt`
- Create: `app/src/test/java/dev/agentic/data/FakeLanScanner.kt`

**Interfaces:**
- Consumes: `AuthRepository.login(host,password): Outcome<Unit>` (existing), `LanScanner.scan(): Flow<ScanUpdate>` (Task 1), `ScanUpdate`, `DiscoveredServer`.
- Produces: `enum class LoginStep { Chooser, Scan, Manual }`; an extended `LoginUiState`; `LoginViewModel(authRepo, lanScanner, initialHost = "")` with handlers `goTo(step)`, `back()`, `startScan()`, `rescan()`, `onSelectServer(baseUrl)`, `onHost(s)`, `onPassword(s)`, `togglePasswordVisible()`, `submit()`.

- [ ] **Step 1: Write the failing tests**

First add the fake scanner — `app/src/test/java/dev/agentic/data/FakeLanScanner.kt`:

```kotlin
package dev.agentic.data

import dev.agentic.data.net.LanScanner
import dev.agentic.data.net.ScanUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/** In-memory [LanScanner] for unit tests: replays a scripted list of updates per scan() call. */
class FakeLanScanner(var updates: List<ScanUpdate> = listOf(ScanUpdate.Done)) : LanScanner {
    var scanCount = 0
    override fun scan(): Flow<ScanUpdate> {
        scanCount++
        return updates.asFlow()
    }
}
```

Then rewrite `LoginViewModelTest.kt` — keep the existing cases but route submit through Manual, and add scan cases. Full file:

```kotlin
package dev.agentic.ui.login

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.FakeLanScanner
import dev.agentic.data.FakeSettingsStore
import dev.agentic.data.net.DiscoveredServer
import dev.agentic.data.net.ScanUpdate
import dev.agentic.data.repo.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var api: FakeAgenticApi
    private lateinit var settings: FakeSettingsStore
    private lateinit var scanner: FakeLanScanner
    private lateinit var repoScope: CoroutineScope

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        api = FakeAgenticApi()
        settings = FakeSettingsStore()
        scanner = FakeLanScanner()
        repoScope = CoroutineScope(dispatcher)
    }

    @After fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        repoScope.cancel()
    }

    private fun makeRepo() = AuthRepository(api, settings, repoScope)
    private fun makeVm(initialHost: String = "") = LoginViewModel(makeRepo(), scanner, initialHost)

    private fun server(ip: String, ms: Long) = DiscoveredServer(ip = ip, latencyMs = ms)

    // ── initial state ──────────────────────────────────────────────────────────

    @Test fun `initial state starts on the chooser, empty fields, not done`() = runTest(dispatcher) {
        val vm = makeVm()
        val s = vm.uiState.value
        assertEquals(LoginStep.Chooser, s.step)
        assertEquals("", s.password)
        assertFalse(s.busy)
        assertNull(s.error)
        assertFalse(s.done)
    }

    @Test fun `initialHost prefills the manual host field`() = runTest(dispatcher) {
        val vm = makeVm(initialHost = "http://1.2.3.4:7420")
        assertEquals("http://1.2.3.4:7420", vm.uiState.value.host)
    }

    // ── navigation ───────────────────────────────────────────────────────────

    @Test fun `goTo and back move between steps`() = runTest(dispatcher) {
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        assertEquals(LoginStep.Manual, vm.uiState.value.step)
        vm.back()
        assertEquals(LoginStep.Chooser, vm.uiState.value.step)
    }

    // ── auto-scan on open ──────────────────────────────────────────────────────

    @Test fun `scan runs automatically on construction and populates results`() = runTest(dispatcher) {
        scanner.updates = listOf(
            ScanUpdate.Found(server("192.168.1.10", 12)),
            ScanUpdate.Found(server("192.168.1.23", 31)),
            ScanUpdate.Progress(253, 253),
            ScanUpdate.Done,
        )
        val vm = makeVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(1, scanner.scanCount)
        assertFalse("scanning ends after Done", s.scanning)
        assertEquals(listOf("http://192.168.1.10:7420", "http://192.168.1.23:7420"), s.results.map { it.baseUrl })
    }

    @Test fun `results are sorted by latency ascending`() = runTest(dispatcher) {
        scanner.updates = listOf(
            ScanUpdate.Found(server("192.168.1.23", 31)),
            ScanUpdate.Found(server("192.168.1.10", 12)),
            ScanUpdate.Done,
        )
        val vm = makeVm()
        advanceUntilIdle()
        assertEquals(listOf("192.168.1.10", "192.168.1.23"), vm.uiState.value.results.map { it.ip })
    }

    @Test fun `a single result is auto-selected on Done`() = runTest(dispatcher) {
        scanner.updates = listOf(ScanUpdate.Found(server("192.168.1.10", 12)), ScanUpdate.Done)
        val vm = makeVm()
        advanceUntilIdle()
        assertEquals("http://192.168.1.10:7420", vm.uiState.value.selectedHost)
    }

    @Test fun `multiple results are not auto-selected`() = runTest(dispatcher) {
        scanner.updates = listOf(
            ScanUpdate.Found(server("192.168.1.10", 12)),
            ScanUpdate.Found(server("192.168.1.23", 31)),
            ScanUpdate.Done,
        )
        val vm = makeVm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedHost)
    }

    @Test fun `NotOnLan sets the flag and stops scanning`() = runTest(dispatcher) {
        scanner.updates = listOf(ScanUpdate.NotOnLan)
        val vm = makeVm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notOnLan)
        assertFalse(vm.uiState.value.scanning)
    }

    @Test fun `rescan re-runs the scanner and clears prior results`() = runTest(dispatcher) {
        scanner.updates = listOf(ScanUpdate.Found(server("192.168.1.10", 12)), ScanUpdate.Done)
        val vm = makeVm()
        advanceUntilIdle()
        vm.rescan()
        advanceUntilIdle()
        assertEquals(2, scanner.scanCount)
        assertEquals(1, vm.uiState.value.results.size)
    }

    @Test fun `onSelectServer sets the selected host`() = runTest(dispatcher) {
        val vm = makeVm()
        vm.onSelectServer("http://192.168.1.42:7420")
        assertEquals("http://192.168.1.42:7420", vm.uiState.value.selectedHost)
    }

    // ── password visibility ──────────────────────────────────────────────────

    @Test fun `togglePasswordVisible flips the flag`() = runTest(dispatcher) {
        val vm = makeVm()
        assertFalse(vm.uiState.value.passwordVisible)
        vm.togglePasswordVisible()
        assertTrue(vm.uiState.value.passwordVisible)
    }

    // ── submit (manual) ──────────────────────────────────────────────────────

    @Test fun `manual submit success sets done`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        vm.onHost("http://host"); vm.onPassword("pass")
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.done); assertFalse(s.busy); assertNull(s.error)
    }

    @Test fun `manual submit failure sets a clean host hint`() = runTest(dispatcher) {
        api.loginException = java.io.IOException("network down")
        val vm = makeVm()
        vm.goTo(LoginStep.Manual)
        vm.onHost("http://host"); vm.onPassword("pass"); vm.submit()
        advanceUntilIdle()
        val err = vm.uiState.value.error ?: ""
        assertFalse(s_leak(err))
        assertTrue(err.contains("reach the server", ignoreCase = true))
        assertFalse(vm.uiState.value.done)
    }
    private fun s_leak(err: String) = err.contains("AppError") || err.contains("Network(")

    // ── submit (scan) ──────────────────────────────────────────────────────────

    @Test fun `scan submit logs in with the selected server's baseUrl`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val vm = makeVm()
        vm.goTo(LoginStep.Scan)
        vm.onSelectServer("http://192.168.1.10:7420")
        vm.onPassword("pass")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("http://192.168.1.10:7420", settings.host) // AuthRepository.login persisted it
    }

    @Test fun `scan submit with no selection is a no-op`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val vm = makeVm()
        vm.goTo(LoginStep.Scan)
        vm.onPassword("pass")
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.done)
        assertFalse(vm.uiState.value.busy)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail** — deferred to the Task 5 checkpoint. Expected: FAIL — `LoginStep`, `scanner` ctor param, `goTo`, scan fields unresolved.

- [ ] **Step 3: Write the implementation**

Replace `LoginViewModel.kt` with:

```kotlin
package dev.agentic.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.agentic.data.net.AppError
import dev.agentic.data.net.DiscoveredServer
import dev.agentic.data.net.LanScanner
import dev.agentic.data.net.Outcome
import dev.agentic.data.net.ScanUpdate
import dev.agentic.data.net.userMessage
import dev.agentic.data.repo.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which login sub-screen is showing. */
enum class LoginStep { Chooser, Scan, Manual }

data class LoginUiState(
    val step: LoginStep = LoginStep.Chooser,
    // scan
    val scanning: Boolean = false,
    val scanProgress: Float? = null,            // 0f..1f, null = indeterminate
    val results: List<DiscoveredServer> = emptyList(),
    val selectedHost: String? = null,           // baseUrl of the chosen server
    val notOnLan: Boolean = false,
    // manual
    val host: String = "",
    // shared
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class LoginViewModel(
    private val authRepo: AuthRepository,
    private val lanScanner: LanScanner,
    initialHost: String = "",
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(host = initialHost))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init { startScan() }

    fun goTo(step: LoginStep) { _uiState.update { it.copy(step = step) } }
    fun back() { _uiState.update { it.copy(step = LoginStep.Chooser) } }

    /** (Re)start a LAN scan, collecting the scanner flow into ui state. */
    fun startScan() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(scanning = true, scanProgress = null, results = emptyList(),
                    selectedHost = null, notOnLan = false)
        }
        scanJob = viewModelScope.launch {
            lanScanner.scan().collect { update ->
                when (update) {
                    is ScanUpdate.Found -> _uiState.update { st ->
                        val merged = (st.results + update.server)
                            .distinctBy { it.baseUrl }
                            .sortedBy { it.latencyMs }
                        st.copy(results = merged)
                    }
                    is ScanUpdate.Progress -> _uiState.update {
                        it.copy(scanProgress = if (update.total > 0) update.scanned.toFloat() / update.total else null)
                    }
                    ScanUpdate.Done -> _uiState.update { st ->
                        val sole = st.results.singleOrNull()?.baseUrl
                        st.copy(scanning = false, selectedHost = st.selectedHost ?: sole)
                    }
                    ScanUpdate.NotOnLan -> _uiState.update { it.copy(scanning = false, notOnLan = true) }
                }
            }
        }
    }

    fun rescan() = startScan()
    fun onSelectServer(baseUrl: String) { _uiState.update { it.copy(selectedHost = baseUrl) } }
    fun onHost(s: String) { _uiState.update { it.copy(host = s) } }
    fun onPassword(s: String) { _uiState.update { it.copy(password = s) } }
    fun togglePasswordVisible() { _uiState.update { it.copy(passwordVisible = !it.passwordVisible) } }

    /**
     * Log in against the host implied by the current step: the typed [LoginUiState.host] in Manual,
     * or the selected discovered server in Scan. A Scan submit with no selection is a no-op.
     */
    fun submit() {
        val state = _uiState.value
        val host = when (state.step) {
            LoginStep.Manual -> state.host
            else -> state.selectedHost ?: return
        }
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val outcome = authRepo.login(host, state.password)) {
                is Outcome.Success -> _uiState.update { it.copy(busy = false, done = true) }
                is Outcome.Failure -> _uiState.update {
                    it.copy(busy = false, error = loginErrorMessage(outcome.error))
                }
            }
        }
    }

    private fun loginErrorMessage(e: AppError): String = when {
        e is AppError.Http && e.code == 401 -> "Wrong password."
        e is AppError.Network -> "Can't reach the server — check the host address and your connection."
        else -> e.userMessage()
    }
}
```

- [ ] **Step 4: Verify (deferred to Task 5 checkpoint)** — Expected: all `LoginViewModelTest` cases PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/login/LoginViewModel.kt \
        app/src/test/java/dev/agentic/ui/login/LoginViewModelTest.kt \
        app/src/test/java/dev/agentic/data/FakeLanScanner.kt
git commit -m "feat(login): LoginViewModel step machine + scan state"
```

---

### Task 5: Wire `LanScanner` into the DI container + second test checkpoint

**Files:**
- Modify: `app/src/main/java/dev/agentic/di/AppContainer.kt`

**Interfaces:**
- Consumes: `DefaultLanScanner`, `RealNetworkInfoProvider`, `HealthzServerProbe` (Tasks 2–3), `LanScanner` (Task 1).
- Produces: `AppContainer.lanScanner: LanScanner`.

- [ ] **Step 1: Add the property**

In `AppContainer.kt`, add the imports and the property (place it near the other data-layer members, after `filesRepo`):

```kotlin
import dev.agentic.data.net.DefaultLanScanner
import dev.agentic.data.net.HealthzServerProbe
import dev.agentic.data.net.LanScanner
import dev.agentic.data.net.RealNetworkInfoProvider
```

```kotlin
    val lanScanner: LanScanner = DefaultLanScanner(RealNetworkInfoProvider(), HealthzServerProbe())
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/dev/agentic/di/AppContainer.kt
git commit -m "feat(login): provide LanScanner in AppContainer"
```

- [ ] **Step 3: Push and run the full unit-test suite (CHECKPOINT)**

```bash
git push origin HEAD:master   # rebase+push if rejected
cd ~/src/agentic-dev-android && git pull --ff-only origin master && \
  ~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL — all unit tests pass, including the full `LoginViewModelTest` and the Task 1–2 scanner tests. Fix any failure in the worktree, re-push, and re-run before starting the UI tasks.

---

### Task 6: `LoginManualScreen` composable (extract the current form)

The manual path keeps today's behavior: a full Host-URL field + password, plus the new show/hide toggle. Stateless: takes state + callbacks.

**Files:**
- Create: `app/src/main/java/dev/agentic/ui/login/LoginManualScreen.kt`

**Interfaces:**
- Consumes: `LoginUiState` (Task 4).
- Produces: `@Composable fun LoginManualScreen(state: LoginUiState, onHost: (String)->Unit, onPassword: (String)->Unit, onTogglePassword: ()->Unit, onSubmit: ()->Unit, onBack: ()->Unit)`.

- [ ] **Step 1: Write the composable**

```kotlin
package dev.agentic.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginManualScreen(
    state: LoginUiState,
    onHost: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manual entry") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.host,
                onValueChange = onHost,
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(state, onPassword, onTogglePassword)
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onSubmit,
                enabled = !state.busy && state.host.isNotBlank() && state.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "…" else "Log in") }
        }
    }
}

/** Shared password field with a show/hide trailing toggle — used by manual and scan screens. */
@Composable
fun PasswordField(
    state: LoginUiState,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = state.password,
        onValueChange = onPassword,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onTogglePassword) {
                Icon(
                    if (state.passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (state.passwordVisible) "Hide password" else "Show password",
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
```

- [ ] **Step 2: Commit** (compile verified at the Task 10 build)

```bash
git add app/src/main/java/dev/agentic/ui/login/LoginManualScreen.kt
git commit -m "feat(login): manual-entry sub-screen with password show/hide toggle"
```

---

### Task 7: `LoginChooser` composable (two cards + live scan status)

**Files:**
- Create: `app/src/main/java/dev/agentic/ui/login/LoginChooser.kt`

**Interfaces:**
- Consumes: `LoginUiState` (Task 4).
- Produces: `@Composable fun LoginChooser(state: LoginUiState, onScan: ()->Unit, onManual: ()->Unit)`.

- [ ] **Step 1: Write the composable**

```kotlin
package dev.agentic.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginChooser(
    state: LoginUiState,
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp),
            )
            Text("agentic-dev", style = MaterialTheme.typography.headlineMedium)

            ChooserCard(
                icon = Icons.Rounded.Wifi,
                title = "Scan LAN",
                subtitle = scanSubtitle(state),
                showSpinner = state.scanning,
                onClick = onScan,
            )
            ChooserCard(
                icon = Icons.Rounded.Keyboard,
                title = "Manual entry",
                subtitle = "Type the host address yourself",
                showSpinner = false,
                onClick = onManual,
            )
        }
    }
}

private fun scanSubtitle(state: LoginUiState): String = when {
    state.notOnLan -> "Not on a local network"
    state.scanning -> "Scanning…"
    state.results.isEmpty() -> "No servers found"
    else -> "Found ${state.results.size} server(s)"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChooserCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    showSpinner: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(Modifier.fillMaxWidth(if (showSpinner) 0.8f else 1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showSpinner) LoadingIndicator()
        }
    }
}
```

Build-fallback note: if `LoadingIndicator()` has a different signature in this alpha, substitute `androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/login/LoginChooser.kt
git commit -m "feat(login): two-card chooser with live scan status"
```

---

### Task 8: `LoginScanScreen` composable (results list + selection + connect)

**Files:**
- Create: `app/src/main/java/dev/agentic/ui/login/LoginScanScreen.kt`

**Interfaces:**
- Consumes: `LoginUiState`, `DiscoveredServer` (Tasks 1, 4), `PasswordField` (Task 6).
- Produces: `@Composable fun LoginScanScreen(state, onSelect:(String)->Unit, onPassword:(String)->Unit, onTogglePassword:()->Unit, onRescan:()->Unit, onSubmit:()->Unit, onManual:()->Unit, onBack:()->Unit)`.

- [ ] **Step 1: Write the composable**

```kotlin
package dev.agentic.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.agentic.data.net.DiscoveredServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScanScreen(
    state: LoginUiState,
    onSelect: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onRescan: () -> Unit,
    onSubmit: () -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan LAN") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onRescan, enabled = !state.scanning) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (state.scanning) {
                if (state.scanProgress != null) {
                    LinearProgressIndicator(progress = { state.scanProgress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            when {
                state.notOnLan -> EmptyState("Not on a local network", "Connect to Wi-Fi, or enter the host manually.", onManual)
                !state.scanning && state.results.isEmpty() ->
                    EmptyState("No servers found", "Make sure the server is running on port 7420.", onManual)
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.results, key = { it.baseUrl }) { server ->
                        ServerRow(server, selected = server.baseUrl == state.selectedHost, onSelect = { onSelect(server.baseUrl) })
                    }
                }
            }

            // Password + connect appear once at least one server is present.
            if (state.results.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PasswordField(state, onPassword, onTogglePassword)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = !state.busy && state.selectedHost != null && state.password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.busy) "…" else "Connect") }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(server: DiscoveredServer, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        leadingContent = { Icon(Icons.Rounded.Computer, contentDescription = null) },
        headlineContent = { Text("${server.ip}:${server.port}") },
        supportingContent = { Text("${server.latencyMs} ms") },
        trailingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
    )
}

@Composable
private fun EmptyState(title: String, body: String, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onManual) { Text("Enter manually") }
    }
}
```

Note: this uses the always-present `LinearProgressIndicator` (stable) for scan progress rather than the experimental contained indicator, to keep the build robust. The expressive `LoadingIndicator` already appears on the chooser card (Task 7).

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/login/LoginScanScreen.kt
git commit -m "feat(login): scan results sub-screen with radio selection + connect"
```

---

### Task 9: `LoginScreen` shell — step router + back handling + VM wiring

Rewrite the existing `LoginScreen.kt` to construct the extended VM and route between the three sub-screens with `AnimatedContent` (slide, reusing `AppMotion`), wiring `BackHandler` on sub-screens.

**Files:**
- Modify: `app/src/main/java/dev/agentic/ui/login/LoginScreen.kt`

**Interfaces:**
- Consumes: `LoginViewModel` (Task 4), `LoginChooser` (Task 7), `LoginScanScreen` (Task 8), `LoginManualScreen` (Task 6), `appContainer()` + `AppContainer.lanScanner`/`settings` (Task 5), `AppMotion` (existing).
- Produces: `@Composable fun LoginScreen(onLoggedIn: () -> Unit, vm: LoginViewModel? = null)` — same signature `AppNav` already calls.

- [ ] **Step 1: Write the implementation**

```kotlin
package dev.agentic.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentic.di.appContainer
import dev.agentic.ui.AppMotion

/**
 * Login shell. Builds the [LoginViewModel] (with the LAN scanner + last-used host) and routes
 * between the Chooser, Scan, and Manual sub-screens held in [LoginUiState.step]. Navigation out
 * still fires via LaunchedEffect(done) so the host never observes VM state.
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    vm: LoginViewModel? = null,
) {
    val container = appContainer()
    val resolvedVm: LoginViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { LoginViewModel(container.authRepo, container.lanScanner, container.settings.host) }
        },
    )
    val s by resolvedVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(s.done) { if (s.done) onLoggedIn() }

    // Sub-screens hand back to the chooser on system-back.
    BackHandler(enabled = s.step != LoginStep.Chooser) { resolvedVm.back() }

    val forward = s.step != LoginStep.Chooser
    AnimatedContent(
        targetState = s.step,
        transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(AppMotion.DurationNav, easing = AppMotion.Emphasized)) { dir * it })
                .togetherWith(slideOutHorizontally(tween(AppMotion.DurationNav, easing = AppMotion.Emphasized)) { -dir * it })
        },
        label = "login-step",
    ) { step ->
        when (step) {
            LoginStep.Chooser -> LoginChooser(
                state = s,
                onScan = { resolvedVm.goTo(LoginStep.Scan) },
                onManual = { resolvedVm.goTo(LoginStep.Manual) },
            )
            LoginStep.Scan -> LoginScanScreen(
                state = s,
                onSelect = resolvedVm::onSelectServer,
                onPassword = resolvedVm::onPassword,
                onTogglePassword = resolvedVm::togglePasswordVisible,
                onRescan = resolvedVm::rescan,
                onSubmit = resolvedVm::submit,
                onManual = { resolvedVm.goTo(LoginStep.Manual) },
                onBack = resolvedVm::back,
            )
            LoginStep.Manual -> LoginManualScreen(
                state = s,
                onHost = resolvedVm::onHost,
                onPassword = resolvedVm::onPassword,
                onTogglePassword = resolvedVm::togglePasswordVisible,
                onSubmit = resolvedVm::submit,
                onBack = resolvedVm::back,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/dev/agentic/ui/login/LoginScreen.kt
git commit -m "feat(login): step router shell with animated transitions + back handling"
```

---

### Task 10: Build, verify, and deliver the signed APK

Final integration: push everything, build the release APK in the main checkout (this compiles all UI from Tasks 6–9), and deliver it.

**Files:** none (build + delivery only).

- [ ] **Step 1: Push the branch to master**

```bash
git push origin HEAD:master   # rebase+push if rejected
```

- [ ] **Step 2: Build the release APK in the main checkout**

```bash
cd ~/src/agentic-dev-android && git pull --ff-only origin master && \
  ~/.local/share/gradle-8.10.2/bin/gradle :app:testDebugUnitTest assembleRelease
```
Expected: BUILD SUCCESSFUL — all unit tests pass and `app/build/outputs/apk/release/app-release.apk` is produced. If the UI fails to compile (e.g. an experimental-API signature mismatch), apply the build-fallback note from Task 7/8 in the worktree, re-commit, re-push, and re-run.

- [ ] **Step 3: Deliver the APK to the worktree outbox**

```bash
mkdir -p <worktree>/outbox && \
  cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk \
     "<worktree>/outbox/$(date +%Y%m%d-%H%M).apk"
```
(Replace `<worktree>` with this session's worktree path.) Expected: a timestamped `.apk` appears in `outbox/` for the user to download and install.

- [ ] **Step 4: Manual smoke test (user, on-device)**

On a phone on the same Wi-Fi as a running agentic-dev server: open the app → chooser shows, "Scan LAN" card flips from "Scanning…" to "Found N server(s)" → tap it → the server appears with its latency → select it, type the password, Connect → lands on Home. Also verify Manual entry still logs in.

---

## Self-Review

**1. Spec coverage:**
- Two-card chooser + drill-down → Tasks 7, 9. ✓
- Auto-scan on open → Task 4 (`init { startScan() }`), surfaced in Task 7. ✓
- LAN /24 sweep, port 7420, exclude own IP, cap at /24 → Task 1 (`candidateIps`) + Task 2. ✓
- `/healthz` body==`ok` probe via raw socket, latency → Task 3. ✓
- Avoid Tailscale CGNAT, prefer wlan, site-local only → Task 3 (`RealNetworkInfoProvider`). ✓
- Live, incremental results + progress → Task 2 (`send` per find/progress), Task 4 (state merge). ✓
- Results row: IP:port + latency + radio single-select; single result auto-select → Tasks 8, 4. ✓
- Password always typed, show/hide toggle → Tasks 4, 6 (`PasswordField`). ✓
- Manual unchanged, prefilled with last host → Tasks 6, 9 (`container.settings.host`). ✓
- Empty / not-on-LAN states → Task 8 (`EmptyState`). ✓
- Rescan (top-bar action) → Task 8. (Pull-to-refresh from the spec was dropped in favor of the top-bar Refresh action to avoid an experimental-API build risk; rescan is still available — noted here as the one intentional reduction.)
- MD3E components, no new deps/permissions/routes/backend → Global Constraints + all tasks. ✓
- No new nav route; single `Login` destination kept → Task 9 (same signature). ✓

**2. Placeholder scan:** No TBD/TODO; every code step shows complete code. The `<worktree>` token in Task 10 Step 3 is an explicit substitution instruction, not a placeholder.

**3. Type consistency:** `DiscoveredServer.baseUrl`, `ScanUpdate.{Found,Progress,Done,NotOnLan}`, `LanScanner.scan()`, `LoginStep`, and the VM handler names (`goTo`, `back`, `startScan`, `rescan`, `onSelectServer`, `onHost`, `onPassword`, `togglePasswordVisible`, `submit`) are used identically across Tasks 4, 6, 7, 8, 9. `PasswordField(state, onPassword, onTogglePassword)` defined in Task 6, consumed in Task 8. ✓

**Deviation log:** Pull-to-refresh (spec §MD3E components) replaced by a top-bar Refresh `IconButton` to avoid depending on the experimental `PullToRefreshBox` at build time. Scan progress uses the stable `LinearProgressIndicator`; the expressive `LoadingIndicator` is used on the chooser card. These keep the release build robust without losing any user-facing capability.
