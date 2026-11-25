package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.PlaylistWithTracks
import com.example.data.model.TrackEntity
import com.example.data.remote.CloudMusicRepository
import com.example.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) {
    PLAYLISTS("Playlists"),
    LIKED("Liked Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists")
}

sealed interface LibraryDetailView {
    data object None : LibraryDetailView
    data object LikedSongs : LibraryDetailView
    data object RecentlyPlayed : LibraryDetailView
    data class PlaylistDetail(val playlistId: String) : LibraryDetailView
    data class AlbumDetail(val albumName: String) : LibraryDetailView
    data class ArtistDetail(val artistName: String) : LibraryDetailView
}

data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.PLAYLISTS,
    val detailView: LibraryDetailView = LibraryDetailView.None,
    val likedTracks: List<TrackEntity> = emptyList(),
    val recentlyPlayed: List<TrackEntity> = emptyList(),
    val playlists: List<PlaylistWithTracks> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val artists: List<ArtistGroup> = emptyList(),
    val allTracks: List<TrackEntity> = emptyList()
)

class LibraryViewModel(
    private val repository: CloudMusicRepository,
    private val playbackController: PlaybackController
) : ViewModel() {

    private val activeTab = MutableStateFlow(LibraryTab.PLAYLISTS)
    private val detailView = MutableStateFlow<LibraryDetailView>(LibraryDetailView.None)

    private val collectionsFlow = combine(
        repository.observeAllTracks(),
        repository.observeLikedTracks(),
        repository.observeRecentlyPlayed(limit = 30),
        repository.observePlaylistsWithTracks()
    ) { all, liked, recent, playlists ->
        CollectionsBundle(all, liked, recent, playlists)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        activeTab,
        detailView,
        collectionsFlow
    ) { tab, detail, bundle ->
        LibraryUiState(
            activeTab = tab,
            detailView = detail,
            likedTracks = bundle.liked,
            recentlyPlayed = bundle.recent,
            playlists = bundle.playlists,
            albums = bundle.all.groupIntoAlbums(),
            artists = bundle.all.groupIntoArtists(),
            allTracks = bundle.all
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun selectTab(tab: LibraryTab) {
        activeTab.value = tab
        detailView.value = LibraryDetailView.None
    }

    fun openDetail(detail: LibraryDetailView) {
        detailView.value = detail
    }

    fun closeDetail() {
        detailView.value = LibraryDetailView.None
    }

    fun createPlaylist(name: String, initialTrackId: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createPlaylist(name, initialTrackId)
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if ((detailView.value as? LibraryDetailView.PlaylistDetail)?.playlistId == playlistId) {
                detailView.value = LibraryDetailView.None
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun toggleLike(track: TrackEntity) {
        viewModelScope.launch { repository.toggleLike(track) }
    }

    fun playTrack(tracks: List<TrackEntity>, track: TrackEntity) {
        val idx = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playbackController.playTracks(tracks, idx)
        viewModelScope.launch { repository.recordTrackPlayed(track.id) }
    }

    fun playAll(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        playbackController.playTracks(tracks, 0)
        viewModelScope.launch { repository.recordTrackPlayed(tracks.first().id) }
    }

    private data class CollectionsBundle(
        val all: List<TrackEntity>,
        val liked: List<TrackEntity>,
        val recent: List<TrackEntity>,
        val playlists: List<PlaylistWithTracks>
    )
}
