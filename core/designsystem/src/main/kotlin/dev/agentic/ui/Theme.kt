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

// Fixed M3 (Expressive) brand scheme — one unified BLUE family: primary/secondary/tertiary all blue tones;
// error = semantic red; neutrals = blue-tinted greys. No device dynamic color.
private val LightExpressive = lightColorScheme(
    primary = Color(0xFF1B61C8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF545F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3F8), onSecondaryContainer = Color(0xFF111C2B),
    // Tertiary: cornflower/royal blue accent (was teal) — same blue family as primary, slightly more saturated.
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
    // Tertiary: cornflower blue accent (was teal #59D6CE) — more saturated than periwinkle primary, but blue.
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

// Accents on the all-blue base — workflow/agent/ultracode use Violet (rail, pill, flywheel); skill uses Cyan;
// non-router provider cards use the blue tint as a violet mirror (same lightness/contrast steps).
val AccentCyan = Color(0xFF59D6CE)
val AccentCyanContainer = Color(0xFF00504C)
val OnAccentCyanContainer = Color(0xFF76F3EA)
val AccentViolet = Color(0xFFB197FC)
val OnAccentViolet = Color(0xFF241640)
val AccentVioletContainer = Color(0xFF382C5E)
val OnAccentVioletContainer = Color(0xFFD9C7FF)
val AccentBlue = Color(0xFF97BCFC)
val OnAccentBlue = Color(0xFF162440)
val AccentBlueContainer = Color(0xFF2C3A5E)
val OnAccentBlueContainer = Color(0xFFC7D9FF)
// Component chips: cyan for toggleable kinds (skill/plugin/mcp), blue for repos.

// md3e leans on larger, varied corner radii.
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * App motion scheme — **critically damped (DampingRatioNoBouncy 1.0) so springs settle with ZERO
 * overshoot/rebound**, the app-wide no-bounce requirement. Single source every spatial/effects spec
 * and M3 component reads. The wide workflow rail stays fade-only and the transcript pins via
 * instant scrollToItem so restoring these springs cannot bring back the reflow/scroll rebound.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val AppMotionScheme: MotionScheme = object : MotionScheme {
    // SPATIAL (size/position) — critical damping; stiffness tuned a bit higher than stock expressive.
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
    val scheme = DarkExpressive   // forced dark; LightExpressive kept for an easy toggle back.
    // MaterialExpressiveTheme wires motion scheme + shape morphing that plain MaterialTheme doesn't.
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = AppMotionScheme,
        shapes = ExpressiveShapes,
    ) {
        // Paint the whole window with the theme background — activity's platform window theme has a
        // non-our-dark default, so without this root Surface the window background leaks through
        // wherever a screen doesn't fully paint (nav transitions, insets).
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
