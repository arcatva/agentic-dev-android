package dev.agentic.ui.tree

import androidx.compose.ui.graphics.Color

/**
 * Lane palette for the multi-lane commit graph.
 *
 * Material 3 Expressive treats colour as tonal palettes in HCT space; "contrast is a difference in
 * tone". To get a categorical (one-per-branch) set that is harmonious rather than a garish rainbow,
 * every accent here is fixed at roughly the same lightness — HCT tone ~80, chroma ~30–40 — which is
 * exactly the tone M3 uses for accent roles on a dark theme. Same tone → the lanes are equiluminant
 * (no single branch shouts louder than the others) and each lands ~9.5–11:1 contrast on the app's
 * near-black `#101620` surface, well past the 3:1 minimum for thin strokes/dots. The hues are ordered
 * so adjacent lane ids are far apart on the colour wheel.
 *
 * Lane 0 (the HEAD / first-parent trunk) is NOT in this list — it is pinned to the brand periwinkle
 * `colorScheme.primary` by [laneColor], so the mainline always reads as the app's primary blue.
 */
val LaneAccents: List<Color> = listOf(
    Color(0xFF9BD49A), // green
    Color(0xFFF4ABA8), // coral
    Color(0xFF74D4DC), // cyan
    Color(0xFFE4C77E), // amber
    Color(0xFFC9B4F4), // violet
    Color(0xFFF2B488), // orange
    Color(0xFFEFAAD6), // magenta
)

/**
 * Colour for a lane. Lane 0 = the trunk, drawn in [trunk] (pass `colorScheme.primary`). Every other
 * lane cycles [LaneAccents] by its stable lane id, so a branch keeps one colour for its whole length.
 */
fun laneColor(laneId: Int, trunk: Color): Color =
    if (laneId == 0) trunk else LaneAccents[(laneId - 1).mod(LaneAccents.size)]
