package com.cressu.screendock.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cressu.screendock.ui.theme.Palette

/**
 * Visible D-pad / keyboard focus state for custom clickables (which use
 * indication = null): focused element scales up slightly, gets a bright accent
 * border and a soft accent glow.
 *
 * Must be placed BEFORE the `.clickable` / `.selectable` / `.toggleable` in the
 * modifier chain so [onFocusChanged] observes that focus target.
 */
fun Modifier.focusGlow(shape: Shape, focusedScale: Float = 1.05f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(140),
        label = "focusScale",
    )
    Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(
            elevation = if (focused) 14.dp else 0.dp,
            shape = shape,
            clip = false,
            ambientColor = Palette.accent,
            spotColor = Palette.accent,
        )
        .then(if (focused) Modifier.border(2.dp, Palette.accent, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused }
}
