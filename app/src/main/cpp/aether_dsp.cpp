#include "aether_dsp.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace aether {

namespace {

constexpr double kPi = 3.14159265358979323846;

inline int32_t bytesPerSample(PcmEncoding encoding) {
    switch (encoding) {
        case PcmEncoding::Pcm16Bit:
            return 2;
        case PcmEncoding::Pcm24Bit:
            return 3;
        case PcmEncoding::Pcm32Bit:
        case PcmEncoding::PcmFloat:
            return 4;
    }
    return 2;
}

inline double softLimit(double sample) {
    // Transparent linear region up to -0.45 dBFS (0.95), smooth tanh saturation above
    constexpr double kThreshold = 0.95;
    const double absVal = std::abs(sample);
    if (absVal <= kThreshold) {
        return sample;
    }
    const double excess = absVal - kThreshold;
    const double headroom = 1.0 - kThreshold;
    const double compressed = kThreshold + headroom * std::tanh(excess / headroom);
    return (sample >= 0.0) ? compressed : -compressed;
}

inline double readNormalizedSample(const uint8_t* ptr, PcmEncoding encoding) {
    switch (encoding) {
        case PcmEncoding::Pcm16Bit: {
            int16_t raw = static_cast<int16_t>(
                static_cast<uint16_t>(ptr[0]) | (static_cast<uint16_t>(ptr[1]) << 8)
            );
            return static_cast<double>(raw) / 32768.0;
        }
        case PcmEncoding::Pcm24Bit: {
            int32_t raw = static_cast<int32_t>(ptr[0]) |
                          (static_cast<int32_t>(ptr[1]) << 8) |
                          (static_cast<int32_t>(static_cast<int8_t>(ptr[2])) << 16);
            return static_cast<double>(raw) / 8388608.0;
        }
        case PcmEncoding::Pcm32Bit: {
            int32_t raw = static_cast<int32_t>(
                static_cast<uint32_t>(ptr[0]) |
                (static_cast<uint32_t>(ptr[1]) << 8) |
                (static_cast<uint32_t>(ptr[2]) << 16) |
                (static_cast<uint32_t>(ptr[3]) << 24)
            );
            return static_cast<double>(raw) / 2147483648.0;
        }
        case PcmEncoding::PcmFloat: {
            float raw = 0.0f;
            std::memcpy(&raw, ptr, sizeof(float));
            return static_cast<double>(raw);
        }
    }
    return 0.0;
}

inline void writeNormalizedSample(uint8_t* ptr, PcmEncoding encoding, double value) {
    const double clamped = std::clamp(value, -1.0, 1.0);
    switch (encoding) {
        case PcmEncoding::Pcm16Bit: {
            const long scaled = std::lround(clamped * 32767.0);
            const int16_t out = static_cast<int16_t>(std::clamp(scaled, -32768L, 32767L));
            ptr[0] = static_cast<uint8_t>(out & 0xFF);
            ptr[1] = static_cast<uint8_t>((out >> 8) & 0xFF);
            break;
        }
        case PcmEncoding::Pcm24Bit: {
            const long scaled = std::lround(clamped * 8388607.0);
            const int32_t out = static_cast<int32_t>(std::clamp(scaled, -8388608L, 8388607L));
            ptr[0] = static_cast<uint8_t>(out & 0xFF);
            ptr[1] = static_cast<uint8_t>((out >> 8) & 0xFF);
            ptr[2] = static_cast<uint8_t>((out >> 16) & 0xFF);
            break;
        }
        case PcmEncoding::Pcm32Bit: {
            const double scaled = std::round(clamped * 2147483647.0);
            const int32_t out = static_cast<int32_t>(
                std::clamp(scaled, -2147483648.0, 2147483647.0)
            );
            ptr[0] = static_cast<uint8_t>(out & 0xFF);
            ptr[1] = static_cast<uint8_t>((out >> 8) & 0xFF);
            ptr[2] = static_cast<uint8_t>((out >> 16) & 0xFF);
            ptr[3] = static_cast<uint8_t>((out >> 24) & 0xFF);
            break;
        }
        case PcmEncoding::PcmFloat: {
            const float out = static_cast<float>(clamped);
            std::memcpy(ptr, &out, sizeof(float));
            break;
        }
    }
}

}  // namespace

