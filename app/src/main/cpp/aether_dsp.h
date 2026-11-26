#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

namespace aether {

constexpr int kNumEqBands = 10;
constexpr int kMaxChannels = 8;
constexpr double kBandFrequenciesHz[kNumEqBands] = {
    31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
};

enum class PcmEncoding : int32_t {
    Pcm16Bit = 0,
    Pcm24Bit = 1,
    Pcm32Bit = 2,
    PcmFloat = 3
};

struct BiquadFilter {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
    bool active = false;

    double z1[kMaxChannels]{};
    double z2[kMaxChannels]{};

    void resetState() {
        for (int ch = 0; ch < kMaxChannels; ++ch) {
            z1[ch] = 0.0;
            z2[ch] = 0.0;
        }
    }

    inline double processSample(double x, int ch) {
        if (!active) return x;
        const double y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
};

class AetherDspEngine {
public:
    AetherDspEngine();

    void configure(int32_t sampleRateHz, int32_t channelCount, PcmEncoding encoding);
    void resetState();
    void updateParameters(
        bool enabled,
        const float* bandGainsDb,
        int32_t bandCount,
        int32_t bassBoostStrength,
        int32_t virtualizerStrength
    );

    bool isProcessingActive() const;

    void processFrames(
        const uint8_t* input,
        uint8_t* output,
        int32_t frameCount
    );

private:
    void rebuildCoefficientsLocked();
    static void configurePeakingBiquad(
        BiquadFilter& filter,
        double freqHz,
        double sampleRateHz,
        double q,
        double gainDb
    );
    static void configureLowShelfBiquad(
        BiquadFilter& filter,
        double freqHz,
        double sampleRateHz,
        double slope,
        double gainDb
    );

    mutable std::mutex mutex_;

    int32_t sampleRateHz_ = 44100;
    int32_t channelCount_ = 2;
    PcmEncoding encoding_ = PcmEncoding::Pcm16Bit;

    bool dspEnabled_ = true;
    std::array<float, kNumEqBands> bandGainsDb_{};
    int32_t bassBoostStrength_ = 0;
    int32_t virtualizerStrength_ = 0;
    std::atomic<bool> processingActive_{false};

    double preampLinear_ = 1.0;
    std::array<BiquadFilter, kNumEqBands> eqFilters_{};
    BiquadFilter bassShelfFilter_{};

    // All-pass phase decorrelator state for artifact-free M/S spatial width
    double sideApZ1_ = 0.0;
    double stereoWidth_ = 1.0;
    double virtualizerMix_ = 0.0;
};

}  // namespace aether
