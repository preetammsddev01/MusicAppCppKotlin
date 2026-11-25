package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioDspController
import com.example.data.local.SettingsDataStore
import com.example.data.model.BuiltInEqPresets
import com.example.data.model.EqualizerPreset
import com.example.data.model.EqualizerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class EqualizerViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val audioDspController: AudioDspController
) : ViewModel() {

    private val workingState = MutableStateFlow<EqualizerState?>(null)
    private var persistJob: Job? = null

    val uiState: StateFlow<EqualizerState> = combine(
        settingsDataStore.equalizerStateFlow,
        audioDspController.runtimeInfo,
        workingState
    ) { stored, runtime, working ->
        val base = working ?: stored
        base.copy(
            customPresets = stored.customPresets,
            isTenBandMode = runtime.isTenBandActive,
            isAudioSessionAttached = runtime.isAttached,
            isNativeCppEngine = runtime.isNativeCppEngine,
            isBitExactBypass = runtime.isBitExactBypass
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EqualizerState()
    )

    init {
        viewModelScope.launch {
            val initial = settingsDataStore.equalizerStateFlow.first()
            workingState.value = initial
            audioDspController.applyState(initial)
        }
    }

    fun toggleDspEnabled() {
        val current = uiState.value
        val updated = current.copy(isDspEnabled = !current.isDspEnabled)
        applyAndPersist(updated, immediatePersist = true)
    }

    fun selectPreset(preset: EqualizerPreset) {
        val current = uiState.value
        val updated = current.copy(
            isDspEnabled = true,
            selectedPresetId = preset.id,
            bandLevelsDb = preset.bandLevelsDb,
            bassBoostStrength = preset.bassBoost,
            virtualizerStrength = preset.virtualizer
        )
        applyAndPersist(updated, immediatePersist = true)
    }

    fun updateBandGain(bandIndex: Int, gainDb: Float) {
        val current = uiState.value
        if (bandIndex !in current.bandLevelsDb.indices) return
        val snapped = if (kotlin.math.abs(gainDb) < 0.25f) 0f else (gainDb * 10f).toInt() / 10f
        val newBands = current.bandLevelsDb.toMutableList().apply {
            this[bandIndex] = snapped.coerceIn(-15f, 15f)
        }
        val updated = current.copy(
            isDspEnabled = true,
            selectedPresetId = "custom",
            bandLevelsDb = newBands
        )
        applyAndPersist(updated, immediatePersist = false)
    }

    fun updateBassBoost(strength: Int) {
        val current = uiState.value
        val updated = current.copy(
            isDspEnabled = true,
            selectedPresetId = "custom",
            bassBoostStrength = strength.coerceIn(0, 1000)
        )
        applyAndPersist(updated, immediatePersist = false)
    }

    fun updateVirtualizer(strength: Int) {
        val current = uiState.value
        val updated = current.copy(
            isDspEnabled = true,
            selectedPresetId = "custom",
            virtualizerStrength = strength.coerceIn(0, 1000)
        )
        applyAndPersist(updated, immediatePersist = false)
    }

    fun resetToFlat() {
        selectPreset(BuiltInEqPresets.flat)
    }

    fun saveCustomPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = uiState.value
        val newPreset = EqualizerPreset(
            id = "custom_${UUID.randomUUID()}",
            name = trimmed,
            bandLevelsDb = current.bandLevelsDb,
            bassBoost = current.bassBoostStrength,
            virtualizer = current.virtualizerStrength,
            isBuiltIn = false
        )
        viewModelScope.launch {
            val updatedList = current.customPresets + newPreset
            settingsDataStore.saveCustomPresets(updatedList)
            val updatedState = current.copy(
                selectedPresetId = newPreset.id,
                customPresets = updatedList
            )
            applyAndPersist(updatedState, immediatePersist = true)
        }
    }

    fun deleteCustomPreset(presetId: String) {
        val current = uiState.value
        viewModelScope.launch {
            val updatedList = current.customPresets.filterNot { it.id == presetId }
            settingsDataStore.saveCustomPresets(updatedList)
            if (current.selectedPresetId == presetId) {
                val updatedState = current.copy(
                    selectedPresetId = "custom",
                    customPresets = updatedList
                )
                applyAndPersist(updatedState, immediatePersist = true)
            }
        }
    }

    private fun applyAndPersist(updated: EqualizerState, immediatePersist: Boolean) {
        workingState.value = updated
        audioDspController.applyState(updated)
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (!immediatePersist) delay(250L)
            settingsDataStore.saveEqualizerState(
                isDspEnabled = updated.isDspEnabled,
                selectedPresetId = updated.selectedPresetId,
                bandLevelsDb = updated.bandLevelsDb,
                bassBoostStrength = updated.bassBoostStrength,
                virtualizerStrength = updated.virtualizerStrength
            )
        }
    }
}
