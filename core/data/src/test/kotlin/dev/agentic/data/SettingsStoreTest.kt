package dev.agentic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {

    private fun makeStore(initialHost: String = "http://localhost:7420"): FakeSettingsStore =
        FakeSettingsStore(initialHost = initialHost)

    @Test
    fun `token starts null`() {
        val store = makeStore()
        assertNull(store.token.value)
    }

    @Test
    fun `setToken updates token StateFlow value`() {
        val store = makeStore()
        store.setToken("my-secret-token")
        assertEquals("my-secret-token", store.token.value)
    }

    @Test
    fun `setToken to null clears token`() {
        val store = makeStore()
        store.setToken("some-token")
        store.setToken(null)
        assertNull(store.token.value)
    }

    @Test
    fun `host returns initial value`() {
        val store = makeStore(initialHost = "http://10.0.2.2:7420")
        assertEquals("http://10.0.2.2:7420", store.host)
    }

    @Test
    fun `setHost updates host`() {
        val store = makeStore()
        store.setHost("http://192.168.1.5:7420")
        assertEquals("http://192.168.1.5:7420", store.host)
    }
}
