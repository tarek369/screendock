package com.cressu.screendock.ui.components

import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cressu.screendock.ui.theme.Palette

/**
 * Whole-row toggle: label + description + themed switch. The entire row is the
 * focus/touch target (>= 56dp tall) and shows the TV focus glow; the Switch
 * itself is purely visual so there is exactly one focus stop per row.
 */
@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusGlow(shape, focusedScale = 1.02f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Palette.textPrimary else Palette.textTertiary,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textSecondary,
                )
            }
        }
        Spacer12(Modifier.width(4.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.accent,
                checkedThumbColor = Palette.white,
                uncheckedTrackColor = Palette.white.copy(alpha = 0.14f),
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedBorderColor = Palette.white.copy(alpha = 0.18f),
            ),
        )
    }
}

/** Small uppercase-ish caption used above controls inside cards. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.textSecondary,
        modifier = modifier,
    )
}

/** Shared themed outlined-text-field colors (device name, PIN). */
@Composable
fun appTextFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent.copy(alpha = 0.65f),
    unfocusedBorderColor = Palette.white.copy(alpha = 0.10f),
    errorBorderColor = Palette.error,
    errorTextColor = Palette.textPrimary,
    focusedContainerColor = Palette.white.copy(alpha = 0.05f),
    unfocusedContainerColor = Palette.white.copy(alpha = 0.05f),
    errorContainerColor = Palette.white.copy(alpha = 0.05f),
)

/**
 * Single-line text fields swallow D-pad up/down, trapping TV remote users inside them.
 * Move focus out instead so the rest of the screen (e.g. the Apply button) stays reachable.
 */
@Composable
fun Modifier.dpadExitsTextField(): Modifier {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            androidx.compose.ui.input.key.Key.DirectionDown ->
                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            androidx.compose.ui.input.key.Key.DirectionUp ->
                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
            else -> false
        }
    }
}

private const val DPAD_SCROLL_PX = 320f

/**
 * TV remotes can only scroll by moving focus. On pages where long stretches have nothing
 * focusable (tips, help text), D-pad up/down would get stuck, so scroll the page instead
 * whenever focus can't move further in that direction.
 */
@Composable
fun Modifier.dpadScrollable(state: androidx.compose.foundation.ScrollState): Modifier {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return onKeyEvent { event ->
        if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
        val (direction, delta) = when (event.key) {
            androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down to DPAD_SCROLL_PX
            androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up to -DPAD_SCROLL_PX
            else -> return@onKeyEvent false
        }
        if (focusManager.moveFocus(direction)) return@onKeyEvent true
        scope.launch { state.animateScrollBy(delta) }
        true
    }
}
