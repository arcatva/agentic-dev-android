package dev.agentic.ui

/**
 * Single source of truth for how models and effort levels are NAMED in the UI.
 *
 * Shared by the New request screen's model/effort sliders and every place that later displays the
 * model/effort a session ran with (the session annotation pills under the conversation title, the
 * workflow agent chips and rail subtitle). Keeping ONE list here is what stops the slider labels and
 * the pill labels from drifting apart — previously the sliders showed "Sonnet 4.6" / "High" while the
 * pills showed the raw "sonnet-4-6" / "high".
 *
 * Each entry is `rawValue to displayLabel`: the raw value is what the server stores and the API
 * sends (e.g. "claude-sonnet-4-6", "high"); the display label is what the user sees ("Sonnet 4.6",
 * "High"). The leading `"" to "Default"` entry is the sliders' "no override" notch and is never
 * emitted as a pill (callers drop blank model/effort before labeling).
 */

// Ordered weakest → strongest so the slider reads left = least capable, right = most capable.
// Populated dynamically from GET /api/models at startup; falls back to a single "Default" notch
// until the catalog loads. ModelCatalog is the single source of truth — update the backend mapping
// in server-rs/src/engine/providers.rs when Claude releases a new model.
val MODEL_OPTIONS: List<Pair<String, String>>
    get() = ModelCatalog.modelOptions()

// Claude/native-only options for the main-thread model pickers (New Request and Session
// Settings). Populated from GET /api/models?scope=session_start; BYOK provider models are for
// delegate fan-out routing and must never be offered as a session's own model. Falls back to a
// single "Default" notch until the scoped catalog loads (or when it is empty/unavailable).
val SESSION_START_MODEL_OPTIONS: List<Pair<String, String>>
    get() = ModelCatalog.sessionStartModelOptions()

val EFFORT_OPTIONS: List<Pair<String, String>> = listOf(
    "" to "Default",
    "low" to "Low",
    "medium" to "Medium",
    "high" to "High",
    "xhigh" to "Xhigh",
    "max" to "Max",
    // Ultracode folded in as the top notch (mode=ultracode ⇒ xhigh effort + auto workflow orchestration).
    // Display label is capitalized ("Ultracode") to match the other tiers and the session pill; the KEY
    // stays lowercase "ultracode" — that's the mode value sent to the backend, not display text. (The
    // effort pill is suppressed for ultracode sessions, so effortLabel("ultracode") is never shown.)
    "ultracode" to "Ultracode",
)

/**
 * Friendly display label for a raw model id, matching the New request screen's slider. Unknown ids —
 * a non-Claude model, or a newer id this build predates — fall back to the raw id with any "claude-"
 * prefix stripped (the behavior the pills had before there was a shared catalog).
 */
fun modelLabel(rawModel: String): String = ModelCatalog.modelLabel(rawModel)

/**
 * Friendly display label for a raw effort value, matching the New request screen's slider. Unknown
 * values fall back to the raw string unchanged.
 */
fun effortLabel(rawEffort: String): String =
    EFFORT_OPTIONS.firstOrNull { it.first == rawEffort }?.second ?: rawEffort
