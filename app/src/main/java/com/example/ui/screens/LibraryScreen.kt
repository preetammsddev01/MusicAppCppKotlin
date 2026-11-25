package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.PlaylistWithTracks
import com.example.data.model.TrackEntity
import com.example.ui.components.AlbumArtworkImage
import com.example.ui.components.HiResFormatBadge
import com.example.ui.components.TrackListRow
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.LosslessCyan
import com.example.ui.theme.StudioConsole
import com.example.ui.theme.TonalRack
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.viewmodel.LibraryDetailView
import com.example.ui.viewmodel.LibraryTab
import com.example.ui.viewmodel.LibraryUiState

@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenDetail: (LibraryDetailView) -> Unit,
    onCloseDetail: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayTrack: (List<TrackEntity>, TrackEntity) -> Unit,
    onPlayAll: (List<TrackEntity>) -> Unit,
    bottomContentPadding: PaddingValues
) {
    if (uiState.detailView !is LibraryDetailView.None) {
        BackHandler { onCloseDetail() }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var createInput by remember { mutableStateOf("") }
    var renamingPlaylist by remember { mutableStateOf<PlaylistWithTracks?>(null) }
    var renameInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.detailView !is LibraryDetailView.None) {
            LibraryDetailContent(
                uiState = uiState,
                currentPlayingTrackId = currentPlayingTrackId,
                isPlaying = isPlaying,
                onBack = onCloseDetail,
                onRenamePlaylist = { pl ->
                    renamingPlaylist = pl
                    renameInput = pl.playlist.name
                },
                onDeletePlaylist = onDeletePlaylist,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onToggleLike = onToggleLike,
                onAddToPlaylist = onAddToPlaylist,
                onPlayTrack = onPlayTrack,
                onPlayAll = onPlayAll,
                bottomContentPadding = bottomContentPadding
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(
                    onClick = { showCreateDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, HairlineBorder),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("create_playlist_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.create_playlist_action))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryTab.entries.forEach { tab ->
                    val selected = uiState.activeTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectTab(tab) },
                        label = { Text(tab.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = StudioConsole,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = HairlineBorder,
                            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.testTag("library_tab_${tab.name.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (uiState.activeTab) {
                LibraryTab.PLAYLISTS -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "liked_songs_pinned") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioConsole,
                                border = BorderStroke(1.dp, VerifiedEmerald.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("liked_songs_card")
                                    .clickable { onOpenDetail(LibraryDetailView.LikedSongs) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(VerifiedEmerald, LosslessCyan)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = null,
                                            tint = StudioConsole,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.liked_songs_title),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "${uiState.likedTracks.size} liked tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "recently_played_pinned") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioConsole,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recently_played_card")
                                    .clickable { onOpenDetail(LibraryDetailView.RecentlyPlayed) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(TonalRack),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.home_recently_played),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "${uiState.recentlyPlayed.size} played tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        items(uiState.playlists, key = { it.playlist.id }) { pl ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioConsole,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("playlist_card_${pl.playlist.id}")
                                    .clickable { onOpenDetail(LibraryDetailView.PlaylistDetail(pl.playlist.id)) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val cover = pl.tracks.firstOrNull { !it.albumArtUrl.isNullOrBlank() }?.albumArtUrl
                                    if (cover != null) {
                                        AlbumArtworkImage(
                                            artUrl = cover,
                                            contentDescription = pl.playlist.name,
                                            cornerRadius = 10.dp,
                                            modifier = Modifier.size(54.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(TonalRack),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pl.playlist.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${pl.tracks.size} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeletePlaylist(pl.playlist.id) },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete ${pl.playlist.name}",
                                            tint = ErrorCoral
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LibraryTab.LIKED -> {
                    TrackCollectionList(
                        tracks = uiState.likedTracks,
                        emptyText = "Tap the heart icon on any track to save it to Liked Songs.",
                        currentPlayingTrackId = currentPlayingTrackId,
                        isPlaying = isPlaying,
                        onToggleLike = onToggleLike,
                        onAddToPlaylist = onAddToPlaylist,
                        onPlayTrack = onPlayTrack,
                        bottomContentPadding = bottomContentPadding
                    )
                }

                LibraryTab.ALBUMS -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.albums, key = { it.name }) { album ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = StudioConsole,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDetail(LibraryDetailView.AlbumDetail(album.name)) }
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
                                        text = album.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                LibraryTab.ARTISTS -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.artists, key = { it.name }) { artist ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = StudioConsole,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDetail(LibraryDetailView.ArtistDetail(artist.name)) }
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
                                        Text(artist.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = StudioConsole,
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = createInput,
                    onValueChange = { createInput = it },
                    label = { Text("Playlist Name") },
                    placeholder = { Text("e.g. Late Night Jazz FLAC") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_playlist_dialog_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreatePlaylist(createInput)
                        createInput = ""
                        showCreateDialog = false
                    },
                    modifier = Modifier.testTag("confirm_create_playlist_button")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    renamingPlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { renamingPlaylist = null },
            containerColor = StudioConsole,
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenamePlaylist(target.playlist.id, renameInput)
                        renamingPlaylist = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingPlaylist = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LibraryDetailContent(
    uiState: LibraryUiState,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onRenamePlaylist: (PlaylistWithTracks) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayTrack: (List<TrackEntity>, TrackEntity) -> Unit,
    onPlayAll: (List<TrackEntity>) -> Unit,
    bottomContentPadding: PaddingValues
) {
    val activePlaylist: PlaylistWithTracks? = (uiState.detailView as? LibraryDetailView.PlaylistDetail)?.let { detail ->
        uiState.playlists.firstOrNull { it.playlist.id == detail.playlistId }
    }

    val (title, tracks) = when (val d = uiState.detailView) {
        LibraryDetailView.LikedSongs -> "Liked Songs" to uiState.likedTracks
        LibraryDetailView.RecentlyPlayed -> "Recently Played" to uiState.recentlyPlayed
        is LibraryDetailView.PlaylistDetail -> (activePlaylist?.playlist?.name ?: "Playlist") to (activePlaylist?.tracks ?: emptyList())
        is LibraryDetailView.AlbumDetail -> d.albumName to (uiState.albums.firstOrNull { it.name == d.albumName }?.tracks ?: emptyList())
        is LibraryDetailView.ArtistDetail -> d.artistName to (uiState.artists.firstOrNull { it.name == d.artistName }?.tracks ?: emptyList())
        LibraryDetailView.None -> "" to emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("library_detail_back")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activePlaylist != null) {
                    IconButton(onClick = { onRenamePlaylist(activePlaylist) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename playlist")
                    }
                    IconButton(onClick = { onDeletePlaylist(activePlaylist.playlist.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete playlist", tint = ErrorCoral)
                    }
                }
                if (tracks.isNotEmpty()) {
                    Button(
                        onClick = { onPlayAll(tracks) },
                        modifier = Modifier.testTag("library_detail_play_all")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (activePlaylist != null) {
            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomContentPadding.calculateBottomPadding()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "This playlist is empty. Add tracks from Home, Search, or Now Playing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tracks, key = { "pl_${activePlaylist.playlist.id}_${it.id}" }) { track ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TrackListRow(
                                    track = track,
                                    isActive = track.id == currentPlayingTrackId,
                                    isPlaying = track.id == currentPlayingTrackId && isPlaying,
                                    onClick = { onPlayTrack(tracks, track) },
                                    onToggleLike = { onToggleLike(track) },
                                    onAddToPlaylist = { onAddToPlaylist(track) }
                                )
                            }
                            IconButton(
                                onClick = { onRemoveFromPlaylist(activePlaylist.playlist.id, track.id) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.RemoveCircleOutline,
                                    contentDescription = "Remove from playlist",
                                    tint = ErrorCoral
                                )
                            }
                        }
                    }
                }
            }
        } else {
            TrackCollectionList(
                tracks = tracks,
                emptyText = "No tracks in this collection yet.",
                currentPlayingTrackId = currentPlayingTrackId,
                isPlaying = isPlaying,
                onToggleLike = onToggleLike,
                onAddToPlaylist = onAddToPlaylist,
                onPlayTrack = onPlayTrack,
                bottomContentPadding = bottomContentPadding
            )
        }
    }
}

@Composable
private fun TrackCollectionList(
    tracks: List<TrackEntity>,
    emptyText: String,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    onToggleLike: (TrackEntity) -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onPlayTrack: (List<TrackEntity>, TrackEntity) -> Unit,
    bottomContentPadding: PaddingValues
) {
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomContentPadding.calculateBottomPadding()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomContentPadding.calculateBottomPadding() + 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(tracks, key = { "col_${it.id}" }) { track ->
                TrackListRow(
                    track = track,
                    isActive = track.id == currentPlayingTrackId,
                    isPlaying = track.id == currentPlayingTrackId && isPlaying,
                    onClick = { onPlayTrack(tracks, track) },
                    onToggleLike = { onToggleLike(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) }
                )
            }
        }
    }
}
