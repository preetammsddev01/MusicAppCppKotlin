package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.audio.EphemeralAudioCacheManager
import com.example.audio.TempPrepStatus
import com.example.data.model.TrackEntity
import com.example.data.remote.InvalidAudioStreamException
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
    val isPreparingAudio: Boolean = false,
    val prepStatusMessage: String? = null,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val errorMessage: String? = null
)

class PlaybackController(
    private val context: Context,
    private val cacheManager: EphemeralAudioCacheManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var positionTickerJob: Job? = null
    private var prepareSessionJob: Job? = null
    private var switchTrackJob: Job? = null
    private var activeQueueTracks: List<TrackEntity> = emptyList()
    private var suppressAutoTransitionGuard = false
    private var optimisticIsPlaying: Boolean? = null
    private var optimisticUntilElapsedMs: Long = 0L

    private val _snapshot = MutableStateFlow(ControllerSnapshot())
    val snapshot: StateFlow<ControllerSnapshot> = _snapshot.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncSnapshotFromPlayer(player, events)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (suppressAutoTransitionGuard) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null) {
                val uriScheme = mediaItem.localConfiguration?.uri?.scheme
                val controller = mediaController ?: return
                val idx = controller.currentMediaItemIndex
                val track = activeQueueTracks.getOrNull(idx)
                val alreadyReadyLocally = uriScheme == "file" ||
                    (track != null && cacheManager.getReadyUri(track.id) != null)
                if (!alreadyReadyLocally && idx in activeQueueTracks.indices) {
                    prepareAndSwitchToIndex(idx)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            optimisticIsPlaying = null
            _snapshot.value = _snapshot.value.copy(
                isPlaying = false,
                isBuffering = false,
                isPreparingAudio = false,
                prepStatusMessage = null,
                errorMessage = formatPlaybackError(error)
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
                    syncSnapshotFromPlayer(controller, events = null)
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
        val startTrack = tracks[safeIndex]
        activeQueueTracks = tracks

        prepareSessionJob?.cancel()
        switchTrackJob?.cancel()
        cacheManager.cancelQueuePreparation()
        connect()

        _positionMs.value = 0L
        optimisticIsPlaying = null
        _snapshot.value = _snapshot.value.copy(
            currentMediaId = startTrack.id,
            queueMediaIds = tracks.map { it.id },
            currentQueueIndex = safeIndex,
            isPlaying = false,
            isBuffering = true,
            isPreparingAudio = true,
            prepStatusMessage = "Downloading track locally...",
            durationMs = startTrack.durationMs,
            errorMessage = null
        )

        prepareSessionJob = scope.launch {
            val firstResult = cacheManager.prepareTrack(startTrack)
            if (firstResult.status != TempPrepStatus.READY || firstResult.localUri == null) {
                _snapshot.value = _snapshot.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPreparingAudio = false,
                    prepStatusMessage = null,
                    errorMessage = firstResult.errorMessage ?: "Unable to prepare track for playback."
                )
                return@launch
            }

            val controller = awaitController() ?: run {
                _snapshot.value = _snapshot.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPreparingAudio = false,
                    prepStatusMessage = null,
                    errorMessage = "Playback service unavailable."
                )
                return@launch
            }

            val mediaItems = tracks.mapIndexed { idx, track ->
                val resolvedUri = if (idx == safeIndex) {
                    firstResult.localUri
                } else {
                    cacheManager.getReadyUri(track.id) ?: track.streamUrl
                }
                track.toMediaItem(resolvedUri)
            }

            suppressAutoTransitionGuard = true
            try {
                controller.setMediaItems(mediaItems, safeIndex, 0L)
                controller.prepare()
                controller.play()
            } finally {
                suppressAutoTransitionGuard = false
            }

            _snapshot.value = _snapshot.value.copy(
                isPreparingAudio = false,
                prepStatusMessage = null
            )

            cacheManager.prepareQueueInBackground(tracks, safeIndex) { _, _, _ ->
                // Local file URIs are resolved transparently by PlaybackService's ResolvingDataSource
                // when ExoPlayer opens subsequent queue items, avoiding mid-playback timeline rebuilds.
            }
        }
    }

    fun togglePlayPause() {
        if (_snapshot.value.isPreparingAudio) return
        val controller = mediaController ?: return
        val currentlyPlaying = optimisticIsPlaying ?: controller.isPlaying
        val targetPlaying = !currentlyPlaying
        optimisticIsPlaying = targetPlaying
        optimisticUntilElapsedMs = SystemClock.elapsedRealtime() + 600L

        _snapshot.value = _snapshot.value.copy(
            isPlaying = targetPlaying,
            errorMessage = if (targetPlaying) null else _snapshot.value.errorMessage
        )

        if (currentlyPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount > 0) {
                controller.prepare()
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        _positionMs.value = clamped
        mediaController?.seekTo(clamped)
    }

    fun skipNext() {
        _snapshot.value = _snapshot.value.copy(errorMessage = null)
        val controller = mediaController ?: return
        if (!controller.hasNextMediaItem()) return
        val nextIdx = controller.nextMediaItemIndex
        if (nextIdx in activeQueueTracks.indices) {
            prepareAndSwitchToIndex(nextIdx)
        } else {
            controller.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        _snapshot.value = _snapshot.value.copy(errorMessage = null)
        val controller = mediaController ?: return
        if (controller.currentPosition > 4000L) {
            controller.seekTo(0L)
            return
        }
        if (controller.hasPreviousMediaItem()) {
            val prevIdx = controller.previousMediaItemIndex
            if (prevIdx in activeQueueTracks.indices) {
                prepareAndSwitchToIndex(prevIdx)
            } else {
                controller.seekToPreviousMediaItem()
            }
        } else {
            controller.seekTo(0L)
        }
    }

    fun skipToQueueIndex(index: Int) {
        _snapshot.value = _snapshot.value.copy(errorMessage = null)
        val controller = mediaController ?: return
        if (index in 0 until controller.mediaItemCount) {
            if (index in activeQueueTracks.indices) {
                prepareAndSwitchToIndex(index)
            } else {
                controller.seekToDefaultPosition(index)
                controller.play()
            }
        }
    }

    private fun prepareAndSwitchToIndex(targetIndex: Int) {
        val controller = mediaController ?: return
        val track = activeQueueTracks.getOrNull(targetIndex) ?: return
        val readyUri = cacheManager.getReadyUri(track.id)

        if (readyUri != null) {
            switchTrackJob?.cancel()
            suppressAutoTransitionGuard = true
            try {
                val itemUri = if (targetIndex in 0 until controller.mediaItemCount) {
                    controller.getMediaItemAt(targetIndex).localConfiguration?.uri?.toString()
                } else null
                if (itemUri != readyUri && targetIndex in 0 until controller.mediaItemCount) {
                    controller.replaceMediaItem(targetIndex, track.toMediaItem(readyUri))
                }
                controller.seekToDefaultPosition(targetIndex)
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                controller.play()
            } finally {
                suppressAutoTransitionGuard = false
            }
            _snapshot.value = _snapshot.value.copy(
                isPreparingAudio = false,
                prepStatusMessage = null
            )
            return
        }

        switchTrackJob?.cancel()
        controller.pause()
        _snapshot.value = _snapshot.value.copy(
            currentMediaId = track.id,
            currentQueueIndex = targetIndex,
            isPlaying = false,
            isBuffering = true,
            isPreparingAudio = true,
            prepStatusMessage = "Downloading track locally...",
            errorMessage = null
        )

        switchTrackJob = scope.launch {
            val result = cacheManager.prepareTrack(track)
            if (result.status != TempPrepStatus.READY || result.localUri == null) {
                _snapshot.value = _snapshot.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPreparingAudio = false,
                    prepStatusMessage = null,
                    errorMessage = result.errorMessage ?: "Unable to prepare track for playback."
                )
                return@launch
            }

            val ctrl = mediaController ?: return@launch
            if (targetIndex in 0 until ctrl.mediaItemCount) {
                suppressAutoTransitionGuard = true
                try {
                    ctrl.replaceMediaItem(targetIndex, track.toMediaItem(result.localUri))
                    ctrl.seekToDefaultPosition(targetIndex)
                    ctrl.prepare()
                    ctrl.play()
                } finally {
                    suppressAutoTransitionGuard = false
                }
            }
            _snapshot.value = _snapshot.value.copy(
                isPreparingAudio = false,
                prepStatusMessage = null
            )
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

    fun cancelAllPreparation() {
        prepareSessionJob?.cancel()
        prepareSessionJob = null
        switchTrackJob?.cancel()
        switchTrackJob = null
        cacheManager.cancelQueuePreparation()
    }

    fun cleanupTempCacheIfIdle() {
        val controller = mediaController
        val isActivelyPlaying = controller?.isPlaying == true ||
            (controller?.playWhenReady == true && controller.playbackState == Player.STATE_BUFFERING)
        if (!isActivelyPlaying && !_snapshot.value.isPreparingAudio) {
            cancelAllPreparation()
            controller?.stop()
            controller?.clearMediaItems()
            activeQueueTracks = emptyList()
            cacheManager.clearAll()
            optimisticIsPlaying = null
            _positionMs.value = 0L
            _snapshot.value = ControllerSnapshot()
        }
    }

    private suspend fun awaitController(): MediaController? {
        mediaController?.let { return it }
        repeat(30) {
            delay(100)
            mediaController?.let { return it }
        }
        return null
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (isActive) {
                mediaController?.let { player ->
                    if (player.isPlaying) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        if (_positionMs.value != pos) {
                            _positionMs.value = pos
                        }
                        val dur = player.duration
                        if (dur > 0L && _snapshot.value.durationMs != dur) {
                            _snapshot.value = _snapshot.value.copy(durationMs = dur)
                        }
                    }
                }
                delay(250L)
            }
        }
    }

    private fun syncSnapshotFromPlayer(player: Player, events: Player.Events?) {
        if (_snapshot.value.isPreparingAudio && player.mediaItemCount == 0) {
            return
        }
        val prev = _snapshot.value
        val shouldRefreshQueue = events == null ||
            events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            prev.queueMediaIds.size != player.mediaItemCount
        val queueIds = if (shouldRefreshQueue) {
            (0 until player.mediaItemCount).map { idx ->
                player.getMediaItemAt(idx).mediaId
            }
        } else {
            prev.queueMediaIds
        }

        val currentIdx = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        val actualIsPlaying = player.isPlaying
        val nowElapsed = SystemClock.elapsedRealtime()
        val resolvedIsPlaying = optimisticIsPlaying?.let { target ->
            if (actualIsPlaying == target || nowElapsed > optimisticUntilElapsedMs) {
                optimisticIsPlaying = null
                actualIsPlaying
            } else {
                target
            }
        } ?: actualIsPlaying

        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
        _snapshot.value = prev.copy(
            currentMediaId = currentId ?: prev.currentMediaId,
            queueMediaIds = if (queueIds.isNotEmpty()) queueIds else prev.queueMediaIds,
            currentQueueIndex = if (currentIdx >= 0) currentIdx else prev.currentQueueIndex,
            isPlaying = resolvedIsPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it > 0 } ?: prev.durationMs,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            errorMessage = if (actualIsPlaying) null else prev.errorMessage
        )
    }

    @OptIn(UnstableApi::class)
    private fun formatPlaybackError(error: PlaybackException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        val invalidStream = causes.filterIsInstance<InvalidAudioStreamException>().firstOrNull()
        if (invalidStream != null && !invalidStream.message.isNullOrBlank()) {
            return invalidStream.message!!
        }
        val isUnrecognizedFormat = causes.any {
            it is UnrecognizedInputFormatException ||
                it.javaClass.name.endsWith("UnrecognizedInputFormatException")
        }
        if (isUnrecognizedFormat) {
            return "Unsupported or invalid audio stream. The file could not be decoded into playable audio bytes."
        }
        val httpCodeError = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (httpCodeError != null) {
            return "Streaming server returned HTTP ${httpCodeError.responseCode}."
        }
        val ioError = causes.filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull()
        if (ioError != null) {
            return "Network error while streaming audio: ${ioError.cause?.localizedMessage ?: error.localizedMessage ?: "Connection failed"}"
        }
        return "Playback failed (${error.errorCodeName}): ${error.localizedMessage ?: "Unable to stream audio"}"
    }

    private fun TrackEntity.toMediaItem(uriOverride: String = streamUrl): MediaItem {
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
            .setUri(uriOverride)
            .setMediaMetadata(metadata)
            .build()
    }
}
