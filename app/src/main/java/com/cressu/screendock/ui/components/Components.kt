package com.cressu.screendock.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.cressu.screendock.ui.theme.AccentGradient
import com.cressu.screendock.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ambient animated background: the near-black canvas with two large, very
 * soft radial glows (periwinkle and violet) that drift slowly and
 * continuously, plus a vignette that pulls the eye to the center. Runs two
 * animators and four gradient fills per frame — cheap even on tablets.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(36_000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Palette.bg)

        fun center(fx: Float, fy: Float, ampX: Float, ampY: Float, ph: Float): Offset {
            val t = 2.0 * PI * (phase + ph)
            return Offset(
                w * (fx + ampX * sin(t).toFloat()),
                h * (fy + ampY * cos(t).toFloat()),
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Palette.accent.copy(alpha = 0.10f), Color.Transparent),
                center = center(0.16f, 0.10f, 0.07f, 0.05f, 0.00f),
                radius = w * 0.70f,
            ),
            radius = w * 0.70f,
            center = center(0.16f, 0.10f, 0.07f, 0.05f, 0.00f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Palette.violet.copy(alpha = 0.09f), Color.Transparent),
                center = center(0.86f, 0.78f, 0.06f, 0.08f, 0.33f),
                radius = w * 0.75f,
            ),
            radius = w * 0.75f,
            center = center(0.86f, 0.78f, 0.06f, 0.08f, 0.33f),
        )
        // Vignette: darken the edges so the center composition pops.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.95f,
            ),
        )
    }
}

/**
 * The idle-screen centerpiece: a hand-drawn AirPlay-style glyph (a screen
 * with an inbound triangle) over a soft glow, with concentric rings that
 * continuously pulse outward — the receiver "broadcasting" that it is
 * waiting. [rings] off + [dim] on is the failure look.
 */
@Composable
fun BroadcastHero(
    modifier: Modifier = Modifier,
    rings: Boolean = true,
    dim: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "hero")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val glyphAlpha = if (dim) 0.45f else 1f

    Canvas(modifier = modifier.size(260.dp)) {
        val c = center
        val s = size.minDimension

        // Soft glow behind the glyph.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Palette.accent.copy(alpha = 0.16f * glyphAlpha), Color.Transparent),
                center = c,
                radius = s * 0.30f,
            ),
            radius = s * 0.30f,
            center = c,
        )

        // Broadcast rings: three circles expanding and fading, evenly
        // phase-offset so the pulse reads as a steady heartbeat.
        if (rings) {
            for (i in 0 until 3) {
                val p = ((phase + i / 3f) % 1f)
                val radius = s * (0.22f + 0.26f * p)
                val alpha = ((1f - p) * (1f - p)) * 0.38f * glyphAlpha
                drawCircle(
                    color = Palette.accent,
                    radius = radius,
                    center = c,
                    style = Stroke(width = 2.dp.toPx() * (1f - 0.5f * p)),
                    alpha = alpha,
                )
            }
        }

        // Glyph: rounded-rect screen outline + inbound triangle.
        val boxInset = s * 0.26f
        val boxSize = Size(s - 2 * boxInset, s - 2 * boxInset)
        val corner = CornerRadius(s * 0.06f, s * 0.06f)
        drawRoundRect(
            brush = AccentGradient,
            topLeft = Offset(boxInset, boxInset),
            size = boxSize,
            cornerRadius = corner,
            style = Stroke(width = 2.5.dp.toPx()),
            alpha = glyphAlpha,
        )
        // Faint fill inside the screen for depth.
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(Palette.accent.copy(alpha = 0.08f * glyphAlpha), Color.Transparent),
                center = c,
                radius = boxSize.minDimension,
            ),
            topLeft = Offset(boxInset, boxInset),
            size = boxSize,
            cornerRadius = corner,
        )
        // Triangle pointing into the screen (the AirPlay mark).
        val path = Path().apply {
            moveTo(c.x, c.y - s * 0.105f)
            lineTo(c.x + s * 0.095f, c.y + s * 0.075f)
            lineTo(c.x - s * 0.095f, c.y + s * 0.075f)
            close()
        }
        drawPath(path, brush = AccentGradient, alpha = glyphAlpha)
    }
}

