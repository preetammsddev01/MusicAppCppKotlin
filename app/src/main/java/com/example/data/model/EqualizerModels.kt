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
    val isBuiltIn: Boolean = true,
    val description: String = "",
    val category: String = "Signature"
)

object BuiltInEqPresets {
    val flat = EqualizerPreset(
        id = "flat",
        name = "Flat",
        bandLevelsDb = List(10) { 0f },
        bassBoost = 0,
        virtualizer = 0,
        description = "Bit-exact 0 dB neutral C++ signal path",
        category = "Reference"
    )

    val bassBoost = EqualizerPreset(
        id = "bass_boost",
        name = "Bass Boost",
        bandLevelsDb = listOf(6.0f, 5.5f, 4.0f, 1.5f, 0f, -0.5f, 0f, 1.0f, 1.5f, 1.0f),
        bassBoost = 680,
        virtualizer = 160,
        description = "Deep sub-bass shelf and warm 31–125 Hz punch",
        category = "Low End"
    )

    val vocalEnhance = EqualizerPreset(
        id = "vocal_enhance",
        name = "Vocal Enhance",
        bandLevelsDb = listOf(-2.0f, -1.5f, -0.5f, 1.0f, 3.0f, 4.0f, 3.5f, 2.5f, 1.5f, 0.5f),
        bassBoost = 0,
        virtualizer = 220,
        description = "Forward 500 Hz–4 kHz presence and vocal intimacy",
        category = "Voice"
    )

    val studioReference = EqualizerPreset(
        id = "studio_ref",
        name = "Studio Ref",
        bandLevelsDb = listOf(0.5f, 1.0f, 0.5f, 0f, -0.5f, 0f, 1.0f, 1.5f, 2.0f, 1.5f),
        bassBoost = 120,
        virtualizer = 180,
        description = "Balanced studio monitor curve with gentle air",
        category = "Reference"
    )

    val hiResSpatial = EqualizerPreset(
        id = "hires_spatial",
        name = "Hi-Res Spatial",
        bandLevelsDb = listOf(1.5f, 1.0f, 0f, -0.5f, 0f, 0.5f, 2.0f, 3.0f, 3.5f, 4.0f),
        bassBoost = 160,
        virtualizer = 620,
        description = "Wide M/S soundstage and shimmering top harmonics",
        category = "Spatial"
    )

    val acoustic = EqualizerPreset(
        id = "acoustic",
        name = "Acoustic",
        bandLevelsDb = listOf(1.5f, 2.0f, 1.5f, 0.5f, 0f, 0.5f, 1.5f, 2.5f, 3.0f, 2.0f),
        bassBoost = 150,
        virtualizer = 260,
        description = "Organic timbre for guitars, piano, and unplugged sets",
        category = "Genre"
    )

    val rockLive = EqualizerPreset(
        id = "rock_live",
        name = "Rock / Live",
        bandLevelsDb = listOf(3.5f, 2.5f, 1.0f, -0.5f, 1.0f, 2.0f, 2.5f, 3.0f, 3.5f, 2.5f),
        bassBoost = 320,
        virtualizer = 340,
        description = "Snappy drum attack, crunchy mids, and stage depth",
        category = "Genre"
    )

    val electronic = EqualizerPreset(
        id = "electronic",
        name = "Electronic",
        bandLevelsDb = listOf(4.5f, 4.0f, 2.0f, 0f, -1.5f, 0.5f, 1.5f, 3.0f, 4.5f, 4.0f),
        bassBoost = 450,
        virtualizer = 380,
        description = "Tight club low-end and crisp high-frequency sparkle",
        category = "Genre"
    )

    val jazzLounge = EqualizerPreset(
        id = "jazz_lounge",
        name = "Jazz / Lounge",
        bandLevelsDb = listOf(2.5f, 2.0f, 1.0f, 1.5f, 0.5f, -0.5f, 1.0f, 2.0f, 2.5f, 2.0f),
        bassBoost = 220,
        virtualizer = 300,
        description = "Warm upright bass bloom and smooth brass clarity",
        category = "Genre"
    )

    val classical = EqualizerPreset(
        id = "classical",
        name = "Classical",
        bandLevelsDb = listOf(1.0f, 1.5f, 1.0f, 0f, 0f, 0f, 1.0f, 2.0f, 2.5f, 3.0f),
        bassBoost = 80,
        virtualizer = 350,
        description = "Concert hall space and delicate orchestral strings",
        category = "Genre"
    )

    val lateNight = EqualizerPreset(
        id = "late_night",
        name = "Late Night",
        bandLevelsDb = listOf(3.0f, 2.5f, 1.0f, 0f, 1.0f, 1.5f, 1.0f, 0.5f, 1.5f, 2.0f),
        bassBoost = 250,
        virtualizer = 120,
        description = "Low-volume loudness contour for quiet listening",
        category = "Utility"
    )

    val all: List<EqualizerPreset> = listOf(
        flat,
        bassBoost,
        vocalEnhance,
        studioReference,
        hiResSpatial,
        acoustic,
        rockLive,
        electronic,
        jazzLounge,
        classical,
        lateNight
    )

    fun findById(id: String, customPresets: List<EqualizerPreset> = emptyList()): EqualizerPreset? {
        return all.firstOrNull { it.id == id }
            ?: customPresets.firstOrNull { it.id == id }
    }
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