AetherDspEngine::AetherDspEngine() {
    bandGainsDb_.fill(0.0f);
    rebuildCoefficientsLocked();
}

void AetherDspEngine::configure(int32_t sampleRateHz, int32_t channelCount, PcmEncoding encoding) {
    std::lock_guard<std::mutex> lock(mutex_);
    sampleRateHz_ = std::max(sampleRateHz, 8000);
    channelCount_ = std::clamp(channelCount, 1, kMaxChannels);
    encoding_ = encoding;
    sideApZ1_ = 0.0;

    for (auto& filter : eqFilters_) {
        filter.resetState();
    }
    bassShelfFilter_.resetState();
    rebuildCoefficientsLocked();
}

void AetherDspEngine::resetState() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& filter : eqFilters_) {
        filter.resetState();
    }
    bassShelfFilter_.resetState();
    sideApZ1_ = 0.0;
}

void AetherDspEngine::updateParameters(
    bool enabled,
    const float* bandGainsDb,
    int32_t bandCount,
    int32_t bassBoostStrength,
    int32_t virtualizerStrength
) {
    std::lock_guard<std::mutex> lock(mutex_);
    dspEnabled_ = enabled;
    const int32_t count = std::min(bandCount, kNumEqBands);
    for (int32_t i = 0; i < count; ++i) {
        bandGainsDb_[i] = std::clamp(bandGainsDb[i], -15.0f, 15.0f);
    }
    bassBoostStrength_ = std::clamp(bassBoostStrength, 0, 1000);
    virtualizerStrength_ = std::clamp(virtualizerStrength, 0, 1000);
    rebuildCoefficientsLocked();
}

bool AetherDspEngine::isProcessingActive() const {
    return processingActive_.load(std::memory_order_acquire);
}

void AetherDspEngine::rebuildCoefficientsLocked() {
    bool anyBandNonZero = false;
    double maxPositiveGain = 0.0;

    for (int i = 0; i < kNumEqBands; ++i) {
        const double gainDb = static_cast<double>(bandGainsDb_[i]);
        if (std::abs(gainDb) > 0.05) {
            anyBandNonZero = true;
            if (gainDb > maxPositiveGain) {
                maxPositiveGain = gainDb;
            }
        }
        configurePeakingBiquad(
            eqFilters_[i],
            kBandFrequenciesHz[i],
            static_cast<double>(sampleRateHz_),
            1.41421356,
            dspEnabled_ ? gainDb : 0.0
        );
    }

    // Bass Boost: 0..1000 -> 0..+9 dB analog low-shelf at 68 Hz
    const double bassGainDb = dspEnabled_ ? (static_cast<double>(bassBoostStrength_) / 1000.0) * 9.0 : 0.0;
    configureLowShelfBiquad(
        bassShelfFilter_,
        68.0,
        static_cast<double>(sampleRateHz_),
        0.75,
        bassGainDb
    );

    // Mid/Side spatial width + flat-magnitude all-pass ambience (no comb-filter notches)
    const double virtNorm = dspEnabled_ ? (static_cast<double>(virtualizerStrength_) / 1000.0) : 0.0;
    stereoWidth_ = 1.0 + (0.45 * virtNorm);
    virtualizerMix_ = 0.12 * virtNorm;

    // Gentle preamp headroom compensation to keep boosts clean and unclipped
    const double totalBoostEstimate = std::max(0.0, maxPositiveGain * 0.35 + bassGainDb * 0.30);
    preampLinear_ = std::pow(10.0, -totalBoostEstimate / 20.0);

    const bool active = dspEnabled_ &&
        (anyBandNonZero || bassBoostStrength_ > 0 || virtualizerStrength_ > 0);
    processingActive_.store(active, std::memory_order_release);
}

