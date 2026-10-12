package com.cressu.screendock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cressu.screendock.ui.LocalIsTv
import com.cressu.screendock.ui.isTelevision
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Standby Signal" design language for the ScreenDock receiver.
 *
 * The app lives in the dark: it is a screen waiting for a sender. The palette
 * is a near-black blue-tinted canvas, translucent glass surfaces, and a soft
 * periwinkle→violet signature gradient used for state changes and actions.
 * One green means "ready", one amber means "working", one red means "broken".
 */
object Palette {
    /** Canvas base — near black, slightly blue. */
    val bg = Color(0xFF07090D)
    val bgRaised = Color(0xFF0D1118)

    val white = Color.White

    val textPrimary = Color(0xFFF2F5FA)
    val textSecondary = Color(0xFFA7B0C0)
    /** Lightened from 0xFF5E6779 (3.4:1) to clear WCAG AA 4.5:1 on [bg]. */
    val textTertiary = Color(0xFF8590A2)

    /** Signature accent: soft periwinkle → violet. */
    val accent = Color(0xFF7FB3FF)
    val accentDeep = Color(0xFF4F7FDB)
    val violet = Color(0xFFA78BFA)

    /** Text that sits ON the accent gradient needs to be near-black. */
    val onAccent = Color(0xFF0A1120)

    val ready = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val error = Color(0xFFFB7185)
}

val AccentGradient = Brush.linearGradient(
    0.0f to Palette.accent,
    1.0f to Palette.violet,
)

/**
 * Typography: platform Roboto, but tuned — the hero device name is *thin*
 * and tightly tracked (Apple-standby-screen feel); labels are medium-weight
 * and slightly airy; the overline is uppercase, wide-tracked tertiary.
 */
private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.W200,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.W300,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    ),
)

private val DarkScheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    primaryContainer = Color(0xFF1B2C4D),
    onPrimaryContainer = Color(0xFFBFD5FF),
    secondary = Palette.violet,
    onSecondary = Color(0xFF151021),
    background = Palette.bg,
    onBackground = Palette.textPrimary,
    surface = Palette.bg,
    onSurface = Palette.textPrimary,
    surfaceVariant = Color(0xFF141A26),
    onSurfaceVariant = Palette.textSecondary,
    outline = Color(0xFF2A3242),
    error = Palette.error,
    onError = Color(0xFF2A0A12),
)

/** Extra text scale on TV: the screen is read from a couch. */
private const val TV_FONT_BOOST = 1.2f

@Composable
fun AirplayTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tv = remember(context) { context.isTelevision() }
    val density = LocalDensity.current
    val boosted = if (tv) Density(density.density, density.fontScale * TV_FONT_BOOST) else density
    CompositionLocalProvider(
        LocalDensity provides boosted,
        LocalIsTv provides tv,
    ) {
        MaterialTheme(
            colorScheme = DarkScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
