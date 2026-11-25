package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.TrackEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ControllerSnapshot(
    val currentMediaId: String? = null,
    val queueMediaIds: List<String> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val errorMessage: String? = null
)

class PlaybackController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var positionTickerJob: Job? = null

    private val _snapshot = MutableStateFlow(ControllerSnapshot())
    val snapshot: StateFlow<ControllerSnapshot> = _snapshot.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncSnapshotFromPlayer(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _snapshot.value = _snapshot.value.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = "Playback failed (${error.errorCodeName}): ${error.localizedMessage ?: "Unable to stream audio"}"
            )
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    syncSnapshotFromPlayer(controller)
                    startPositionTicker()
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun disconnect() {
        positionTickerJob?.cancel()
        positionTickerJob = null
        mediaController?.removeListener(playerListener)
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val mediaItems = tracks.map { it.toMediaItem() }
        val controller = mediaController
        if (controller != null) {
            _snapshot.value = _snapshot.value.copy(errorMessage = null)
            controller.setMediaItems(mediaItems, safeIndex, 0L)
            controller.prepare()
            controller.play()
        } else {
            connect()
            scope.launch {
                repeat(20) {
                    delay(100)
                    mediaController?.let { ready ->
                        ready.setMediaItems(mediaItems, safeIndex, 0L)
                        ready.prepare()
                        ready.play()
                        return@launch
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                controller.prepare()
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs.coerceAtLeast(0L))
        _snapshot.value = _snapshot.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    fun skipNext() {
        mediaController?.let {
            if (it.hasNextMediaItem()) it.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        mediaController?.let {
            if (it.currentPosition > 4000L) {
                it.seekTo(0L)
            } else if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0L)
            }
        }
    }

    fun skipToQueueIndex(index: Int) {
        mediaController?.let {
            if (index in 0 until it.mediaItemCount) {
                it.seekToDefaultPosition(index)
                it.play()
            }
        }
    }

    fun toggleShuffle() {
        mediaController?.let {
            it.shuffleModeEnabled = !it.shuffleModeEnabled
        }
    }

    fun cycleRepeatMode() {
        mediaController?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun clearError() {
        _snapshot.value = _snapshot.value.copy(errorMessage = null)
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (isActive) {
                mediaController?.let { player ->
                    if (player.isPlaying) {
                        _snapshot.value = _snapshot.value.copy(
                            positionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = player.duration.takeIf { it > 0 } ?: _snapshot.value.durationMs
                        )
                    }
                }
                delay(250L)
            }
        }
    }

    private fun syncSnapshotFromPlayer(player: Player) {
        val queueIds = (0 until player.mediaItemCount).map { idx ->
            player.getMediaItemAt(idx).mediaId
        }
        val currentIdx = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        _snapshot.value = _snapshot.value.copy(
            currentMediaId = currentId,
            queueMediaIds = queueIds,
            currentQueueIndex = currentIdx,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode
        )
    }

    private fun TrackEntity.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (!albumArtUrl.isNullOrBlank()) {
                    setArtworkUri(Uri.parse(albumArtUrl))
                }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }
}
