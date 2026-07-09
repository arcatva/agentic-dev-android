package dev.agentic.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Fixed M3 (Expressive) brand scheme — a single, unified BLUE family built the way the M3 color
// system prescribes (each key color → a tonal palette; roles map to specific tones; same role serves
// light + dark at different tones). Primary = vibrant azure blue, secondary = slate blue, tertiary =
// a brighter sky-blue accent (NOT teal/green — kept in the blue family so the app reads as one hue),
// error = semantic red, neutrals = blue-tinted greys. No device dynamic color, no off-brand hues.
private val LightExpressive = lightColorScheme(
    primary = Color(0xFF1B61C8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF545F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3F8), onSecondaryContainer = Color(0xFF111C2B),
    // tertiary: cornflower/royal blue accent (was teal) — same blue family as primary, slightly
    // more saturated for accent presence.
    tertiary = Color(0xFF3B5CA8), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDAE2FF), onTertiaryContainer = Color(0xFF001A43),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8FAFF), onSurface = Color(0xFF191C20),
    surfaceTint = Color(0xFF1B61C8),
    surfaceVariant = Color(0xFFDBE2EF), onSurfaceVariant = Color(0xFF424751),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF3FB),
    surfaceContainer = Color(0xFFE9EEF7), surfaceContainerHigh = Color(0xFFE3E9F3),
    surfaceContainerHighest = Color(0xFFDEE4EF),
    outline = Color(0xFF72777F), outlineVariant = Color(0xFFC2C7D2),
)
private val DarkExpressive = darkColorScheme(
    primary = Color(0xFFA7C8FF), onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF234776), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBCC7DC), onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3D4758), onSecondaryContainer = Color(0xFFD8E3F8),
    // tertiary: cornflower blue accent (was teal #59D6CE) — a touch more saturated than the
    // periwinkle primary for accent presence, but unmistakably blue (no cyan/green), so the whole
    // palette is one cohesive blue family.
    tertiary = Color(0xFF9DBBFF), onTertiary = Color(0xFF002C71),
    tertiaryContainer = Color(0xFF26447F), onTertiaryContainer = Color(0xFFD9E2FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101620), onBackground = Color(0xFFE2E6EE),
    surface = Color(0xFF101620), onSurface = Color(0xFFE2E6EE),
    surfaceTint = Color(0xFFA7C8FF),
    surfaceVariant = Color(0xFF41485A), onSurfaceVariant = Color(0xFFC1C7D3),
    surfaceContainerLowest = Color(0xFF0A0F18), surfaceContainerLow = Color(0xFF18202B),
    surfaceContainer = Color(0xFF1C242F), surfaceContainerHigh = Color(0xFF27303C),
    surfaceContainerHighest = Color(0xFF323B48),
    outline = Color(0xFF8B92A1), outlineVariant = Color(0xFF41485A),
)

// Two accent families on the otherwise all-blue base (dark-theme values; the app is forced dark),
// each a bright accent + an M3 container/on-container pair. After the swap, workflow / agent /
// ultracode use the VIOLET family (inline cards, the workflow rail + flywheel spinner, the ultracode
// pill), while skill uses the CYAN family.
val AccentCyan = Color(0xFF59D6CE)
val AccentCyanContainer = Color(0xFF00504C)
val OnAccentCyanContainer = Color(0xFF76F3EA)
val AccentViolet = Color(0xFFB197FC)
val OnAccentViolet = Color(0xFF241640)
val AccentVioletContainer = Color(0xFF382C5E)
val OnAccentVioletContainer = Color(0xFFD9C7FF)
// Blue family — hue-rotated mirror of the violet family (same lightness/contrast steps), used for
// non-router provider cards so they read as tinted peers of the violet router card.
val AccentBlue = Color(0xFF97BCFC)
val OnAccentBlue = Color(0xFF162440)
val AccentBlueContainer = Color(0xFF2C3A5E)
val OnAccentBlueContainer = Color(0xFFC7D9FF)
// Skill = green family; Plugin/MCP = purple family. Both designed for the dark theme
// background (0xFF101620). Contrast ratio vs background > 4.5:1 for both hues.
val SkillGreen            = Color(0xFF6EE7A0)   // bright mint-green, readable on dark bg
val OnSkillGreen          = Color(0xFF003920)   // dark text drawn on SkillGreen containers
val SkillGreenContainer   = Color(0xFF00522C)   // container (chip background when ON)
val PluginPurple          = Color(0xFFCFB8FF)   // soft lilac-purple, readable on dark bg
val OnPluginPurple        = Color(0xFF24005A)   // dark text on PluginPurple containers
val PluginPurpleContainer = Color(0xFF3B1878)   // container (chip background when ON)

// md3e leans on larger, varied corner radii.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * App motion scheme — **critically damped (dampingRatio 1.0) so springs settle with ZERO
 * overshoot/rebound**, per the user's hard requirement. This is the single source every
 * `appSpatialSpec()`/`appEffectsSpec()` and every M3 component reads, so it drives card expand/
 * collapse, the error/stuck banners, button & ToggleButton press feedback, the ProtocolSelector,
 * `animateContentHeight`, etc. The wide workflow rail stays fade-only (no width animation) and the
 * transcript pins via instant `scrollToItem`, so restoring these springs cannot bring back the
 * reflow/scroll rebound. Stiffness sets speed (higher = snappier); these are tunable starting points.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val AppMotionScheme: MotionScheme = object : MotionScheme {
    // SPATIAL (size/position) — critical damping; stiffness tuned a bit higher than stock expressive
    // because a critically-damped spring has a longer tail than an underdamped one.
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 600f)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 1400f)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 280f)
    // EFFECTS (alpha/color) — alpha never visibly overshoots; damping pinned to 1.0 to be explicit.
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 1600f)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 3800f)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 800f)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = DarkExpressive   // forced dark per request (LightExpressive kept for an easy toggle back)
    // MaterialExpressiveTheme wires the expressive motion scheme + component defaults (shape
    // morphing on press, spatial springs) that plain MaterialTheme doesn't.
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = AppMotionScheme,
        shapes = ExpressiveShapes,
    ) {
        // Paint the whole window with the theme background. The activity uses a platform window
        // theme whose default background is NOT our dark surface, so without this root Surface the
        // window background leaks through wherever a screen doesn't fully paint (nav transitions,
        // insets) — that was the "background turned white" regression after the MVVM rewrite dropped
        // the old AppRoot Surface wrap. Keep this here so future root/nav refactors can't lose it.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
