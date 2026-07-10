package dev.agentic.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fade the content out over the trailing [width] px (right edge) using an offscreen DstIn mask. */
fun Modifier.fadingEdge(width: Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = size.width - w,
                endX = size.width,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Fade the content out over the trailing [height] px (bottom edge) using an offscreen DstIn mask —
 *  the vertical counterpart of [fadingEdge], for a clipped block (e.g. a collapsed description) that
 *  should dissolve into the surface instead of ending on a hard cut. */
fun Modifier.fadingEdgeBottom(height: Dp = 32.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - h,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Fade content out over [width] px at the left and/or right edge using an offscreen DstIn mask.
 * [fadeStart] / [fadeEnd] are evaluated at draw time, so a horizontally-scrollable row can fade only
 * the edge that actually has off-screen content — left once scrolled, right while more remains.
 */
fun Modifier.fadingEdgeHorizontal(
    width: Dp = 28.dp,
    fadeStart: () -> Boolean = { true },
    fadeEnd: () -> Boolean = { true },
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (fadeStart()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = w,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (fadeEnd()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - w,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Fade content out over [height] px at the top and/or bottom edge using an offscreen DstIn mask —
 * the vertical counterpart of [fadingEdgeHorizontal]. [fadeTop] / [fadeBottom] are evaluated at
 * draw time, so a vertically-scrollable block can fade only the edge that actually has off-screen
 * content — top once scrolled down, bottom while more remains below.
 */
fun Modifier.fadingEdgeVertical(
    height: Dp = 24.dp,
    fadeTop: () -> Boolean = { true },
    fadeBottom: () -> Boolean = { true },
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        if (fadeTop()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = h,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (fadeBottom()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - h,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** Single-line text that fades out at the right edge when it overflows (instead of an ellipsis). */
@Composable
fun FadingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    fadeWidth: Dp = 28.dp,
) {
    var overflow by remember(text) { mutableStateOf(false) }
    Text(
        text,
        modifier = if (overflow) modifier.fadingEdge(fadeWidth) else modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { overflow = it.hasVisualOverflow },
    )
}
