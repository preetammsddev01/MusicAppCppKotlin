package com.example.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.data.model.EqualizerState
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class NativeDspAudioProcessor(
    private val onStateChanged: (isConfigured: Boolean, isProcessingActive: Boolean) -> Unit
) : BaseAudioProcessor() {

    private var nativeHandle: Long = NativeDspBridge.create()
    private var encodingType: Int = ENCODING_PCM_16

    @Synchronized
    fun updateDspState(state: EqualizerState) {
        ensureHandle()
        val gains = FloatArray(10) { idx -> state.bandLevelsDb.getOrElse(idx) { 0f } }
        NativeDspBridge.updateParameters(
            handle = nativeHandle,
            enabled = state.isDspEnabled,
            bandGainsDb = gains,
            bassBoostStrength = state.bassBoostStrength,
            virtualizerStrength = state.virtualizerStrength
        )
        onStateChanged(outputAudioFormat != AudioFormat.NOT_SET, isProcessingActiveNow())
    }

    fun isProcessingActiveNow(): Boolean {
        return NativeDspBridge.isProcessingActive(nativeHandle)
    }

    override fun isActive(): Boolean {
        return NativeDspBridge.isNativeLoaded && super.isActive()
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!NativeDspBridge.isNativeLoaded) {
            onStateChanged(false, false)
            return AudioFormat.NOT_SET
        }
        val mappedEncoding = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> ENCODING_PCM_16
            C.ENCODING_PCM_24BIT -> ENCODING_PCM_24
            C.ENCODING_PCM_32BIT -> ENCODING_PCM_32
            C.ENCODING_PCM_FLOAT -> ENCODING_PCM_FLOAT
            else -> throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..8) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }

        encodingType = mappedEncoding
        ensureHandle()
        NativeDspBridge.configure(
            handle = nativeHandle,
            sampleRateHz = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
            encodingType = encodingType
        )
        onStateChanged(true, isProcessingActiveNow())
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val outBuffer = replaceOutputBuffer(remaining)
        val frameBytes = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        val frameCount = remaining / frameBytes

        if (frameCount <= 0 || !NativeDspBridge.isProcessingActive(nativeHandle)) {
            // Bit-exact zero-modification bypass copy
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        val byteCount = frameCount * frameBytes
        if (inputBuffer.isDirect && outBuffer.isDirect) {
            val processed = NativeDspBridge.processDirectBuffer(
                handle = nativeHandle,
                inputBuffer = inputBuffer,
                inputOffsetBytes = inputBuffer.position(),
                outputBuffer = outBuffer,
                frameCount = frameCount
            )
            if (processed) {
                inputBuffer.position(inputBuffer.position() + byteCount)
                outBuffer.position(0)
                outBuffer.limit(byteCount)
                return
            }
        }

        // Safe fallback if upstream buffer was heap-backed: copy into direct outBuffer, process in-place
        val origLimit = inputBuffer.limit()
        inputBuffer.limit(inputBuffer.position() + byteCount)
        outBuffer.put(inputBuffer)
        inputBuffer.limit(origLimit)
        outBuffer.position(0)
        outBuffer.limit(byteCount)
        if (outBuffer.isDirect) {
            NativeDspBridge.processDirectBuffer(
                handle = nativeHandle,
                inputBuffer = outBuffer,
                inputOffsetBytes = 0,
                outputBuffer = outBuffer,
                frameCount = frameCount
            )
        }
    }

    override fun onFlush() {
        NativeDspBridge.resetState(nativeHandle)
    }

    override fun onReset() {
        NativeDspBridge.resetState(nativeHandle)
        onStateChanged(false, isProcessingActiveNow())
    }

    @Synchronized
    fun releaseNative() {
        if (nativeHandle != 0L) {
            NativeDspBridge.destroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    @Synchronized
    private fun ensureHandle() {
        if (nativeHandle == 0L && NativeDspBridge.isNativeLoaded) {
            nativeHandle = NativeDspBridge.create()
        }
    }

    companion object {
        private const val ENCODING_PCM_16 = 0
        private const val ENCODING_PCM_24 = 1
        private const val ENCODING_PCM_32 = 2
        private const val ENCODING_PCM_FLOAT = 3
    }
}
