#include <jni.h>
#include "modules/audio_processing/include/audio_processing.h"

#include <array>
#include <cmath>
#include <memory>
#include <new>

namespace {

constexpr int kFrameSamples = 480;
const webrtc::StreamConfig kStream(48000, 1);

struct ApmSession {
    rtc::scoped_refptr<webrtc::AudioProcessing> engine;
    bool echo;
    bool noise;
};

void fail(JNIEnv* env, const char* kind, const char* message) {
    jclass exception = env->FindClass(kind);
    if (exception != nullptr) env->ThrowNew(exception, message);
}

bool initialize(JNIEnv* env, ApmSession* session) {
    rtc::scoped_refptr<webrtc::AudioProcessing> engine = webrtc::AudioProcessingBuilder().Create();
    if (!engine) {
        fail(env, "java/lang/IllegalStateException", "APM creation failed");
        return false;
    }
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = session->echo;
    config.echo_canceller.mobile_mode = false;
    config.echo_canceller.enforce_high_pass_filtering = false;
    config.noise_suppression.enabled = session->noise;
    config.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
    config.gain_controller1.enabled = false;
    config.gain_controller2.enabled = false;
    config.gain_controller2.adaptive_digital.enabled = false;
    config.pre_amplifier.enabled = false;
    config.high_pass_filter.enabled = false;
    engine->ApplyConfig(config);
    webrtc::ProcessingConfig streams;
    streams.input_stream() = kStream;
    streams.output_stream() = kStream;
    streams.reverse_input_stream() = kStream;
    streams.reverse_output_stream() = kStream;
    if (engine->Initialize(streams) != webrtc::AudioProcessing::kNoError) {
        fail(env, "java/lang/IllegalStateException", "APM initialization failed");
        return false;
    }
    session->engine = engine;
    return true;
}

ApmSession* session_from(JNIEnv* env, jlong handle) {
    auto* session = reinterpret_cast<ApmSession*>(handle);
    if (session == nullptr) fail(env, "java/lang/IllegalStateException", "APM is closed");
    return session;
}

bool read_frame(JNIEnv* env, jfloatArray pcm, std::array<float, kFrameSamples>& frame) {
    if (pcm == nullptr || env->GetArrayLength(pcm) != kFrameSamples) {
        fail(env, "java/lang/IllegalArgumentException", "APM requires 480 mono samples");
        return false;
    }
    env->GetFloatArrayRegion(pcm, 0, kFrameSamples, frame.data());
    if (env->ExceptionCheck()) return false;
    for (float sample : frame) {
        if (!std::isfinite(sample) || sample < -1.f || sample > 1.f) {
            fail(env, "java/lang/IllegalArgumentException", "Invalid APM PCM amplitude");
            return false;
        }
    }
    return true;
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ts3mobile_audio_opus_NativeApmProcessor_nativeCreate(
        JNIEnv* env, jclass, jboolean echo, jboolean noise) {
    auto session = std::unique_ptr<ApmSession>(new (std::nothrow) ApmSession{});
    if (!session) {
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate APM session");
        return 0;
    }
    session->echo = echo;
    session->noise = noise;
    if (!initialize(env, session.get())) return 0;
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ts3mobile_audio_opus_NativeApmProcessor_nativeReverse(
        JNIEnv* env, jclass, jlong handle, jfloatArray pcm) {
    auto* session = session_from(env, handle);
    if (session == nullptr) return;
    std::array<float, kFrameSamples> frame{};
    std::array<float, kFrameSamples> output{};
    if (!read_frame(env, pcm, frame)) return;
    const float* input_channels[] = {frame.data()};
    float* output_channels[] = {output.data()};
    if (session->engine->ProcessReverseStream(input_channels, kStream, kStream, output_channels) != webrtc::AudioProcessing::kNoError) {
        fail(env, "java/lang/IllegalStateException", "APM reverse processing failed");
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_github_ts3mobile_audio_opus_NativeApmProcessor_nativeCapture(
        JNIEnv* env, jclass, jlong handle, jfloatArray pcm, jint delay_ms) {
    auto* session = session_from(env, handle);
    if (session == nullptr) return nullptr;
    if (delay_ms < 0 || delay_ms > 500) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid APM delay");
        return nullptr;
    }
    std::array<float, kFrameSamples> frame{};
    std::array<float, kFrameSamples> output{};
    if (!read_frame(env, pcm, frame)) return nullptr;
    const float* input_channels[] = {frame.data()};
    float* output_channels[] = {output.data()};
    if (session->engine->set_stream_delay_ms(delay_ms) != webrtc::AudioProcessing::kNoError ||
        session->engine->ProcessStream(input_channels, kStream, kStream, output_channels) != webrtc::AudioProcessing::kNoError) {
        fail(env, "java/lang/IllegalStateException", "APM capture processing failed");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(kFrameSamples);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, kFrameSamples, output.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ts3mobile_audio_opus_NativeApmProcessor_nativeReset(JNIEnv* env, jclass, jlong handle) {
    auto* session = session_from(env, handle);
    if (session != nullptr) initialize(env, session);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ts3mobile_audio_opus_NativeApmProcessor_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ApmSession*>(handle);
}
