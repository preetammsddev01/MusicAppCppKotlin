package com.example.data.model

import kotlinx.serialization.Serializable

val TEN_BAND_FREQUENCIES_HZ = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

fun formatFrequencyHz(hz: Int): String = when {
    hz >= 1000 -> {
        val k = hz / 1000f
        if (k % 1f == 0f) "${k.toInt()}k" else String.format("%.1fk", k)
    }
    else -> "$hz"
}

@Serializable
data class EqualizerPreset(
    val id: String,
    val name: String,
    val bandLevelsDb: List<Float>,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val isBuiltIn: Boolean = true
)

object BuiltInEqPresets {
    val flat = EqualizerPreset(
        id = "flat",
        name = "Flat",
        bandLevelsDb = List(10) { 0f },
        bassBoost = 0,
        virtualizer = 0
    )

    val studioReference = EqualizerPreset(
        id = "studio_ref",
        name = "Studio Ref",
        bandLevelsDb = listOf(0.5f, 1.0f, 0.5f, 0f, -0.5f, 0f, 1.0f, 1.5f, 2.0f, 1.5f),
        bassBoost = 120,
        virtualizer = 180
    )

    val vocalClarity = EqualizerPreset(
        id = "vocal_clarity",
        name = "Vocal Clarity",
        bandLevelsDb = listOf(-2.0f, -1.5f, -0.5f, 1.0f, 2.5f, 3.5f, 3.0f, 1.5f, 1.0f, 0.5f),
        bassBoost = 0,
        virtualizer = 220
    )

    val acoustic = EqualizerPreset(
        id = "acoustic",
        name = "Acoustic",
        bandLevelsDb = listOf(1.5f, 2.0f, 1.5f, 0.5f, 0f, 0.5f, 1.5f, 2.5f, 3.0f, 2.0f),
        bassBoost = 150,
        virtualizer = 260
    )

    val deepBass = EqualizerPreset(
        id = "deep_bass",
        name = "Deep Bass",
        bandLevelsDb = listOf(5.5f, 5.0f, 3.5f, 1.5f, 0f, -0.5f, 0.5f, 1.5f, 2.0f, 1.5f),
        bassBoost = 620,
        virtualizer = 200
    )

    val electronic = EqualizerPreset(
        id = "electronic",
        name = "Electronic",
        bandLevelsDb = listOf(4.5f, 4.0f, 2.0f, 0f, -1.5f, 0.5f, 1.5f, 3.0f, 4.5f, 4.0f),
        bassBoost = 450,
        virtualizer = 380
    )

    val classical = EqualizerPreset(
        id = "classical",
        name = "Classical",
        bandLevelsDb = listOf(1.0f, 1.5f, 1.0f, 0f, 0f, 0f, 1.0f, 2.0f, 2.5f, 3.0f),
        bassBoost = 80,
        virtualizer = 350
    )

    val lateNight = EqualizerPreset(
        id = "late_night",
        name = "Late Night",
        bandLevelsDb = listOf(3.0f, 2.5f, 1.0f, 0f, 1.0f, 1.5f, 1.0f, 0.5f, 1.5f, 2.0f),
        bassBoost = 250,
        virtualizer = 120
    )

    val all: List<EqualizerPreset> = listOf(
        flat,
        studioReference,
        vocalClarity,
        acoustic,
        deepBass,
        electronic,
        classical,
        lateNight
    )
}

data class EqualizerState(
    val isDspEnabled: Boolean = true,
    val selectedPresetId: String = BuiltInEqPresets.flat.id,
    val bandFrequenciesHz: List<Int> = TEN_BAND_FREQUENCIES_HZ,
    val bandLevelsDb: List<Float> = List(10) { 0f },
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val isTenBandMode: Boolean = true,
    val isAudioSessionAttached: Boolean = false,
    val isNativeCppEngine: Boolean = false,
    val isBitExactBypass: Boolean = true,
    val customPresets: List<EqualizerPreset> = emptyList()
)
