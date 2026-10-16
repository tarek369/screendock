package com.cressu.screendock.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cressu.screendock.R
import com.cressu.screendock.renderer.ScaleMode
import com.cressu.screendock.settings.AirPlaySettings
import com.cressu.screendock.settings.PinMode
import com.cressu.screendock.ui.components.FieldLabel
import com.cressu.screendock.ui.components.Segment
import com.cressu.screendock.ui.components.SegmentedControl
import com.cressu.screendock.ui.components.SettingsCard
import com.cressu.screendock.ui.components.Spacer16
import com.cressu.screendock.ui.components.Spacer8
import com.cressu.screendock.ui.components.SwitchRow
import com.cressu.screendock.ui.components.appTextFieldColors
import com.cressu.screendock.ui.components.dpadExitsTextField
import com.cressu.screendock.ui.theme.Palette

private const val MAX_NAME_LENGTH = 32

/** Receiver card: name, run toggle, boot / auto-open behaviour. */
@Composable
fun ReceiverCard(
    settings: AirPlaySettings,
    receiverEnabled: Boolean,
    onChange: (AirPlaySettings) -> Unit,
    onToggleReceiver: (Boolean) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.set_card_receiver), icon = Icons.Rounded.Edit) {
        FieldLabel(stringResource(R.string.set_device_name))
        Spacer8()
        OutlinedTextField(
            value = settings.displayName,
            onValueChange = { if (it.length <= MAX_NAME_LENGTH) onChange(settings.copy(displayName = it)) },
            modifier = Modifier.fillMaxWidth().dpadExitsTextField(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = appTextFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.set_device_name_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textTertiary,
        )
        Spacer16()
        SwitchRow(
            title = stringResource(R.string.set_receiver_running),
            description = stringResource(R.string.set_receiver_running_desc),
            checked = receiverEnabled,
            onCheckedChange = onToggleReceiver,
        )
        SwitchRow(
            title = stringResource(R.string.set_auto_start_boot),
            description = stringResource(R.string.set_auto_start_boot_desc),
            checked = settings.autoStartOnBoot,
            onCheckedChange = { onChange(settings.copy(autoStartOnBoot = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.set_auto_open),
            description = stringResource(R.string.set_auto_open_desc),
            checked = settings.autoOpenOnConnect,
            onCheckedChange = { onChange(settings.copy(autoOpenOnConnect = it)) },
        )
    }
}

/** Display card: Fit / Fill / Stretch (applied live, no restart). */
@Composable
fun DisplayCard(scaleMode: ScaleMode, onSelect: (ScaleMode) -> Unit) {
    SettingsCard(title = stringResource(R.string.set_card_display), icon = Icons.Rounded.AspectRatio) {
        FieldLabel(stringResource(R.string.set_scale_label))
        Spacer8()
        SegmentedControl(
            selected = scaleMode,
            segments = listOf(
                Segment(ScaleMode.FIT, stringResource(R.string.scale_fit)),
                Segment(ScaleMode.FILL, stringResource(R.string.scale_fill)),
                Segment(ScaleMode.STRETCH, stringResource(R.string.scale_stretch)),
            ),
            onSelect = onSelect,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.set_scale_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textTertiary,
        )
    }
}

/** Media card: AirPlay video (HLS) and audio screen behaviour. */
@Composable
fun MediaCard(settings: AirPlaySettings, onChange: (AirPlaySettings) -> Unit) {
    SettingsCard(title = stringResource(R.string.set_card_media), icon = Icons.Rounded.GraphicEq) {
        SwitchRow(
            title = stringResource(R.string.set_hls),
            description = stringResource(R.string.set_hls_desc),
            checked = settings.hlsEnabled,
            onCheckedChange = { onChange(settings.copy(hlsEnabled = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.set_keep_on_audio),
            description = stringResource(R.string.set_keep_on_audio_desc),
            checked = settings.keepScreenOnAudio,
            onCheckedChange = { onChange(settings.copy(keepScreenOnAudio = it)) },
        )
    }
}

/** Security card: PIN mode + fixed PIN + ask-before-allow. */
@Composable
fun SecurityCard(settings: AirPlaySettings, onChange: (AirPlaySettings) -> Unit) {
    SettingsCard(title = stringResource(R.string.set_card_security), icon = Icons.Rounded.Security) {
        FieldLabel(stringResource(R.string.set_pin_mode))
        Spacer8()
        SegmentedControl(
            selected = settings.pinMode,
            segments = listOf(
                Segment(PinMode.OFF, stringResource(R.string.pin_off)),
                Segment(PinMode.RANDOM, stringResource(R.string.pin_random)),
                Segment(PinMode.FIXED, stringResource(R.string.pin_fixed)),
            ),
            onSelect = { onChange(settings.copy(pinMode = it)) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (settings.pinMode) {
                    PinMode.OFF -> R.string.pin_off_desc
                    PinMode.RANDOM -> R.string.pin_random_desc
                    PinMode.FIXED -> R.string.pin_fixed_desc
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textTertiary,
        )
        if (settings.pinMode == PinMode.FIXED) {
            Spacer16()
            FieldLabel(stringResource(R.string.pin_field_label))
            Spacer8()
            val invalid = !PinValidator.isValidFixedPin(settings.fixedPin)
            OutlinedTextField(
                value = settings.fixedPin,
                onValueChange = { onChange(settings.copy(fixedPin = PinValidator.sanitize(it))) },
                modifier = Modifier.fillMaxWidth().dpadExitsTextField(),
                singleLine = true,
                isError = invalid,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = appTextFieldColors(),
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.pin_field_error), color = Palette.error) }
                } else null,
            )
        }
        Spacer16()
        SwitchRow(
            title = stringResource(R.string.set_ask_before),
            description = stringResource(R.string.set_ask_before_desc),
            checked = settings.askBeforeAllow,
            onCheckedChange = { onChange(settings.copy(askBeforeAllow = it)) },
        )
    }
}