void AetherDspEngine::configurePeakingBiquad(
    BiquadFilter& filter,
    double freqHz,
    double sampleRateHz,
    double q,
    double gainDb
) {
    if (std::abs(gainDb) <= 0.05 || freqHz >= sampleRateHz * 0.45) {
        filter.b0 = 1.0;
        filter.b1 = 0.0;
        filter.b2 = 0.0;
        filter.a1 = 0.0;
        filter.a2 = 0.0;
        filter.active = false;
        return;
    }

    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * (freqHz / sampleRateHz);
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);

    const double b0 = 1.0 + alpha * A;
    const double b1 = -2.0 * cosW0;
    const double b2 = 1.0 - alpha * A;
    const double a0 = 1.0 + alpha / A;
    const double a1 = -2.0 * cosW0;
    const double a2 = 1.0 - alpha / A;

    filter.b0 = b0 / a0;
    filter.b1 = b1 / a0;
    filter.b2 = b2 / a0;
    filter.a1 = a1 / a0;
    filter.a2 = a2 / a0;
    filter.active = true;
}

void AetherDspEngine::configureLowShelfBiquad(
    BiquadFilter& filter,
    double freqHz,
    double sampleRateHz,
    double slope,
    double gainDb
) {
    if (std::abs(gainDb) <= 0.05 || freqHz >= sampleRateHz * 0.45) {
        filter.b0 = 1.0;
        filter.b1 = 0.0;
        filter.b2 = 0.0;
        filter.a1 = 0.0;
        filter.a2 = 0.0;
        filter.active = false;
        return;
    }

    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * (freqHz / sampleRateHz);
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double sqrtA = std::sqrt(A);
    const double alpha = (sinW0 / 2.0) * std::sqrt((A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0);

    const double b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha);
    const double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0);
    const double b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha);
    const double a0 = (A + 1.0) + (A - 1.0) * cosW0 + 2.0 * sqrtA * alpha;
    const double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0);
    const double a2 = (A + 1.0) + (A - 1.0) * cosW0 - 2.0 * sqrtA * alpha;

    filter.b0 = b0 / a0;
    filter.b1 = b1 / a0;
    filter.b2 = b2 / a0;
    filter.a1 = a1 / a0;
    filter.a2 = a2 / a0;
    filter.active = true;
}

void AetherDspEngine::processFrames(
    const uint8_t* input,
    uint8_t* output,
    int32_t frameCount
) {
    if (input == nullptr || output == nullptr || frameCount <= 0) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    const int32_t bps = bytesPerSample(encoding_);
    const int32_t frameBytes = bps * channelCount_;

    // True bit-exact bypass: direct memory copy with zero mathematical modification
    if (!processingActive_.load(std::memory_order_relaxed)) {
        std::memcpy(output, input, static_cast<size_t>(frameCount) * static_cast<size_t>(frameBytes));
        return;
    }

    double frameSamples[kMaxChannels]{};
    const bool applyVirtualizer = (channelCount_ >= 2) && (virtualizerStrength_ > 0);
    constexpr double kAllPassG = 0.65;

    for (int32_t frame = 0; frame < frameCount; ++frame) {
        const uint8_t* inFrame = input + static_cast<size_t>(frame) * static_cast<size_t>(frameBytes);
        uint8_t* outFrame = output + static_cast<size_t>(frame) * static_cast<size_t>(frameBytes);

        for (int32_t ch = 0; ch < channelCount_; ++ch) {
            double x = readNormalizedSample(inFrame + static_cast<size_t>(ch) * bps, encoding_) * preampLinear_;

            // 10-band cascaded RBJ peaking EQ
            for (int band = 0; band < kNumEqBands; ++band) {
                x = eqFilters_[band].processSample(x, ch);
            }

            // Low-shelf Bass Boost
            x = bassShelfFilter_.processSample(x, ch);
            frameSamples[ch] = x;
        }

        // Artifact-free Mid/Side Stereo Spatial Virtualizer on front L/R pair
        if (applyVirtualizer) {
            const double left = frameSamples[0];
            const double right = frameSamples[1];
            const double mid = 0.5 * (left + right);
            const double side = 0.5 * (left - right);

            const double apSide = kAllPassG * side + sideApZ1_;
            sideApZ1_ = side - kAllPassG * apSide;

            const double widenedSide = (side * stereoWidth_) + (apSide * virtualizerMix_);
            frameSamples[0] = mid + widenedSide;
            frameSamples[1] = mid - widenedSide;
        }

        for (int32_t ch = 0; ch < channelCount_; ++ch) {
            const double outSample = softLimit(frameSamples[ch]);
            writeNormalizedSample(outFrame + static_cast<size_t>(ch) * bps, encoding_, outSample);
        }
    }
}

}  // namespace aether
