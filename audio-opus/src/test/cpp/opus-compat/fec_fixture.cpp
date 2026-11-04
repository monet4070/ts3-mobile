#include <opus.h>

#include <cmath>
#include <iomanip>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

void check(int result) {
    if (result != OPUS_OK) throw std::runtime_error(opus_strerror(result));
}

void generate(bool fec) {
    int error = OPUS_OK;
    std::unique_ptr<OpusEncoder, decltype(&opus_encoder_destroy)> encoder(
        opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error), opus_encoder_destroy);
    check(error);
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_BITRATE(24000)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_VBR(1)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_VBR_CONSTRAINT(1)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_COMPLEXITY(10)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_INBAND_FEC(fec ? 2 : 0)));
    check(opus_encoder_ctl(encoder.get(), OPUS_SET_PACKET_LOSS_PERC(fec ? 30 : 0)));
    std::cout << "{\"fecEnabled\":" << (fec ? "true" : "false") << ",\"packets\":[";
    int redundant = 0;
    for (int frame = 0; frame < 160; ++frame) {
        opus_int16 pcm[960];
        const double fundamental = 180 + (frame % 9) * 23;
        for (int index = 0; index < 960; ++index) {
            const double seconds = (frame * 960 + index) / 48000.0;
            const double envelope = 0.6 + 0.4 * std::sin(2 * std::acos(-1.0) * 3 * seconds);
            pcm[index] = static_cast<opus_int16>(envelope * (7000 * std::sin(2 * std::acos(-1.0) * fundamental * seconds) +
                3000 * std::sin(2 * std::acos(-1.0) * fundamental * 2 * seconds)));
        }
        unsigned char packet[1275];
        const int bytes = opus_encode(encoder.get(), pcm, 960, packet, sizeof(packet));
        if (bytes < 1) throw std::runtime_error("FEC fixture encode failed");
        const int has_fec = opus_packet_has_lbrr(packet, bytes);
        if (has_fec < 0) throw std::runtime_error("FEC fixture inspection failed");
        if (has_fec) redundant++;
        if (frame > 0) std::cout << ',';
        std::cout << "{\"lbrr\":" << (has_fec ? "true" : "false") << ",\"hex\":\"";
        for (int index = 0; index < bytes; ++index) {
            std::cout << std::hex << std::setw(2) << std::setfill('0') << static_cast<unsigned int>(packet[index]);
        }
        std::cout << std::dec << "\"}";
    }
    if (fec && redundant < 10) throw std::runtime_error("No reliable FEC was produced");
    if (!fec && redundant != 0) throw std::runtime_error("Unexpected FEC in control fixture");
    std::cout << "],\"redundantPackets\":" << redundant << '}';
}

int main() {
    try {
        std::cout << "{\"producer\":\"" << opus_get_version_string() << "\",\"samples\":960,\"sampleRate\":48000,\"cases\":[";
        generate(false);
        std::cout << ',';
        generate(true);
        std::cout << "]}\n";
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
