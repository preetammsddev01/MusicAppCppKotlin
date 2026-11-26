package com.example

import com.example.data.model.BuiltInEqPresets
import com.example.data.model.EqualizerPreset
import com.example.data.model.TEN_BAND_FREQUENCIES_HZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerPresetsTest {

    @Test
    fun `built-in presets include Flat, Bass Boost, and Vocal Enhance`() {
        val names = BuiltInEqPresets.all.map { it.name }
        assertTrue(names.contains("Flat"))
        assertTrue(names.contains("Bass Boost"))
        assertTrue(names.contains("Vocal Enhance"))
        assertTrue(BuiltInEqPresets.all.size >= 10)
    }

    @Test
    fun `every built-in preset has valid 10-band dB gains and DSP strengths`() {
        val ids = mutableSetOf<String>()
        BuiltInEqPresets.all.forEach { preset ->
            assertTrue("Duplicate preset id: ${preset.id}", ids.add(preset.id))
            assertTrue("Preset name should not be blank", preset.name.isNotBlank())
            assertTrue("Preset description should not be blank", preset.description.isNotBlank())
            assertEquals(
                "Preset ${preset.name} must match 10 bands",
                TEN_BAND_FREQUENCIES_HZ.size,
                preset.bandLevelsDb.size
            )
            assertTrue(
                "Band gains out of range [-15, 15] for ${preset.name}",
                preset.bandLevelsDb.all { it in -15f..15f }
            )
            assertTrue(
                "Bass boost out of range [0, 1000] for ${preset.name}",
                preset.bassBoost in 0..1000
            )
            assertTrue(
                "Virtualizer out of range [0, 1000] for ${preset.name}",
                preset.virtualizer in 0..1000
            )
            assertTrue(preset.isBuiltIn)
        }
    }

    @Test
    fun `flat preset maintains bit-exact zero gains and strengths`() {
        val flat = BuiltInEqPresets.flat
        assertTrue(flat.bandLevelsDb.all { it == 0f })
        assertEquals(0, flat.bassBoost)
        assertEquals(0, flat.virtualizer)
    }

    @Test
    fun `bass boost and vocal enhance emphasize expected spectral regions`() {
        val bass = BuiltInEqPresets.bassBoost
        assertTrue(bass.bandLevelsDb[0] > 4f)
        assertTrue(bass.bassBoost >= 500)

        val vocal = BuiltInEqPresets.vocalEnhance
        assertTrue(vocal.bandLevelsDb[5] > 3f) // 1 kHz presence peak
        assertTrue(vocal.bandLevelsDb[0] < 0f) // sub-bass trimmed for vocal clarity
    }

    @Test
    fun `findById resolves both built-in and custom presets`() {
        val custom = EqualizerPreset(
            id = "custom_warm_vinyl",
            name = "Warm Vinyl",
            bandLevelsDb = listOf(2f, 1.5f, 1f, 0.5f, 0f, 0f, -0.5f, -1f, -1.5f, -2f),
            bassBoost = 200,
            virtualizer = 100,
            isBuiltIn = false
        )

        val foundBuiltIn = BuiltInEqPresets.findById("vocal_enhance", listOf(custom))
        assertNotNull(foundBuiltIn)
        assertEquals("Vocal Enhance", foundBuiltIn?.name)

        val foundCustom = BuiltInEqPresets.findById("custom_warm_vinyl", listOf(custom))
        assertNotNull(foundCustom)
        assertEquals("Warm Vinyl", foundCustom?.name)

        assertNull(BuiltInEqPresets.findById("missing_id", listOf(custom)))
    }
}
