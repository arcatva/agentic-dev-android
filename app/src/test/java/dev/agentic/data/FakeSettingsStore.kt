package dev.agentic.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// In-memory SettingsStore for unit tests.
class FakeSettingsStore(initialHost: String = "http://localhost:7420") : SettingsStore {
    private val _token = MutableStateFlow<String?>(null)
    override val token: StateFlow<String?> = _token.asStateFlow()

    private var _host: String = initialHost
    override val host: String get() = _host

    override fun setToken(t: String?) { _token.value = t }
    override fun setHost(h: String) { _host = h }

    private val drafts = mutableMapOf<String, String>()
    override fun draft(id: String): String? = drafts[id]
    override fun setDraft(id: String, text: String?) {
        if (text.isNullOrEmpty()) drafts.remove(id) else drafts[id] = text
    }

    private val pins = mutableMapOf<String, String>()
    override fun pinnedCert(hostKey: String): String? = pins[hostKey]
    override fun setPinnedCert(hostKey: String, fingerprint: String?) {
        if (fingerprint.isNullOrEmpty()) pins.remove(hostKey) else pins[hostKey] = fingerprint
    }
}
