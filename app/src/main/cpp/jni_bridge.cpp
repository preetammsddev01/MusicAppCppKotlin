#include <jni.h>
#include <cstdint>
#include "aether_dsp.h"

namespace {

inline aether::AetherDspEngine* fromHandle(jlong handle) {
    return reinterpret_cast<aether::AetherDspEngine*>(handle);
}

inline aether::PcmEncoding toEncoding(jint encodingType) {
    switch (encodingType) {
        case 1:
            return aether::PcmEncoding::Pcm24Bit;
        case 2:
            return aether::PcmEncoding::Pcm32Bit;
        case 3:
            return aether::PcmEncoding::PcmFloat;
        case 0:
        default:
            return aether::PcmEncoding::Pcm16Bit;
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_audio_NativeDspBridge_nativeCreate(JNIEnv*, jobject) {
    auto* engine = new aether::AetherDspEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeDspBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    delete engine;
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeDspBridge_nativeConfigure(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sampleRateHz,
    jint channelCount,
    jint encodingType
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->configure(sampleRateHz, channelCount, toEncoding(encodingType));
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeDspBridge_nativeResetState(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return;
    engine->resetState();
}

JNIEXPORT void JNICALL
Java_com_example_audio_NativeDspBridge_nativeUpdateParameters(
    JNIEnv* env,
    jobject,
    jlong handle,
    jboolean enabled,
    jfloatArray bandGainsDb,
    jint bassBoostStrength,
    jint virtualizerStrength
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || bandGainsDb == nullptr) return;

    const jsize len = env->GetArrayLength(bandGainsDb);
    jfloat* gains = env->GetFloatArrayElements(bandGainsDb, nullptr);
    if (gains != nullptr) {
        engine->updateParameters(
            enabled == JNI_TRUE,
            gains,
            len,
            bassBoostStrength,
            virtualizerStrength
        );
        env->ReleaseFloatArrayElements(bandGainsDb, gains, JNI_ABORT);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeDspBridge_nativeIsProcessingActive(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr) return JNI_FALSE;
    return engine->isProcessingActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_audio_NativeDspBridge_nativeProcessDirectBuffer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject inputBuffer,
    jint inputOffsetBytes,
    jobject outputBuffer,
    jint frameCount
) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || inputBuffer == nullptr || outputBuffer == nullptr || frameCount <= 0) {
        return JNI_FALSE;
    }

    auto* rawIn = static_cast<const uint8_t*>(env->GetDirectBufferAddress(inputBuffer));
    auto* rawOut = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    if (rawIn == nullptr || rawOut == nullptr) {
        return JNI_FALSE;
    }

    engine->processFrames(rawIn + inputOffsetBytes, rawOut, frameCount);
    return JNI_TRUE;
}

}  // extern "C"
