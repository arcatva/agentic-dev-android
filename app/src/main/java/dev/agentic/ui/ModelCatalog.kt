package dev.agentic.ui

import dev.agentic.data.net.ModelEntry

/**
 * Global cache for the model catalog from GET /api/models — backend is the source of truth; this
 * holds the last-fetched list so [modelLabel] and [MODEL_OPTIONS] stay simple non-suspend calls.
 * Call [invalidate] after BYOK provider changes so the next read picks up the new catalog.
 */
object ModelCatalog {
    @Volatile
    private var cached: List<ModelEntry>? = null

    /** Claude-only catalog from GET /api/models?scope=session_start — keeps BYOK provider models out of session-level pickers. */
    @Volatile
    private var sessionStartCached: List<ModelEntry>? = null

    fun init(entries: List<ModelEntry>) { cached = entries }
    fun initSessionStart(entries: List<ModelEntry>) { sessionStartCached = entries }
    fun invalidate() {
        cached = null
        sessionStartCached = null
    }

    val isLoaded: Boolean get() = cached != null

    /** Ordered weakest → strongest, with "Default" as the first notch. */
    fun modelOptions(): List<Pair<String, String>> {
        val entries = cached ?: return listOf("" to "Default")
        return listOf("" to "Default") + entries.map { it.key to it.label }
    }

    /** The model key pre-selected for new sessions (the entry with `default: true`). */
    fun defaultModelKey(): String? = cached?.firstOrNull { it.default }?.key

    /** Claude/native-only options; empty/unloaded/failed catalog degrades to just "Default". */
    fun sessionStartModelOptions(): List<Pair<String, String>> {
        val entries = sessionStartCached.orEmpty().filter { it.native }
        if (entries.isEmpty()) return listOf("" to "Default")
        return listOf("" to "Default") + entries.map { it.key to it.label }
    }

    /** The model key pre-selected for new sessions, from the Claude-only catalog. */
    fun defaultSessionStartModelKey(): String? =
        sessionStartCached.orEmpty().firstOrNull { it.native && it.default }?.key

    /** Friendly display label for a raw model key; falls back to stripping a "claude-" prefix. */
    fun modelLabel(rawModel: String): String {
        if (rawModel.isEmpty()) return "Default"
        val entries = cached
        return entries?.firstOrNull { it.key == rawModel }?.label
            ?: rawModel.removePrefix("claude-")
    }
}
