package com.example.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import com.example.data.model.EqualizerState
import com.example.data.model.TEN_BAND_FREQUENCIES_HZ
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class AudioDspController {

    private var currentSessionId: Int = 0
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    @Suppress("DEPRECATION")
    private var virtualizer: Virtualizer? = null

    private var lastAppliedState: EqualizerState = EqualizerState()

    private val _runtimeInfo = MutableStateFlow(
        DspRuntimeInfo(
            isTenBandActive = NativeDspBridge.isNativeLoaded || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            isNativeCppEngine = NativeDspBridge.isNativeLoaded,
            isBitExactBypass = true
        )
    )
    val runtimeInfo: StateFlow<DspRuntimeInfo> = _runtimeInfo.asStateFlow()

    val nativeAudioProcessor: NativeDspAudioProcessor = NativeDspAudioProcessor { isConfigured, isProcessingActive ->
        _runtimeInfo.value = _runtimeInfo.value.copy(
            isAttached = isConfigured || currentSessionId > 0,
            isTenBandActive = if (NativeDspBridge.isNativeLoaded) true else _runtimeInfo.value.isTenBandActive,
            isNativeCppEngine = NativeDspBridge.isNativeLoaded,
            isBitExactBypass = !isProcessingActive
        )
    }

    data class DspRuntimeInfo(
        val isAttached: Boolean = false,
        val isTenBandActive: Boolean = NativeDspBridge.isNativeLoaded || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
        val isNativeCppEngine: Boolean = NativeDspBridge.isNativeLoaded,
        val isBitExactBypass: Boolean = true,
        val activeBandFrequenciesHz: List<Int> = TEN_BAND_FREQUENCIES_HZ
    )

    @Synchronized
    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        currentSessionId = audioSessionId

        if (NativeDspBridge.isNativeLoaded) {
            // Native C++ AudioProcessor handles 10-band EQ, Bass Boost, Virtualizer, and bit-exact bypass
            _runtimeInfo.value = _runtimeInfo.value.copy(
                isAttached = true,
                isTenBandActive = true,
                isNativeCppEngine = true,
                isBitExactBypass = !nativeAudioProcessor.isProcessingActiveNow(),
                activeBandFrequenciesHz = TEN_BAND_FREQUENCIES_HZ
            )
            applyState(lastAppliedState)
            return
        }

        if (dynamicsProcessing != null || legacyEqualizer != null) {
            applyState(lastAppliedState)
            return
        }

        releaseEffects()
        currentSessionId = audioSessionId

        var tenBandReady = false
        var activeFreqs = TEN_BAND_FREQUENCIES_HZ

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tenBandReady = initDynamicsProcessing(audioSessionId)
        }

        if (!tenBandReady) {
            val legacyFreqs = initLegacyEqualizer(audioSessionId)
            if (legacyFreqs.isNotEmpty()) {
                activeFreqs = legacyFreqs
            }
        }

        runCatching {
            bassBoost = BassBoost(0, audioSessionId)
        }.onFailure {
            Log.w(TAG, "BassBoost unavailable on session $audioSessionId", it)
        }

        @Suppress("DEPRECATION")
        runCatching {
            virtualizer = Virtualizer(0, audioSessionId)
        }.onFailure {
            Log.w(TAG, "Virtualizer unavailable on session $audioSessionId", it)
        }

        _runtimeInfo.value = DspRuntimeInfo(
            isAttached = dynamicsProcessing != null || legacyEqualizer != null,
            isTenBandActive = tenBandReady,
            isNativeCppEngine = false,
            isBitExactBypass = !lastAppliedState.isDspEnabled,
            activeBandFrequenciesHz = activeFreqs
        )

        applyState(lastAppliedState)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun initDynamicsProcessing(audioSessionId: Int): Boolean {
        return runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true,
                TEN_BAND_FREQUENCIES_HZ.size,
                false,
                0,
                false,
                0,
                true
            ).build()

            val dp = DynamicsProcessing(0, audioSessionId, config)
            val preEq = DynamicsProcessing.Eq(true, true, TEN_BAND_FREQUENCIES_HZ.size)
            TEN_BAND_FREQUENCIES_HZ.forEachIndexed { index, freqHz ->
                preEq.getBand(index).apply {
                    isEnabled = true
                    cutoffFrequency = freqHz.toFloat()
                    gain = 0f
                }
            }
            dp.setPreEqAllChannelsTo(preEq)

            val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.5f, 0f)
            dp.setLimiterAllChannelsTo(limiter)
            dp.enabled = false
            dynamicsProcessing = dp
            true
        }.getOrElse { e ->
            Log.w(TAG, "DynamicsProcessing init failed, falling back to 5-band Equalizer", e)
            dynamicsProcessing?.release()
            dynamicsProcessing = null
            false
        }
    }

    private fun initLegacyEqualizer(audioSessionId: Int): List<Int> {
        return runCatching {
            val eq = Equalizer(0, audioSessionId)
            val numBands = eq.numberOfBands.toInt().coerceAtLeast(0)
            val freqs = (0 until numBands).map { band ->
                (eq.getCenterFreq(band.toShort()) / 1000).coerceAtLeast(20)
            }
            legacyEqualizer = eq
            freqs
        }.getOrElse { e ->
            Log.w(TAG, "Legacy Equalizer init failed", e)
            legacyEqualizer = null
            emptyList()
        }
    }

    @Synchronized
    fun applyState(state: EqualizerState) {
        lastAppliedState = state

        if (NativeDspBridge.isNativeLoaded) {
            nativeAudioProcessor.updateDspState(state)
            _runtimeInfo.value = _runtimeInfo.value.copy(
                isTenBandActive = true,
                isNativeCppEngine = true,
                isBitExactBypass = !nativeAudioProcessor.isProcessingActiveNow()
            )
            return
        }

        val enabled = state.isDspEnabled
        val isFlat = state.bandLevelsDb.all { kotlin.math.abs(it) < 0.05f } &&
            state.bassBoostStrength == 0 && state.virtualizerStrength == 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing?.let { dp ->
                runCatching {
                    val preEq = DynamicsProcessing.Eq(true, enabled, TEN_BAND_FREQUENCIES_HZ.size)
                    TEN_BAND_FREQUENCIES_HZ.forEachIndexed { index, freqHz ->
                        val db = state.bandLevelsDb.getOrElse(index) { 0f }.coerceIn(-15f, 15f)
                        preEq.getBand(index).apply {
                            isEnabled = enabled
                            cutoffFrequency = freqHz.toFloat()
                            gain = db
                        }
                    }
                    dp.setPreEqAllChannelsTo(preEq)
                    dp.enabled = enabled
                }.onFailure { Log.w(TAG, "Failed updating DynamicsProcessing", it) }
            }
        }

        legacyEqualizer?.let { eq ->
            runCatching {
                eq.enabled = enabled
                val range = eq.bandLevelRange
                val minMb = range[0].toFloat()
                val maxMb = range[1].toFloat()
                val numBands = eq.numberOfBands.toInt()
                for (band in 0 until numBands) {
                    val mappedIdx = ((band.toFloat() / (numBands - 1).coerceAtLeast(1)) * 9f).roundToInt()
                    val targetDb = state.bandLevelsDb.getOrElse(mappedIdx) { 0f }.coerceIn(-15f, 15f)
                    val millibels = (targetDb * 100f).coerceIn(minMb, maxMb).toInt().toShort()
                    eq.setBandLevel(band.toShort(), millibels)
                }
            }.onFailure { Log.w(TAG, "Failed updating legacy Equalizer", it) }
        }

        bassBoost?.let { bb ->
            runCatching {
                val strength = state.bassBoostStrength.coerceIn(0, 1000)
                bb.enabled = enabled && strength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(strength.toShort())
                }
            }.onFailure { Log.w(TAG, "Failed updating BassBoost", it) }
        }

        @Suppress("DEPRECATION")
        virtualizer?.let { virt ->
            runCatching {
                val strength = state.virtualizerStrength.coerceIn(0, 1000)
                virt.enabled = enabled && strength > 0
                if (virt.strengthSupported) {
                    virt.setStrength(strength.toShort())
                }
            }.onFailure { Log.w(TAG, "Failed updating Virtualizer", it) }
        }

        _runtimeInfo.value = _runtimeInfo.value.copy(
            isNativeCppEngine = false,
            isBitExactBypass = !enabled || isFlat
        )
    }

    @Synchronized
    fun releaseEffects() {
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        runCatching { legacyEqualizer?.release() }
        legacyEqualizer = null
        runCatching { bassBoost?.release() }
        bassBoost = null
        @Suppress("DEPRECATION")
        runCatching { virtualizer?.release() }
        virtualizer = null
        currentSessionId = 0
        _runtimeInfo.value = _runtimeInfo.value.copy(isAttached = false)
    }

    companion object {
        private const val TAG = "AudioDspController"
    }
}
