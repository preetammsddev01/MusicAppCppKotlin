package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.R
import com.example.data.model.TrackEntity
import com.example.ui.components.AlbumArtworkImage
import com.example.ui.components.HiResFormatBadge
import com.example.ui.components.formatDurationMs
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.LosslessCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.StudioConsole
import com.example.ui.theme.TelemetryTextStyle
import com.example.ui.theme.TonalRack
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.viewmodel.PlaybackUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingOverlay(
    playbackState: PlaybackUiState,
    isDspEnabled: Boolean,
    isNativeCppEngine: Boolean = false,
    isBitExactBypass: Boolean = !isDspEnabled,
    onCollapse: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: (TrackEntity) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onOpenEqualizer: () -> Unit,
    onDismissError: () -> Unit
) {
    BackHandler { onCollapse() }

    val track = playbackState.currentTrack ?: return
    var showQueueSheet by remember { mutableStateOf(false) }

    var isSeeking by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val displayPositionMs = if (isSeeking) dragPositionMs.toLong() else playbackState.positionMs
    val totalMs = playbackState.durationMs.coerceAtLeast(1L)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        playbackState.accentColor.copy(alpha = 0.28f),
                        playbackState.secondaryGlowColor.copy(alpha = 0.14f),
                        ObsidianBg
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 580.dp)
                .align(Alignment.Center)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_collapse_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Collapse player",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.now_playing_label),
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = track.album,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onOpenEqualizer,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_eq_shortcut")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Equalizer,
                        contentDescription = "Open Equalizer",
                        tint = if (isDspEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Expansive Album Cover
            AlbumArtworkImage(
                artUrl = track.albumArtUrl,
                contentDescription = "Album artwork for ${track.title}",
                cornerRadius = 24.dp,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .widthIn(max = 350.dp)
                    .aspectRatio(1f)
            )

            Spacer(modifier = Modifier.weight(0.6f))

            // Track Metadata + Like/Playlist row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onToggleLike,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_like_button")
                ) {
                    Icon(
                        imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (track.isLiked) "Unlike track" else "Like track",
                        tint = if (track.isLiked) VerifiedEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(
                    onClick = { onAddToPlaylist(track) },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_add_playlist_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add to playlist",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hi-Res Signal Path Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = StudioConsole.copy(alpha = 0.85f),
                border = BorderStroke(
                    1.dp,
                    if (playbackState.isHiResSignal) LosslessCyan.copy(alpha = 0.5f) else HairlineBorder
                ),
                modifier = Modifier.testTag("signal_path_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.HighQuality,
                        contentDescription = null,
                        tint = if (playbackState.isHiResSignal) LosslessCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = playbackState.signalDisplayLabel.ifBlank { track.detailedSignalLabel },
                        style = TelemetryTextStyle,
                        color = if (playbackState.isHiResSignal) LosslessCyan else MaterialTheme.colorScheme.onSurface
                    )
                    val pathSuffix = when {
                        isBitExactBypass || !isDspEnabled -> "· BIT-EXACT"
                        isNativeCppEngine -> "· C++ DSP"
                        else -> "· DSP"
                    }
                    val isDirect = isBitExactBypass || !isDspEnabled
                    Text(
                        text = pathSuffix,
                        style = TelemetryTextStyle.copy(fontWeight = FontWeight.Bold),
                        color = if (isDirect) VerifiedEmerald else MaterialTheme.colorScheme.primary
                    )
                }
            }

            playbackState.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ErrorCoral.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismissError)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorCoral,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Precision Scrubber
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = displayPositionMs.toFloat().coerceIn(0f, totalMs.toFloat()),
                    onValueChange = {
                        isSeeking = true
                        dragPositionMs = it
                    },
                    onValueChangeFinished = {
                        onSeekTo(dragPositionMs.toLong())
                        isSeeking = false
                    },
                    valueRange = 0f..totalMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = HairlineBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("now_playing_seek_slider")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationMs(displayPositionMs),
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDurationMs(playbackState.durationMs),
                        style = TelemetryTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_shuffle")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("now_playing_previous")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous track",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .testTag("now_playing_play_pause")
                        .clickable(onClick = onTogglePlayPause)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackState.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("now_playing_next")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next track",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("now_playing_repeat")
                ) {
                    val repeatIcon = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) {
                        Icons.Filled.RepeatOne
                    } else {
                        Icons.Filled.Repeat
                    }
                    val repeatTint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repeat mode",
                        tint = repeatTint
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Utility Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioConsole,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier
                        .testTag("open_queue_sheet_button")
                        .clickable { showQueueSheet = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Up Next (${playbackState.queue.size})",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        UpNextQueueSheet(
            queue = playbackState.queue,
            currentIndex = playbackState.currentQueueIndex,
            onSelectIndex = { idx ->
                onSelectQueueIndex(idx)
                showQueueSheet = false
            },
            onDismiss = { showQueueSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextQueueSheet(
    queue: List<TrackEntity>,
    currentIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioConsole,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Playback Queue",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(360.dp)
            ) {
                itemsIndexed(queue) { index, item ->
                    val isCurrent = index == currentIndex
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else TonalRack,
                        border = BorderStroke(
                            1.dp,
                            if (isCurrent) MaterialTheme.colorScheme.primary else HairlineBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectIndex(index) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = TelemetryTextStyle,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(26.dp)
                            )
                            AlbumArtworkImage(
                                artUrl = item.albumArtUrl,
                                contentDescription = item.title,
                                cornerRadius = 6.dp,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HiResFormatBadge(
                                label = item.shortFormatBadge,
                                isHiRes = item.isHiRes
                            )
                        }
                    }
                }
            }
        }
    }
}
