package dev.agentic.ui

/**
 * Single source of truth for how models and effort levels are NAMED in the UI — keeps slider labels
 * and the session annotation pills in lock-step. [MODEL_OPTIONS] is populated dynamically from
 * GET /api/models; falls back to a single "Default" notch until the catalog loads.
 */

// Ordered weakest → strongest; populated from /api/models; backend mapping in server-rs/src/engine/providers.rs.
val MODEL_OPTIONS: List<Pair<String, String>>
    get() = ModelCatalog.modelOptions()

// Claude/native-only for NewRequest + SessionSettings pickers; BYOK models are for delegate fan-out only.
val SESSION_START_MODEL_OPTIONS: List<Pair<String, String>>
    get() = ModelCatalog.sessionStartModelOptions()

val EFFORT_OPTIONS: List<Pair<String, String>> = listOf(
    "" to "Default",
    "low" to "Low",
    "medium" to "Medium",
    "high" to "High",
    "xhigh" to "Xhigh",
    "max" to "Max",
    // "ultracode" key stays lowercase (server mode value); display label capitalized to match other tiers.
    "ultracode" to "Ultracode",
)

/** Friendly display label for a raw model id. Unknown ids fall back to stripping the "claude-" prefix. */
fun modelLabel(rawModel: String): String = ModelCatalog.modelLabel(rawModel)

/** Friendly display label for a raw effort value. Unknown values fall back to the raw string unchanged. */
fun effortLabel(rawEffort: String): String =
    EFFORT_OPTIONS.firstOrNull { it.first == rawEffort }?.second ?: rawEffort
