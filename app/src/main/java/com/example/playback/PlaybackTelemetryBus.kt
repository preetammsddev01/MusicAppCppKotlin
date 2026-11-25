package com.example.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DecodedAudioTelemetry(
    val mimeType: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channelCount: Int = 0,
    val bitrateBps: Int = 0,
    val audioSessionId: Int = 0
)

class PlaybackTelemetryBus {
    private val _telemetry = MutableStateFlow(DecodedAudioTelemetry())
    val telemetry: StateFlow<DecodedAudioTelemetry> = _telemetry.asStateFlow()

    fun updateFormat(
        mimeType: String?,
        sampleRateHz: Int,
        bitDepth: Int,
        channelCount: Int,
        bitrateBps: Int
    ) {
        _telemetry.value = _telemetry.value.copy(
            mimeType = mimeType.orEmpty(),
            sampleRateHz = sampleRateHz.coerceAtLeast(0),
            bitDepth = bitDepth.coerceAtLeast(0),
            channelCount = channelCount.coerceAtLeast(0),
            bitrateBps = bitrateBps.coerceAtLeast(0)
        )
    }

    fun updateAudioSessionId(sessionId: Int) {
        _telemetry.value = _telemetry.value.copy(audioSessionId = sessionId)
    }

    fun resetFormat() {
        _telemetry.value = DecodedAudioTelemetry(audioSessionId = _telemetry.value.audioSessionId)
    }
}
