package dev.agentic.data.repo

import dev.agentic.data.FakeAgenticApi
import dev.agentic.data.FakeSettingsStore
import dev.agentic.data.net.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// UnconfinedTestDispatcher lets the internal scope.launch run eagerly (no virtual-time advancing needed) without leaking into runTest's uncompleted-coroutine check.
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repoScope: CoroutineScope
    private lateinit var api: FakeAgenticApi
    private lateinit var settings: FakeSettingsStore

    @Before fun setUp() {
        repoScope = CoroutineScope(dispatcher)
        api = FakeAgenticApi()
        settings = FakeSettingsStore()
    }

    @After fun tearDown() {
        repoScope.cancel()
    }

    private fun repo() = AuthRepository(api, settings, repoScope)


    @Test fun `initial state is logged out`() = runTest(dispatcher) {
        val r = repo()
        assertNull(r.token.value)
        assertFalse(r.isLoggedIn.value)
    }


    @Test fun `login success sets token in StateFlow and api token`() = runTest(dispatcher) {
        api.loginTokenResult = "server-token"
        val r = repo()

        val outcome = r.login("http://myhost", "secret")

        assertTrue("expected Success but got $outcome", outcome is Outcome.Success)
        assertEquals("server-token", r.token.value)
        assertEquals("server-token", api.token)
        assertTrue(r.isLoggedIn.value)
    }

    @Test fun `login trims host and persists it to settings`() = runTest(dispatcher) {
        val r = repo()
        r.login("  http://myhost  ", "secret")
        assertEquals("http://myhost", settings.host)
    }

    @Test fun `login persists token to settings`() = runTest(dispatcher) {
        api.loginTokenResult = "saved-token"
        val r = repo()
        r.login("http://myhost", "secret")
        assertEquals("saved-token", settings.token.value)
    }


    @Test fun `login failure returns Failure and leaves token null`() = runTest(dispatcher) {
        api.loginException = java.io.IOException("network down")
        val r = repo()

        val outcome = r.login("http://myhost", "secret")

        assertTrue("expected Failure but got $outcome", outcome is Outcome.Failure)
        assertNull(r.token.value)
        assertFalse(r.isLoggedIn.value)
    }


    @Test fun `logout clears token in StateFlow and settings and api`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val r = repo()
        r.login("http://myhost", "secret")

        r.logout()

        assertNull(r.token.value)
        assertNull(api.token)
        assertNull(settings.token.value)
        assertFalse(r.isLoggedIn.value)
    }


    @Test fun `onUnauthorized callback triggers logout`() = runTest(dispatcher) {
        api.loginTokenResult = "tok"
        val r = repo()
        r.login("http://myhost", "secret")
        api.onUnauthorized?.invoke()

        assertNull(r.token.value)
        assertFalse(r.isLoggedIn.value)
    }
}
