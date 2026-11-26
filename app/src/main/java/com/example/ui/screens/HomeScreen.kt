package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TrackEntity
import com.example.ui.components.AlbumArtworkImage
import com.example.ui.components.AnimatedWaveformBars
import com.example.ui.components.HiResFormatBadge
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.LosslessCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StudioConsole
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.theme.TonalRack
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.viewmodel.AlbumGroup
import com.example.ui.viewmodel.HomeUiState

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentPlayingTrackId: String?,
    isPlaying: Boolean,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onPlayTrack: (List<TrackEntity>, TrackEntity) -> Unit,
    onPlayAlbum: (AlbumGroup) -> Unit,
    onToggleLike: (TrackEntity) -> Unit,
    onOpenAlbumInSearch: (String) -> Unit,
    onOpenGenreInSearch: (String) -> Unit,
    bottomContentPadding: PaddingValues
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = bottomContentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Greeting Header
        item(key = "home_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HighQuality,
                            contentDescription = null,
                            tint = LosslessCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (uiState.totalTrackCount > 0) {
                                "HI-RES STREAMING · ${uiState.totalTrackCount} TRACKS"
                            } else {
                                "AETHER HI-RES STREAMING"
                            },
                            style = TelemetryTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !uiState.isSyncing,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("home_refresh_button")
                ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh catalog",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        if (uiState.totalTrackCount == 0 && !uiState.isSyncing) {
            item(key = "home_unavailable_state") {
                StreamUnavailableCard(
                    isConfigured = uiState.isServerConfigured,
                    errorMessage = uiState.syncError,
                    onRetry = onRefresh
                )
            }
            return@LazyColumn
        }

        uiState.syncError?.let { err ->
            item(key = "home_sync_error") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ErrorCoral.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismissError)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorCoral,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        // Featured Hi-Res Hero Banner
        uiState.featuredTrack?.let { featured ->
            item(key = "home_featured_hero") {
                FeaturedReleaseHeroCard(
                    track = featured,
                    onPlayClick = {
                        val list = uiState.hiResReleases.ifEmpty { listOf(featured) }
                        onPlayTrack(list, featured)
                    },
                    onToggleLike = { onToggleLike(featured) }
                )
            }
        }

        // Quick Picks 2-Column Compact Grid (Spotify-style)
        if (uiState.quickPicks.isNotEmpty()) {
            item(key = "home_quick_picks") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.home_quick_picks),
                        style = MaterialTheme.typography.titleLarge
                    )
                    val rows = uiState.quickPicks.chunked(2)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { track ->
                                    QuickPickTile(
                                        track = track,
                                        isActive = track.id == currentPlayingTrackId,
                                        isPlaying = track.id == currentPlayingTrackId && isPlaying,
                                        onClick = { onPlayTrack(uiState.quickPicks, track) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Recently Played Shelf
        if (uiState.recentlyPlayed.isNotEmpty()) {
            item(key = "home_recently_played") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.home_recently_played),
                        style = MaterialTheme.typography.titleLarge
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.recentlyPlayed, key = { "recent_${it.id}" }) { track ->
                            SquareTrackCard(
                                track = track,
                                isActive = track.id == currentPlayingTrackId,
                                onClick = { onPlayTrack(uiState.recentlyPlayed, track) }
                            )
                        }
                    }
                }
            }
        }

        // Hi-Res FLAC Releases Shelf
        if (uiState.hiResReleases.isNotEmpty()) {
            item(key = "home_hires_releases") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.home_hires_releases),
                        style = MaterialTheme.typography.titleLarge
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.hiResReleases, key = { "hires_${it.id}" }) { track ->
                            SquareTrackCard(
                                track = track,
                                isActive = track.id == currentPlayingTrackId,
                                onClick = { onPlayTrack(uiState.hiResReleases, track) }
                            )
                        }
                    }
                }
            }
        }

        // Albums Shelf
        if (uiState.albums.isNotEmpty()) {
            item(key = "home_albums") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.home_albums),
                        style = MaterialTheme.typography.titleLarge
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.albums, key = { "album_${it.name}" }) { album ->
                            AlbumShelfCard(
                                album = album,
                                onClick = { onOpenAlbumInSearch(album.name) },
                                onPlayAlbum = { onPlayAlbum(album) }
                            )
                        }
                    }
                }
            }
        }

        // Genres Strip
        if (uiState.genres.isNotEmpty()) {
            item(key = "home_genres") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Browse Genres",
                        style = MaterialTheme.typography.titleLarge
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.genres, key = { "genre_$it" }) { genre ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = StudioConsole,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier
                                    .testTag("home_genre_$genre")
                                    .clickable { onOpenGenreInSearch(genre) }
                            ) {
                                Text(
                                    text = genre,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedReleaseHeroCard(
    track: TrackEntity,
    onPlayClick: () -> Unit,
    onToggleLike: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = StudioConsole,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("featured_hero_card")
            .clickable(onClick = onPlayClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            StudioConsole
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AlbumArtworkImage(
                    artUrl = track.albumArtUrl,
                    contentDescription = "Featured cover ${track.album}",
                    cornerRadius = 14.dp,
                    modifier = Modifier.size(104.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    HiResFormatBadge(
                        label = "${stringResource(R.string.home_featured_label)} · ${track.shortFormatBadge}",
                        isHiRes = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track.artist} · ${track.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onPlayClick,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("featured_play_button")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stream Now", style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(
                            onClick = onToggleLike,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Like featured track",
                                tint = if (track.isLiked) VerifiedEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPickTile(
    track: TrackEntity,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else TonalRack,
        border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else HairlineBorder),
        modifier = modifier
            .height(58.dp)
            .testTag("quick_pick_${track.id}")
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            AlbumArtworkImage(
                artUrl = track.albumArtUrl,
                contentDescription = track.title,
                cornerRadius = 0.dp,
                modifier = Modifier.size(58.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            if (isPlaying) {
                AnimatedWaveformBars(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SquareTrackCard(
    track: TrackEntity,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            AlbumArtworkImage(
                artUrl = track.albumArtUrl,
                contentDescription = track.title,
                cornerRadius = 12.dp,
                modifier = Modifier.size(136.dp)
            )
            HiResFormatBadge(
                label = track.shortFormatBadge,
                isHiRes = track.isHiRes,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AlbumShelfCard(
    album: AlbumGroup,
    onClick: () -> Unit,
    onPlayAlbum: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            AlbumArtworkImage(
                artUrl = album.artUrl,
                contentDescription = album.name,
                cornerRadius = 12.dp,
                modifier = Modifier.size(148.dp)
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPlayAlbum)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play ${album.name}",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
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

@Composable
private fun StreamUnavailableCard(
    isConfigured: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = StudioConsole,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.catalog_unavailable_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    !isConfigured -> stringResource(R.string.catalog_unavailable_subtitle)
                    !errorMessage.isNullOrBlank() -> errorMessage
                    else -> "Could not reach the streaming catalog. Check your internet connection and retry."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("home_retry_stream_button")
            ) {
                Text("Retry Connection")
            }
        }
    }
}
