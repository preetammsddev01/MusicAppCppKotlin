package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.BuiltInEqPresets
import com.example.data.model.EqualizerPreset
import com.example.data.model.EqualizerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aether_settings")

class SettingsDataStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val DSP_ENABLED = booleanPreferencesKey("dsp_enabled")
        val SELECTED_PRESET_ID = stringPreferencesKey("selected_preset_id")
        val BAND_LEVELS_JSON = stringPreferencesKey("band_levels_json")
        val BASS_BOOST = intPreferencesKey("bass_boost_strength")
        val VIRTUALIZER = intPreferencesKey("virtualizer_strength")
        val CUSTOM_PRESETS_JSON = stringPreferencesKey("custom_presets_json")
        val WIFI_ONLY_HI_RES_WARN = booleanPreferencesKey("wifi_only_hires_warn")
    }

    val equalizerStateFlow: Flow<EqualizerState> = context.dataStore.data.map { prefs ->
        val bandLevels = prefs[Keys.BAND_LEVELS_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<Float>>(raw) }.getOrNull()
        }?.takeIf { it.size == 10 } ?: List(10) { 0f }

        val customPresets = prefs[Keys.CUSTOM_PRESETS_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<EqualizerPreset>>(raw) }.getOrNull()
        } ?: emptyList()

        EqualizerState(
            isDspEnabled = prefs[Keys.DSP_ENABLED] ?: true,
            selectedPresetId = prefs[Keys.SELECTED_PRESET_ID] ?: BuiltInEqPresets.flat.id,
            bandLevelsDb = bandLevels,
            bassBoostStrength = prefs[Keys.BASS_BOOST] ?: 0,
            virtualizerStrength = prefs[Keys.VIRTUALIZER] ?: 0,
            customPresets = customPresets
        )
    }

    val wifiOnlyWarningFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WIFI_ONLY_HI_RES_WARN] ?: false
    }

    suspend fun saveEqualizerState(
        isDspEnabled: Boolean,
        selectedPresetId: String,
        bandLevelsDb: List<Float>,
        bassBoostStrength: Int,
        virtualizerStrength: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DSP_ENABLED] = isDspEnabled
            prefs[Keys.SELECTED_PRESET_ID] = selectedPresetId
            prefs[Keys.BAND_LEVELS_JSON] = json.encodeToString(bandLevelsDb)
            prefs[Keys.BASS_BOOST] = bassBoostStrength.coerceIn(0, 1000)
            prefs[Keys.VIRTUALIZER] = virtualizerStrength.coerceIn(0, 1000)
        }
    }

    suspend fun saveCustomPresets(presets: List<EqualizerPreset>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CUSTOM_PRESETS_JSON] = json.encodeToString(presets)
        }
    }

    suspend fun setWifiOnlyWarning(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY_HI_RES_WARN] = enabled
        }
    }
}
