package com.example.ui.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.example.audio.EphemeralAudioCacheManager
import com.example.audio.TempPrepStatus
import com.example.audio.TempTrackState
import com.example.data.model.TrackEntity
import com.example.data.remote.CloudMusicRepository
import com.example.playback.DecodedAudioTelemetry
import com.example.playback.PlaybackController
import com.example.playback.PlaybackTelemetryBus
import com.example.ui.theme.LosslessCyan
import com.example.ui.theme.StudioAmber
import com.example.ui.util.AlbumArtPaletteExtractor
import com.example.ui.util.ExtractedAlbumColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val currentTrack: TrackEntity? = null,
    val queue: List<TrackEntity> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val liveTelemetry: DecodedAudioTelemetry = DecodedAudioTelemetry(),
    val signalDisplayLabel: String = "",
    val isHiResSignal: Boolean = false,
    val accentColor: Color = StudioAmber,
    val secondaryGlowColor: Color = LosslessCyan,
    val isPreparingAudio: Boolean = false,
    val prepStatusMessage: String? = null,
    val prepProgress: Float = 0f,
    val currentPrepState: TempTrackState? = null,
    val queuePrepStates: Map<String, TempTrackState> = emptyMap(),
    val cachedQueueCount: Int = 0,
    val errorMessage: String? = null
)

