package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.TrackEntity
import com.example.data.remote.CloudMusicRepository
import com.example.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SearchFilter(val label: String) {
    ALL("All"),
    HIRES_FLAC("Hi-Res FLAC"),
    ALBUMS("Albums"),
    ARTISTS("Artists")
}

data class SearchUiState(
    val query: String = "",
    val selectedFilter: SearchFilter = SearchFilter.ALL,
    val selectedGenre: String? = null,
    val selectedAlbumName: String? = null,
    val selectedArtistName: String? = null,
    val genres: List<String> = emptyList(),
    val matchingTracks: List<TrackEntity> = emptyList(),
    val matchingAlbums: List<AlbumGroup> = emptyList(),
    val matchingArtists: List<ArtistGroup> = emptyList(),
    val isServerConfigured: Boolean = false
)

class SearchViewModel(
    private val repository: CloudMusicRepository,
    private val playbackController: PlaybackController
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedFilter = MutableStateFlow(SearchFilter.ALL)
    private val selectedGenre = MutableStateFlow<String?>(null)
    private val selectedAlbumName = MutableStateFlow<String?>(null)
    private val selectedArtistName = MutableStateFlow<String?>(null)

    private val filterTuple = combine(
        query,
        selectedFilter,
        selectedGenre,
        selectedAlbumName,
        selectedArtistName
    ) { q, filter, genre, album, artist ->
        SearchControls(q, filter, genre, album, artist)
    }

    val uiState: StateFlow<SearchUiState> = combine(
        repository.observeAllTracks(),
        filterTuple
    ) { allTracks, controls ->
        val q = controls.query.trim().lowercase()
        val genres = allTracks
            .map { it.genre.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val filteredTracks = allTracks.filter { track ->
            val matchesQuery = q.isEmpty() ||
                track.title.lowercase().contains(q) ||
                track.artist.lowercase().contains(q) ||
                track.album.lowercase().contains(q) ||
                track.format.lowercase().contains(q) ||
                track.genre.lowercase().contains(q)

            val matchesGenre = controls.genre == null ||
                track.genre.equals(controls.genre, ignoreCase = true)

            val matchesFilter = when (controls.filter) {
                SearchFilter.ALL -> true
                SearchFilter.HIRES_FLAC -> track.isHiRes || track.format.equals("FLAC", ignoreCase = true)
                SearchFilter.ALBUMS -> controls.album == null || track.album == controls.album
                SearchFilter.ARTISTS -> controls.artist == null || track.artist == controls.artist
            }

            matchesQuery && matchesGenre && matchesFilter
        }

        val matchingAlbums = allTracks.groupIntoAlbums().filter { album ->
            q.isEmpty() || album.name.lowercase().contains(q) || album.artist.lowercase().contains(q)
        }

        val matchingArtists = allTracks.groupIntoArtists().filter { artist ->
            q.isEmpty() || artist.name.lowercase().contains(q)
        }

        SearchUiState(
            query = controls.query,
            selectedFilter = controls.filter,
            selectedGenre = controls.genre,
            selectedAlbumName = controls.album,
            selectedArtistName = controls.artist,
            genres = genres,
            matchingTracks = filteredTracks,
            matchingAlbums = matchingAlbums,
            matchingArtists = matchingArtists,
            isServerConfigured = repository.isServerConfigured
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SearchUiState(isServerConfigured = repository.isServerConfigured)
        )

    fun updateQuery(value: String) {
        query.value = value
    }

    fun selectFilter(filter: SearchFilter) {
        selectedFilter.value = filter
        if (filter != SearchFilter.ALBUMS) selectedAlbumName.value = null
        if (filter != SearchFilter.ARTISTS) selectedArtistName.value = null
    }

    fun selectGenre(genre: String?) {
        selectedGenre.value = genre
        selectedAlbumName.value = null
        selectedArtistName.value = null
    }

    fun openAlbum(albumName: String?) {
        selectedAlbumName.value = albumName
        if (albumName != null) selectedFilter.value = SearchFilter.ALBUMS
    }

    fun openArtist(artistName: String?) {
        selectedArtistName.value = artistName
        if (artistName != null) selectedFilter.value = SearchFilter.ARTISTS
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

    fun toggleLike(track: TrackEntity) {
        viewModelScope.launch { repository.toggleLike(track) }
    }

    private data class SearchControls(
        val query: String,
        val filter: SearchFilter,
        val genre: String?,
        val album: String?,
        val artist: String?
    )
}
