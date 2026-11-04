#include <jni.h>
#include <rnnoise.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <string>

namespace {

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

DenoiseState* denoiser_from_handle(JNIEnv* env, jlong handle) {
    auto* denoiser = reinterpret_cast<DenoiseState*>(handle);
    if (denoiser == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "RNNoise processor is closed");
    }
    return denoiser;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ts3mobile_audio_opus_NativeRnNoiseProcessor_nativeCreate(
        JNIEnv* env,
        jclass) {
    if (rnnoise_get_frame_size() != 480) {
        throw_java(env, "java/lang/IllegalStateException", "Unexpected RNNoise frame size");
        return 0;
    }
    DenoiseState* denoiser = rnnoise_create(nullptr);
    if (denoiser == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "Unable to create RNNoise processor");
        return 0;
    }
    return reinterpret_cast<jlong>(denoiser);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_io_github_ts3mobile_audio_opus_NativeRnNoiseProcessor_nativeProcessInPlace(
        JNIEnv* env,
        jclass,
        jlong handle,
        jshortArray pcm) {
    DenoiseState* denoiser = denoiser_from_handle(env, handle);
    if (denoiser == nullptr) return 0;
    if (pcm == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "PCM input is null");
        return 0;
    }

    const int frame_size = rnnoise_get_frame_size();
    const jsize sample_count = env->GetArrayLength(pcm);
    if (frame_size != 480 || sample_count <= 0 || sample_count % frame_size != 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "PCM must contain complete RNNoise frames");
        return 0;
    }

    jshort* samples = env->GetShortArrayElements(pcm, nullptr);
    if (samples == nullptr) return 0;

    std::array<float, 480> input{};
    std::array<float, 480> output{};
    float vad_total = 0;
    for (jsize offset = 0; offset < sample_count; offset += frame_size) {
        for (int index = 0; index < frame_size; ++index) {
            input[static_cast<size_t>(index)] = samples[offset + index];
        }
        vad_total += rnnoise_process_frame(denoiser, output.data(), input.data());
        for (int index = 0; index < frame_size; ++index) {
            const long rounded = std::lrint(output[static_cast<size_t>(index)]);
            samples[offset + index] = static_cast<jshort>(
                    std::clamp(rounded, -32768L, 32767L));
        }
    }

    env->ReleaseShortArrayElements(pcm, samples, 0);
    return vad_total / static_cast<float>(sample_count / frame_size);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ts3mobile_audio_opus_NativeRnNoiseProcessor_nativeDestroy(
        JNIEnv*,
        jclass,
        jlong handle) {
    rnnoise_destroy(reinterpret_cast<DenoiseState*>(handle));
}
