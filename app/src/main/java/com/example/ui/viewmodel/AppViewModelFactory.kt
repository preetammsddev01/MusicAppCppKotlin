package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.AetherAudioApp

class AppViewModelFactory(private val app: AetherAudioApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                HomeViewModel(app.cloudRepository, app.playbackController) as T
            }
            modelClass.isAssignableFrom(SearchViewModel::class.java) -> {
                SearchViewModel(app.cloudRepository, app.playbackController) as T
            }
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> {
                LibraryViewModel(app.cloudRepository, app.playbackController) as T
            }
            modelClass.isAssignableFrom(PlayerViewModel::class.java) -> {
                PlayerViewModel(
                    repository = app.cloudRepository,
                    playbackController = app.playbackController,
                    cacheManager = app.ephemeralAudioCacheManager,
                    telemetryBus = app.telemetryBus,
                    paletteExtractor = app.paletteExtractor
                ) as T
            }
            modelClass.isAssignableFrom(EqualizerViewModel::class.java) -> {
                EqualizerViewModel(app.settingsDataStore, app.audioDspController) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
