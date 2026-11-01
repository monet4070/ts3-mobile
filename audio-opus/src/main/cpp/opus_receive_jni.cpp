#include <jni.h>
#include <opus.h>

#include <array>

namespace {

void fail(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_ts3mobile_audio_opus_NativeOpusInfo_nativePacketInfo(JNIEnv* env, jclass, jbyteArray packet) {
    if (packet == nullptr || env->GetArrayLength(packet) < 1 || env->GetArrayLength(packet) > 61440) {
        fail(env, "Invalid Opus packet size");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(packet);
    jbyte* bytes = env->GetByteArrayElements(packet, nullptr);
    if (bytes == nullptr) return nullptr;
    const auto* payload = reinterpret_cast<const unsigned char*>(bytes);
    const unsigned char* frames[48];
    opus_int16 sizes[48];
    unsigned char toc = 0;
    const int parsed = opus_packet_parse(payload, length, &toc, frames, sizes, nullptr);
    const int samples = parsed > 0 ? opus_packet_get_nb_samples(payload, length, 48000) : OPUS_INVALID_PACKET;
    const int fec = parsed > 0 ? opus_packet_has_lbrr(payload, length) : 0;
    env->ReleaseByteArrayElements(packet, bytes, JNI_ABORT);
    if (samples < 120 || samples > 5760 || fec < 0) {
        fail(env, "Invalid Opus packet framing");
        return nullptr;
    }
    const jint result[] = {samples, fec};
    jintArray output = env->NewIntArray(2);
    if (output != nullptr) env->SetIntArrayRegion(output, 0, 2, result);
    return output;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_io_github_ts3mobile_audio_opus_NativeOpusDecoder_nativeDecodeFec(
        JNIEnv* env, jclass, jlong handle, jbyteArray packet, jint frame_samples) {
    if (handle == 0 || packet == nullptr || (frame_samples != 480 && frame_samples != 960)) {
        fail(env, "Invalid Opus FEC request");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(packet);
    if (length < 1 || length > 61440) {
        fail(env, "Invalid Opus FEC packet size");
        return nullptr;
    }
    jbyte* bytes = env->GetByteArrayElements(packet, nullptr);
    if (bytes == nullptr) return nullptr;
    std::array<opus_int16, 960> pcm{};
    const int decoded = opus_decode(reinterpret_cast<OpusDecoder*>(handle),
        reinterpret_cast<const unsigned char*>(bytes), length, pcm.data(), frame_samples, 1);
    env->ReleaseByteArrayElements(packet, bytes, JNI_ABORT);
    if (decoded < 0) {
        fail(env, "Opus FEC decode failed");
        return nullptr;
    }
    jshortArray output = env->NewShortArray(decoded);
    if (output != nullptr) env->SetShortArrayRegion(output, 0, decoded, pcm.data());
    return output;
}
