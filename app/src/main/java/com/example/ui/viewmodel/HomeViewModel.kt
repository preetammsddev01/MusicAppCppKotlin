package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.TrackEntity
import com.example.data.remote.CloudMusicRepository
import com.example.data.remote.SyncResult
import com.example.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class HomeUiState(
    val isServerConfigured: Boolean = false,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val greeting: String = "Good evening",
    val featuredTrack: TrackEntity? = null,
    val quickPicks: List<TrackEntity> = emptyList(),
    val recentlyPlayed: List<TrackEntity> = emptyList(),
    val hiResReleases: List<TrackEntity> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val genres: List<String> = emptyList(),
    val totalTrackCount: Int = 0
)

class HomeViewModel(
    private val repository: CloudMusicRepository,
    private val playbackController: PlaybackController
) : ViewModel() {

    private val isSyncing = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeAllTracks(),
        repository.observeRecentlyPlayed(limit = 12),
        isSyncing,
        syncError
    ) { allTracks, recent, syncing, error ->
        val hiRes = allTracks.filter { it.isHiRes || it.format.equals("FLAC", ignoreCase = true) }
        val featured = hiRes.firstOrNull { !it.albumArtUrl.isNullOrBlank() }
            ?: hiRes.firstOrNull()
            ?: allTracks.firstOrNull()

        val quickPicks = allTracks
            .filter { it.id != featured?.id }
            .take(6)
            .ifEmpty { allTracks.take(6) }

        val genres = allTracks
            .map { it.genre.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        HomeUiState(
            isServerConfigured = repository.isServerConfigured,
            isSyncing = syncing,
            syncError = error,
            greeting = computeGreeting(),
            featuredTrack = featured,
            quickPicks = quickPicks,
            recentlyPlayed = recent,
            hiResReleases = hiRes.take(15),
            albums = allTracks.groupIntoAlbums().take(15),
            genres = genres,
            totalTrackCount = allTracks.size
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HomeUiState(isServerConfigured = repository.isServerConfigured)
        )

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        if (isSyncing.value) return
        viewModelScope.launch {
            isSyncing.value = true
            syncError.value = null
            when (val result = repository.syncCatalog()) {
                is SyncResult.Success -> Unit
                is SyncResult.NotConfigured -> Unit
                is SyncResult.Failure -> syncError.value = result.message
            }
            isSyncing.value = false
        }
    }

    fun dismissError() {
        syncError.value = null
    }

    fun playTrack(tracks: List<TrackEntity>, track: TrackEntity) {
        val idx = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(tracks, idx)
        viewModelScope.launch { repository.recordTrackPlayed(track.id) }
    }

    fun playAlbum(album: AlbumGroup) {
        if (album.tracks.isEmpty()) return
        playbackController.playTracks(album.tracks, 0)
        viewModelScope.launch { repository.recordTrackPlayed(album.tracks.first().id) }
    }

    fun toggleLike(track: TrackEntity) {
        viewModelScope.launch { repository.toggleLike(track) }
    }

    private fun computeGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
}
