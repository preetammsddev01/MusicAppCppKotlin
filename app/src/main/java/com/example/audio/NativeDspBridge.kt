package com.example.audio

import android.util.Log
import java.nio.ByteBuffer

object NativeDspBridge {

    private const val TAG = "NativeDspBridge"

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("aether_dsp")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Native C++ DSP library unavailable, falling back to platform DSP", t)
        false
    }

    fun create(): Long = if (isNativeLoaded) nativeCreate() else 0L

    fun destroy(handle: Long) {
        if (isNativeLoaded && handle != 0L) {
            nativeDestroy(handle)
        }
    }

    fun configure(handle: Long, sampleRateHz: Int, channelCount: Int, encodingType: Int) {
        if (isNativeLoaded && handle != 0L) {
            nativeConfigure(handle, sampleRateHz, channelCount, encodingType)
        }
    }

    fun resetState(handle: Long) {
        if (isNativeLoaded && handle != 0L) {
            nativeResetState(handle)
        }
    }

    fun updateParameters(
        handle: Long,
        enabled: Boolean,
        bandGainsDb: FloatArray,
        bassBoostStrength: Int,
        virtualizerStrength: Int
    ) {
        if (isNativeLoaded && handle != 0L) {
            nativeUpdateParameters(handle, enabled, bandGainsDb, bassBoostStrength, virtualizerStrength)
        }
    }

    fun isProcessingActive(handle: Long): Boolean {
        return isNativeLoaded && handle != 0L && nativeIsProcessingActive(handle)
    }

    fun processDirectBuffer(
        handle: Long,
        inputBuffer: ByteBuffer,
        inputOffsetBytes: Int,
        outputBuffer: ByteBuffer,
        frameCount: Int
    ): Boolean {
        if (!isNativeLoaded || handle == 0L) return false
        return nativeProcessDirectBuffer(handle, inputBuffer, inputOffsetBytes, outputBuffer, frameCount)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConfigure(handle: Long, sampleRateHz: Int, channelCount: Int, encodingType: Int)
    private external fun nativeResetState(handle: Long)
    private external fun nativeUpdateParameters(
        handle: Long,
        enabled: Boolean,
        bandGainsDb: FloatArray,
        bassBoostStrength: Int,
        virtualizerStrength: Int
    )
    private external fun nativeIsProcessingActive(handle: Long): Boolean
    private external fun nativeProcessDirectBuffer(
        handle: Long,
        inputBuffer: ByteBuffer,
        inputOffsetBytes: Int,
        outputBuffer: ByteBuffer,
        frameCount: Int
    ): Boolean
}
