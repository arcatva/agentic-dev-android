package dev.agentic.ui

import dev.agentic.data.net.ModelEntry

/**
 * Global cache for the model catalog fetched from GET /api/models at startup.
 *
 * The Android client no longer hardcodes which Claude models exist or what they're called — the
 * backend is the single source of truth. [ModelCatalog] holds the last-fetched list so
 * [modelLabel] and [MODEL_OPTIONS] stay available as simple non-suspend calls from any composable.
 *
 * Cache invalidation: call [invalidate] after adding or removing a BYOK provider so the next
 * [modelOptions] / [modelLabel] call against a freshly-loaded catalog picks up the change.
 */
object ModelCatalog {
    @Volatile
    private var cached: List<ModelEntry>? = null

    /** Claude-only catalog from GET /api/models?scope=session_start — the main-thread model
     *  pickers (New Request, Session Settings) read this, never the full catalog, so BYOK
     *  provider models (MiniMax/DeepSeek/…) can't be selected as a session's model. */
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

    /** Ordered weakest → strongest, with "Default" as the first notch. Claude/native entries
     *  only — empty, unloaded, or failed catalog degrades to just the "Default" notch. */
    fun sessionStartModelOptions(): List<Pair<String, String>> {
        val entries = sessionStartCached.orEmpty().filter { it.native }
        if (entries.isEmpty()) return listOf("" to "Default")
        return listOf("" to "Default") + entries.map { it.key to it.label }
    }

    /** The model key pre-selected for new sessions, from the Claude-only catalog. */
    fun defaultSessionStartModelKey(): String? =
        sessionStartCached.orEmpty().firstOrNull { it.native && it.default }?.key

    /** Friendly display label for a raw model key. Falls back to stripping a "claude-" prefix. */
    fun modelLabel(rawModel: String): String {
        if (rawModel.isEmpty()) return "Default"
        val entries = cached
        return entries?.firstOrNull { it.key == rawModel }?.label
            ?: rawModel.removePrefix("claude-")
    }
}
