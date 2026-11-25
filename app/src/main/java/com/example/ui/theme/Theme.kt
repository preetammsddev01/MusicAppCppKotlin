package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

val AetherShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun AetherAudioTheme(
    dynamicAccent: Color = StudioAmber,
    dynamicSecondary: Color = LosslessCyan,
    content: @Composable () -> Unit
) {
    val animatedPrimary by animateColorAsState(
        targetValue = dynamicAccent,
        animationSpec = tween(durationMillis = 600),
        label = "primaryAccentAnim"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = dynamicSecondary,
        animationSpec = tween(durationMillis = 600),
        label = "secondaryAccentAnim"
    )

    val onPrimaryColor = if (animatedPrimary.luminance() > 0.45f) ObsidianBg else TextPrimary

    val colorScheme = darkColorScheme(
        primary = animatedPrimary,
        onPrimary = onPrimaryColor,
        primaryContainer = animatedPrimary.copy(alpha = 0.18f),
        onPrimaryContainer = animatedPrimary,
        secondary = animatedSecondary,
        onSecondary = ObsidianBg,
        secondaryContainer = animatedSecondary.copy(alpha = 0.16f),
        onSecondaryContainer = animatedSecondary,
        tertiary = VerifiedEmerald,
        onTertiary = ObsidianBg,
        background = ObsidianBg,
        onBackground = TextPrimary,
        surface = StudioConsole,
        onSurface = TextPrimary,
        surfaceVariant = TonalRack,
        onSurfaceVariant = TextSecondary,
        surfaceContainerLowest = ObsidianBg,
        surfaceContainerLow = StudioConsole,
        surfaceContainer = TonalRack,
        surfaceContainerHigh = ElevatedRack,
        outline = HairlineBorder,
        outlineVariant = HairlineBorder.copy(alpha = 0.5f),
        error = ErrorCoral,
        onError = ObsidianBg
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AetherTypography,
        shapes = AetherShapes,
        content = content
    )
}