class PlayerViewModel(
    private val repository: CloudMusicRepository,
    private val playbackController: PlaybackController,
    private val cacheManager: EphemeralAudioCacheManager,
    private val telemetryBus: PlaybackTelemetryBus,
    private val paletteExtractor: AlbumArtPaletteExtractor
) : ViewModel() {

    private val extractedColors = MutableStateFlow(ExtractedAlbumColors())
    val accentColors: StateFlow<ExtractedAlbumColors> = extractedColors
    val positionMs: StateFlow<Long> = playbackController.positionMs

    private val playerAndLibraryFlow = combine(
        playbackController.snapshot,
        repository.observeAllTracks(),
        telemetryBus.telemetry,
        extractedColors
    ) { snapshot, tracks, telemetry, colors ->
        PlayerBaseBundle(snapshot, tracks, telemetry, colors)
    }

    val uiState: StateFlow<PlaybackUiState> = combine(
        playerAndLibraryFlow,
        cacheManager.states
    ) { base, prepStates ->
        val snapshot = base.snapshot
        val trackMap = base.tracks.associateBy { it.id }
        val queue = snapshot.queueMediaIds.mapNotNull { trackMap[it] }
        val currentTrack = snapshot.currentMediaId?.let { trackMap[it] }
            ?: queue.getOrNull(snapshot.currentQueueIndex)

        val resolvedDuration = when {
            snapshot.durationMs > 0L -> snapshot.durationMs
            currentTrack != null && currentTrack.durationMs > 0L -> currentTrack.durationMs
            else -> 0L
        }

        val currentPrep = currentTrack?.let { prepStates[it.id] }
        val cachedCount = queue.count { prepStates[it.id]?.status == TempPrepStatus.READY }
        val signalLabel = buildSignalLabel(currentTrack, base.telemetry)
        val isHiRes = (currentTrack?.isHiRes == true) ||
            (base.telemetry.sampleRateHz > 44100 || base.telemetry.bitDepth >= 24)

        val activePrepMsg = snapshot.prepStatusMessage ?: when (currentPrep?.status) {
            TempPrepStatus.DOWNLOADING -> {
                val pct = (currentPrep.progress * 100).toInt()
                if (pct > 0) "Downloading audio · $pct%" else "Downloading audio..."
            }
            TempPrepStatus.CONVERTING -> "Converting audio bytes..."
            else -> null
        }

        PlaybackUiState(
            currentTrack = currentTrack,
            queue = queue,
            currentQueueIndex = snapshot.currentQueueIndex,
            isPlaying = snapshot.isPlaying,
            isBuffering = snapshot.isBuffering || snapshot.isPreparingAudio,
            durationMs = resolvedDuration,
            shuffleEnabled = snapshot.shuffleEnabled,
            repeatMode = snapshot.repeatMode,
            liveTelemetry = base.telemetry,
            signalDisplayLabel = signalLabel,
            isHiResSignal = isHiRes,
            accentColor = base.colors.primaryAccent,
            secondaryGlowColor = base.colors.secondaryGlow,
            isPreparingAudio = snapshot.isPreparingAudio ||
                currentPrep?.status == TempPrepStatus.DOWNLOADING ||
                currentPrep?.status == TempPrepStatus.CONVERTING,
            prepStatusMessage = activePrepMsg,
            prepProgress = currentPrep?.progress ?: 0f,
            currentPrepState = currentPrep,
            queuePrepStates = prepStates,
            cachedQueueCount = cachedCount,
            errorMessage = snapshot.errorMessage
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PlaybackUiState()
        )

    private data class PlayerBaseBundle(
        val snapshot: com.example.playback.ControllerSnapshot,
        val tracks: List<TrackEntity>,
        val telemetry: DecodedAudioTelemetry,
        val colors: com.example.ui.util.ExtractedAlbumColors
    )

    init {
        viewModelScope.launch {
            uiState
                .map { it.currentTrack?.albumArtUrl }
                .distinctUntilChanged()
                .collect { artUrl ->
                    extractedColors.value = paletteExtractor.extractColors(artUrl)
                }
        }
        viewModelScope.launch {
            uiState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collect { trackId ->
                    if (!trackId.isNullOrBlank()) {
                        repository.recordTrackPlayed(trackId)
                    }
                }
        }
    }

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun seekTo(ms: Long) = playbackController.seekTo(ms)
    fun skipNext() = playbackController.skipNext()
    fun skipPrevious() = playbackController.skipPrevious()
    fun skipToQueueIndex(index: Int) = playbackController.skipToQueueIndex(index)
    fun toggleShuffle() = playbackController.toggleShuffle()
    fun cycleRepeatMode() = playbackController.cycleRepeatMode()
    fun dismissError() = playbackController.clearError()

    fun toggleCurrentTrackLike() {
        val current = uiState.value.currentTrack ?: return
        viewModelScope.launch { repository.toggleLike(current) }
    }

    private fun buildSignalLabel(track: TrackEntity?, telemetry: DecodedAudioTelemetry): String {
        val mimeCodec = when {
            telemetry.mimeType.contains("flac", ignoreCase = true) -> "FLAC"
            telemetry.mimeType.contains("alac", ignoreCase = true) -> "ALAC"
            telemetry.mimeType.contains("wav", ignoreCase = true) ||
                telemetry.mimeType.contains("raw", ignoreCase = true) -> "PCM"
            telemetry.mimeType.contains("opus", ignoreCase = true) -> "OPUS"
            telemetry.mimeType.contains("vorbis", ignoreCase = true) -> "OGG"
            telemetry.mimeType.contains("mpeg", ignoreCase = true) -> "MP3"
            telemetry.mimeType.contains("mp4a", ignoreCase = true) -> "AAC"
            else -> track?.format?.uppercase().orEmpty()
        }
        val sampleRate = telemetry.sampleRateHz.takeIf { it > 0 } ?: (track?.sampleRateHz ?: 0)
        val bitDepth = telemetry.bitDepth.takeIf { it > 0 } ?: (track?.bitDepth ?: 0)
        val bitrateKbps = (telemetry.bitrateBps / 1000).takeIf { it > 0 } ?: (track?.bitrateKbps ?: 0)

        val parts = mutableListOf<String>()
        if (mimeCodec.isNotEmpty()) parts.add(mimeCodec)
        if (bitDepth > 0 && sampleRate > 0) {
            val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz"
            else String.format("%.1f kHz", sampleRate / 1000.0)
            parts.add("${bitDepth}-bit / $khz")
        } else if (sampleRate > 0) {
            parts.add(String.format("%.1f kHz", sampleRate / 1000.0))
        }
        if (bitrateKbps > 0) parts.add("$bitrateKbps kbps")
        return parts.joinToString(" · ")
    }
}
