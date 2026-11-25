package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TrackEntity
import com.example.ui.components.AlbumArtworkImage
import com.example.ui.components.HiResFormatBadge
import com.example.ui.components.TrackListRow
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.StudioConsole
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.viewmodel.AlbumGroup
import com.example.ui.viewmodel.ArtistGroup
import com.example.ui.viewmodel.SearchFilter
import com.example.ui.viewmodel.SearchUiState

@Composable
fun SearchScreen(
    uiState: SearchUiState,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    onQueryChange: (String) -> Unit,
    onFilterSelect: (SearchFilter) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onOpenAlbum: (String?) -> Unit,
    onOpenArtist: (String?) -> Unit,
    onPlayTrack: (List<TrackEntity>, TrackEntity) -> Unit,
    onPlayAll: (List<TrackEntity>) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    bottomContentPadding: PaddingValues
) {
    if (uiState.selectedAlbumName != null) {
        BackHandler { onOpenAlbum(null) }
    } else if (uiState.selectedArtistName != null) {
        BackHandler { onOpenArtist(null) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_tracks_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (uiState.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag("search_clear_button")
                    ) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = StudioConsole,
                unfocusedContainerColor = StudioConsole,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = HairlineBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchFilter.entries.forEach { filter ->
                val selected = uiState.selectedFilter == filter
                FilterChip(
                    selected = selected,
                    onClick = { onFilterSelect(filter) },
                    label = { Text(filter.label) },
                    leadingIcon = {
                        val icon = when (filter) {
                            SearchFilter.ALL -> Icons.Filled.GraphicEq
                            SearchFilter.HIRES_FLAC -> Icons.Filled.HighQuality
                            SearchFilter.ALBUMS -> Icons.Filled.Album
                            SearchFilter.ARTISTS -> Icons.Filled.Person
                        }
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StudioConsole,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = HairlineBorder,
                        selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.testTag("search_filter_${filter.name.lowercase()}")
                )
            }

            uiState.selectedGenre?.let { genre ->
                FilterChip(
                    selected = true,
                    onClick = { onSelectGenre(null) },
                    label = { Text("Genre: $genre") },
                    trailingIcon = {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear genre", modifier = Modifier.size(14.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.secondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when {
            uiState.selectedFilter == SearchFilter.ALBUMS && uiState.selectedAlbumName == null -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.matchingAlbums, key = { it.name }) { album ->
                        SearchAlbumCard(album = album, onClick = { onOpenAlbum(album.name) })
                    }
                }
            }

            uiState.selectedFilter == SearchFilter.ARTISTS && uiState.selectedArtistName == null -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.matchingArtists, key = { it.name }) { artist ->
                        SearchArtistRow(artist = artist, onClick = { onOpenArtist(artist.name) })
                    }
                }
            }

            else -> {
                val drillDownTitle = uiState.selectedAlbumName ?: uiState.selectedArtistName
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (drillDownTitle != null) {
                        item(key = "search_drilldown_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    IconButton(
                                        onClick = {
                                            if (uiState.selectedAlbumName != null) onOpenAlbum(null)
                                            else onOpenArtist(null)
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                    Text(
                                        text = drillDownTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(onClick = { onPlayAll(uiState.matchingTracks) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Play")
                                }
                            }
                        }
                    } else if (uiState.query.isBlank() && uiState.selectedGenre == null && uiState.genres.isNotEmpty()) {
                        item(key = "search_genres_section") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "BROWSE GENRES",
                                    style = TelemetryTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                uiState.genres.chunked(2).forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        pair.forEach { genre ->
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = StudioConsole,
                                                border = BorderStroke(1.dp, HairlineBorder),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onSelectGenre(genre) }
                                            ) {
                                                Text(
                                                    text = genre,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    modifier = Modifier.padding(14.dp)
                                                )
                                            }
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }

                    if (uiState.matchingTracks.isEmpty()) {
                        item(key = "search_empty_results") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (uiState.query.isNotBlank()) "No tracks match \"${uiState.query}\""
                                    else "Catalog tracks will appear here once connected.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(uiState.matchingTracks, key = { "search_${it.id}" }) { track ->
                            TrackListRow(
                                track = track,
                                isActive = track.id == currentPlayingTrackId,
                                isPlaying = track.id == currentPlayingTrackId && isPlaying,
                                onClick = { onPlayTrack(uiState.matchingTracks, track) },
                                onToggleLike = { onToggleLike(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioConsole,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box {
                AlbumArtworkImage(
                    artUrl = album.artUrl,
                    contentDescription = album.name,
                    cornerRadius = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(138.dp)
                )
                if (album.hasHiRes) {
                    HiResFormatBadge(
                        label = "HI-RES",
                        isHiRes = true,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${album.artist} · ${album.tracks.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchArtistRow(artist: ArtistGroup, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = StudioConsole,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArtworkImage(
                artUrl = artist.artUrl,
                contentDescription = artist.name,
                cornerRadius = 25.dp,
                modifier = Modifier.size(50.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
