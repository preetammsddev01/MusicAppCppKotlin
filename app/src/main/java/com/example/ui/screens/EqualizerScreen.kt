package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BuiltInEqPresets
import com.example.data.model.EqualizerPreset
import com.example.data.model.EqualizerState
import com.example.data.model.formatFrequencyHz
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.LosslessCyan
import com.example.ui.theme.StudioConsole
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.theme.TonalRack
import com.example.ui.theme.VerifiedEmerald
import java.util.Locale

@Composable
fun EqualizerScreen(
    state: EqualizerState,
    onToggleDsp: () -> Unit,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onBandGainChange: (Int, Float) -> Unit,
    onBassBoostChange: (Int) -> Unit,
    onVirtualizerChange: (Int) -> Unit,
    onResetFlat: () -> Unit,
    onSaveCustomPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    bottomContentPadding: PaddingValues
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // DSP Studio Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.equalizer_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modeText = when {
                        state.isNativeCppEngine && state.isBitExactBypass -> "C++ Bit-Exact Bypass"
                        state.isNativeCppEngine -> "C++ 64-Bit DSP"
                        state.isTenBandMode -> "10-Band Dynamics"
                        else -> "5-Band Standard"
                    }
                    val sessionText = if (state.isAudioSessionAttached) "LIVE SESSION" else "STANDBY"
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.isAudioSessionAttached && (state.isDspEnabled || state.isBitExactBypass)) VerifiedEmerald
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    )
                    Text(
                        text = "$modeText · $sessionText",
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (state.isDspEnabled && !state.isBitExactBypass) "DSP ON" else "BIT-EXACT",
                    style = TelemetryTextStyle.copy(fontWeight = FontWeight.Bold),
                    color = if (state.isDspEnabled && !state.isBitExactBypass) MaterialTheme.colorScheme.primary else VerifiedEmerald
                )
                Switch(
                    checked = state.isDspEnabled,
                    onCheckedChange = { onToggleDsp() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = TonalRack
                    ),
                    modifier = Modifier.testTag("dsp_master_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Frequency Response Curve Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioConsole,
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (state.isDspEnabled) 1f else 0.45f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FREQUENCY RESPONSE CURVE",
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "±15.0 dB",
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                FrequencyResponseCurveCanvas(
                    bandLevelsDb = state.bandLevelsDb,
                    accentColor = MaterialTheme.colorScheme.primary,
                    secondaryColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Presets Carousel + Save/Reset + Active Preset Summary
        val allPresets = BuiltInEqPresets.all + state.customPresets
        val activePreset = BuiltInEqPresets.findById(state.selectedPresetId, state.customPresets)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "C++ DSP PRESETS",
                    style = TelemetryTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = activePreset?.description?.ifBlank { "Custom user-saved curve" }
                        ?: "Custom 10-band manual curve",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showSaveDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, HairlineBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("eq_save_preset_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onResetFlat,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, HairlineBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("eq_reset_flat_button")
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Flat", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(allPresets, key = { it.id }) { preset ->
                val selected = state.selectedPresetId == preset.id
                PresetCurveCard(
                    preset = preset,
                    selected = selected,
                    onClick = { onSelectPreset(preset) },
                    onDelete = if (!preset.isBuiltIn) {
                        { onDeleteCustomPreset(preset.id) }
                    } else null
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 10-Band Vertical Fader Console
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioConsole,
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (state.isDspEnabled) 1f else 0.45f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "GRAPHIC EQUALIZER",
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Double-tap fader to zero",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.bandFrequenciesHz.forEachIndexed { index, freqHz ->
                        val gain = state.bandLevelsDb.getOrElse(index) { 0f }
                        StudioVerticalFader(
                            frequencyLabel = formatFrequencyHz(freqHz),
                            gainDb = gain,
                            enabled = state.isDspEnabled,
                            onGainChange = { newGain -> onBandGainChange(index, newGain) },
                            modifier = Modifier
                                .width(34.dp)
                                .testTag("eq_band_fader_$index")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bass Boost & Virtualizer Modules
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DspEffectCard(
                title = stringResource(R.string.bass_boost_label),
                icon = Icons.Filled.Speaker,
                strength = state.bassBoostStrength,
                enabled = state.isDspEnabled,
                accentColor = MaterialTheme.colorScheme.primary,
                onStrengthChange = onBassBoostChange,
                testTag = "bass_boost_slider",
                modifier = Modifier.weight(1f)
            )
            DspEffectCard(
                title = stringResource(R.string.virtualizer_label),
                icon = Icons.Filled.SurroundSound,
                strength = state.virtualizerStrength,
                enabled = state.isDspEnabled,
                accentColor = LosslessCyan,
                onStrengthChange = onVirtualizerChange,
                testTag = "virtualizer_slider",
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = StudioConsole,
            title = { Text("Save Custom EQ Preset", style = MaterialTheme.typography.titleLarge) },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset Name") },
                    placeholder = { Text("e.g. Planar Headphones") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_preset_name_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveCustomPreset(presetNameInput)
                        presetNameInput = ""
                        showSaveDialog = false
                    },
                    modifier = Modifier.testTag("confirm_save_preset_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FrequencyResponseCurveCanvas(
    bandLevelsDb: List<Float>,
    accentColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padH = 12.dp.toPx()
        val usableH = (h - padH * 2).coerceAtLeast(1f)
        val midY = padH + usableH / 2f

        // Horizontal dB grid lines (+15, +5, 0, -5, -15)
        val gridRatios = listOf(0f, 0.33f, 0.5f, 0.67f, 1f)
        gridRatios.forEachIndexed { idx, ratio ->
            val y = padH + usableH * ratio
            val isZero = idx == 2
            drawLine(
                color = if (isZero) HairlineBorder.copy(alpha = 0.9f) else HairlineBorder.copy(alpha = 0.45f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (isZero) 1.5.dp.toPx() else 1.dp.toPx(),
                pathEffect = if (isZero) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        }

        if (bandLevelsDb.isEmpty()) return@Canvas

        val points = bandLevelsDb.mapIndexed { idx, db ->
            val x = if (bandLevelsDb.size == 1) w / 2f
            else (idx.toFloat() / (bandLevelsDb.size - 1)) * w
            val normalized = ((-db.coerceIn(-15f, 15f)) + 15f) / 30f
            val y = padH + normalized * usableH
            Offset(x, y)
        }

        val curvePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 0 until points.lastIndex) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val controlX = (p0.x + p1.x) / 2f
                cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = Path().apply {
            addPath(curvePath)
            lineTo(points.last().x, midY)
            lineTo(points.first().x, midY)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.32f),
                    secondaryColor.copy(alpha = 0.08f)
                )
            )
        )

        drawPath(
            path = curvePath,
            brush = Brush.horizontalGradient(listOf(accentColor, secondaryColor)),
            style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
        )

        points.forEach { pt ->
            drawCircle(
                color = accentColor.copy(alpha = 0.3f),
                radius = 6.dp.toPx(),
                center = pt
            )
            drawCircle(
                color = accentColor,
                radius = 3.2.dp.toPx(),
                center = pt
            )
        }
    }
}

@Composable
private fun StudioVerticalFader(
    frequencyLabel: String,
    gainDb: Float,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    var trackHeightPx by remember { mutableStateOf(1f) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val formattedGain = when {
            gainDb > 0f -> String.format(Locale.US, "+%.0f", gainDb)
            gainDb < 0f -> String.format(Locale.US, "%.0f", gainDb)
            else -> "0"
        }
        Text(
            text = formattedGain,
            style = TelemetryTextStyle,
            color = if (gainDb != 0f && enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .width(34.dp)
                .height(176.dp)
                .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { onGainChange(0f) },
                        onTap = { offset ->
                            val ratio = 1f - (offset.y / trackHeightPx).coerceIn(0f, 1f)
                            onGainChange((ratio * 30f) - 15f)
                        }
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val ratio = 1f - (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                        onGainChange((ratio * 30f) - 15f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val trackW = 4.dp.toPx()
                val pad = 10.dp.toPx()
                val usableH = (size.height - pad * 2).coerceAtLeast(1f)
                val zeroY = pad + usableH / 2f
                val normalized = ((-gainDb.coerceIn(-15f, 15f)) + 15f) / 30f
                val thumbY = pad + normalized * usableH

                // Track Groove
                drawRoundRect(
                    color = TonalRack,
                    topLeft = Offset(cx - trackW / 2f, pad),
                    size = Size(trackW, usableH),
                    cornerRadius = CornerRadius(trackW / 2f)
                )

                // Zero tick mark
                drawLine(
                    color = HairlineBorder,
                    start = Offset(cx - 8.dp.toPx(), zeroY),
                    end = Offset(cx + 8.dp.toPx(), zeroY),
                    strokeWidth = 1.dp.toPx()
                )

                // Active Gain Bar from 0 dB to Thumb
                val topY = minOf(zeroY, thumbY)
                val barH = kotlin.math.abs(thumbY - zeroY)
                if (barH > 1f) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(cx - trackW / 2f, topY),
                        size = Size(trackW, barH),
                        cornerRadius = CornerRadius(trackW / 2f)
                    )
                }

                // Precision Fader Cap
                drawRoundRect(
                    color = if (enabled) accent else HairlineBorder,
                    topLeft = Offset(cx - 10.dp.toPx(), thumbY - 6.dp.toPx()),
                    size = Size(20.dp.toPx(), 12.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = frequencyLabel,
            style = TelemetryTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DspEffectCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    strength: Int,
    enabled: Boolean,
    accentColor: Color,
    onStrengthChange: (Int) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val pct = (strength / 10).coerceIn(0, 100)
    Surface(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(16.dp),
        color = StudioConsole,
        border = BorderStroke(1.dp, HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "$pct%",
                    style = TelemetryTextStyle.copy(fontWeight = FontWeight.Bold),
                    color = if (pct > 0 && enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = strength.toFloat(),
                onValueChange = { if (enabled) onStrengthChange(it.toInt()) },
                enabled = enabled,
                valueRange = 0f..1000f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = TonalRack
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag)
            )
        }
    }
}

@Composable
private fun PresetCurveCard(
    preset: EqualizerPreset,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val accent = if (selected) MaterialTheme.colorScheme.primary else LosslessCyan
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else HairlineBorder
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
    } else {
        StudioConsole
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .width(126.dp)
            .testTag("eq_preset_${preset.id}")
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (preset.isBuiltIn) preset.category.uppercase() else "CUSTOM",
                    style = TelemetryTextStyle,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (onDelete != null) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Delete ${preset.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onDelete)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            MiniPresetCurvePreview(
                bandLevelsDb = preset.bandLevelsDb,
                lineColor = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
            )
            Spacer(modifier = Modifier.height(5.dp))
            val bassPct = (preset.bassBoost / 10).coerceIn(0, 100)
            val spacePct = (preset.virtualizer / 10).coerceIn(0, 100)
            Text(
                text = if (bassPct == 0 && spacePct == 0) "0 dB Direct" else "B $bassPct% · S $spacePct%",
                style = TelemetryTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MiniPresetCurvePreview(
    bandLevelsDb: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = 3.dp.toPx()
        val usableH = (h - pad * 2).coerceAtLeast(1f)
        val midY = pad + usableH / 2f

        drawLine(
            color = HairlineBorder.copy(alpha = 0.6f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx()
        )
        if (bandLevelsDb.isEmpty()) return@Canvas

        val pts = bandLevelsDb.mapIndexed { idx, db ->
            val x = if (bandLevelsDb.size == 1) w / 2f else (idx.toFloat() / (bandLevelsDb.size - 1)) * w
            val norm = ((-db.coerceIn(-10f, 10f)) + 10f) / 20f
            Offset(x, pad + norm * usableH)
        }
        val path = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 0 until pts.lastIndex) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val cx = (p0.x + p1.x) / 2f
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