/** Frosted glass card: translucent surface, hairline border, soft corners. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.white.copy(alpha = 0.05f))
            .border(1.dp, Palette.white.copy(alpha = 0.09f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        content = content,
    )
}

/** Small rounded-square icon badge used to lead card sections. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = Palette.accent) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** A glass settings card with an icon-led header row. */
@Composable
fun SettingsCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer12()
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.textPrimary,
            )
        }
        Spacer16()
        content()
    }
}

/** Status dot with a soft breathing glow. [pulse] false = steady. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "dot")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    val glow = if (pulse) a else 0.7f
    Box(
        modifier = modifier
            .size(10.dp)
            .drawBehind {
                drawCircle(color = color.copy(alpha = 0.30f * glow), radius = size.minDimension * 1.5f)
            }
            .graphicsLayer { alpha = if (pulse) (0.65f + 0.35f * a) else 1f }
            .clip(CircleShape)
            .background(color),
    )
}

/** Translucent pill with a leading status dot, for glanceable state. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = true) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Palette.white.copy(alpha = 0.10f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color, pulse = pulse)
        Spacer8()
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.textPrimary)
    }
}

/** Circular translucent icon button (settings gear, back arrow…). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) Palette.white.copy(alpha = 0.14f) else Palette.white.copy(alpha = 0.07f),
        animationSpec = tween(120),
        label = "bg",
    )
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Palette.white.copy(alpha = 0.12f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Palette.textSecondary, modifier = Modifier.size(21.dp))
    }
}

/**
 * Full-width gradient action button. [muted] renders as a quiet glass button
 * (used for "nothing to apply"); otherwise the signature gradient.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    muted: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (pressed) 0.98f else 1f
    val contentColor = if (muted) Palette.textSecondary else Palette.onAccent
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (muted) Palette.white.copy(alpha = 0.08f) else Color.Transparent)
            .let { if (muted) it else it.background(AccentGradient, shape) }
            .border(1.dp, if (muted) Palette.white.copy(alpha = 0.10f) else Palette.white.copy(alpha = 0.22f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer8()
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class Segment<T>(val value: T, val label: String)

/**
 * Custom segmented control with an animated sliding gradient pill — the
 * modern replacement for radio rows. Equal-width segments, no ripple (the
 * pill slide IS the feedback).
 */
@Composable
fun <T> SegmentedControl(
    selected: T,
    segments: List<Segment<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Palette.white.copy(alpha = 0.05f))
            .border(1.dp, Palette.white.copy(alpha = 0.08f), RoundedCornerShape(15.dp)),
    ) {
        val pad = 4.dp
        val itemW = (maxWidth - pad * 2) / segments.size
        val index = segments.indexOfFirst { it.value == selected }.coerceAtLeast(0)
        val pillX by animateDpAsState(
            targetValue = itemW * index,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "pillX",
        )
        Box(modifier = Modifier.padding(pad)) {
            Box(
                modifier = Modifier
                    .offset(x = pillX)
                    .width(itemW)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentGradient),
            )
            Row(modifier = Modifier.fillMaxWidth().height(48.dp).selectableGroup()) {
            segments.forEach { seg ->
                val isSelected = seg.value == selected
                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) Palette.onAccent else Palette.textSecondary,
                    animationSpec = tween(200),
                    label = "segColor",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(seg.value) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        seg.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor,
                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.W600
                        else androidx.compose.ui.text.font.FontWeight.W400,
                        maxLines = 1,
                    )
                }
            }
            }
        }
    }
}

/** Small translucent capability chip ("4K", "60 fps", "H.265"). */
@Composable
fun CapabilityChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.textSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.white.copy(alpha = 0.06f))
            .border(1.dp, Palette.white.copy(alpha = 0.09f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun Spacer8(modifier: Modifier = Modifier) = androidx.compose.foundation.layout.Spacer(modifier.width(8.dp))

@Composable
fun Spacer12(modifier: Modifier = Modifier) = androidx.compose.foundation.layout.Spacer(modifier.width(12.dp))

@Composable
fun Spacer16(modifier: Modifier = Modifier) = androidx.compose.foundation.layout.Spacer(modifier.height(16.dp))
