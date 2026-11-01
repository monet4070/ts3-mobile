#include "fixture_tools.h"
#include <opus.h>

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Scenario {
    const char* name;
    int channels;
    int samples;
    bool music;
    bool dtx;
    int left_hz;
    int right_hz;
    bool repacketized;
};

void check_control(int result) {
    if (result != OPUS_OK) throw std::runtime_error(opus_strerror(result));
}

std::string hex(const unsigned char* bytes, int count) {
    std::ostringstream output;
    for (int index = 0; index < count; ++index) {
        output << std::hex << std::setw(2) << std::setfill('0') << static_cast<unsigned int>(bytes[index]);
    }
    return output.str();
}

void write_scenario(const Scenario& scenario) {
    int error = OPUS_OK;
    const int application = scenario.music ? OPUS_APPLICATION_AUDIO : OPUS_APPLICATION_VOIP;
    std::unique_ptr<OpusEncoder, decltype(&opus_encoder_destroy)> encoder(
        opus_encoder_create(48000, scenario.channels, application, &error), opus_encoder_destroy);
    check_control(error);
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_BITRATE(64000)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_VBR(1)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_VBR_CONSTRAINT(1)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_COMPLEXITY(10)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_SIGNAL(scenario.music ? OPUS_SIGNAL_MUSIC : OPUS_SIGNAL_VOICE)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_LSB_DEPTH(16)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_DTX(scenario.dtx ? 1 : 0)));
    check_control(opus_encoder_ctl(encoder.get(), OPUS_SET_FORCE_CHANNELS(scenario.channels)));
    std::cout << "{\"name\":\"" << scenario.name << "\",\"channels\":" << scenario.channels
              << ",\"codec\":\"" << (scenario.music ? "OPUS_MUSIC" : "OPUS_VOICE")
              << "\",\"samples\":" << scenario.samples << ",\"leftHz\":" << scenario.left_hz
              << ",\"rightHz\":" << scenario.right_hz << ",\"packets\":[";
    const int packet_count = scenario.dtx ? 100 : 50;
    int dtx_packets = 0;
    for (int packet_index = 0; packet_index < packet_count; ++packet_index) {
        const int base_samples = scenario.repacketized ? 960 : scenario.samples;
        std::vector<opus_int16> pcm(static_cast<size_t>(base_samples * scenario.channels));
        for (int index = 0; index < base_samples; ++index) {
            for (int channel = 0; channel < scenario.channels; ++channel) {
                const int frequency = channel == 0 ? scenario.left_hz : scenario.right_hz;
                const double phase = 2.0 * std::acos(-1.0) * frequency * (packet_index * base_samples + index) / 48000;
                pcm[static_cast<size_t>(index * scenario.channels + channel)] =
                    scenario.dtx ? 0 : static_cast<opus_int16>(8000 * std::sin(phase));
            }
        }
        unsigned char encoded[7680];
        int bytes = opus_encode(encoder.get(), pcm.data(), base_samples, encoded, 1275);
        if (bytes <= 0) throw std::runtime_error(opus_strerror(bytes));
        if (scenario.repacketized) {
            std::unique_ptr<OpusRepacketizer, decltype(&opus_repacketizer_destroy)> repacketizer(
                opus_repacketizer_create(), opus_repacketizer_destroy);
            for (int frame = 0; frame < 6; ++frame) check_control(opus_repacketizer_cat(repacketizer.get(), encoded, bytes));
            unsigned char combined[7680];
            bytes = opus_repacketizer_out(repacketizer.get(), combined, sizeof(combined));
            if (bytes <= 0) throw std::runtime_error(opus_strerror(bytes));
            std::copy(combined, combined + bytes, encoded);
        }
        int in_dtx = 0;
        check_control(opus_encoder_ctl(encoder.get(), OPUS_GET_IN_DTX(&in_dtx)));
        if (in_dtx) dtx_packets++;
        if (opus_packet_get_nb_samples(encoded, bytes, 48000) != scenario.samples) throw std::runtime_error("Fixture duration mismatch");
        if (packet_index > 0) std::cout << ',';
        std::cout << '"' << hex(encoded, bytes) << '"';
    }
    if (scenario.dtx && dtx_packets == 0) throw std::runtime_error("DTX was not reached");
    std::cout << "],\"dtxPackets\":" << dtx_packets << '}';
}

}

void generate_fixtures() {
    const Scenario scenarios[] = {
        {"voice-mono-20", 1, 960, false, false, 1000, 0, false},
        {"music-mono-20", 1, 960, true, false, 1000, 0, false},
        {"music-stereo-both-20", 2, 960, true, false, 1000, 2000, false},
        {"music-stereo-left-20", 2, 960, true, false, 1000, 0, false},
        {"music-stereo-right-20", 2, 960, true, false, 0, 2000, false},
        {"voice-dtx-20", 1, 960, false, true, 0, 0, false},
        {"music-mono-2.5", 1, 120, true, false, 1000, 0, false},
        {"music-mono-5", 1, 240, true, false, 1000, 0, false},
        {"music-mono-10", 1, 480, true, false, 1000, 0, false},
        {"voice-mono-40", 1, 1920, false, false, 1000, 0, false},
        {"voice-mono-60", 1, 2880, false, false, 1000, 0, false},
        {"music-mono-120", 1, 5760, true, false, 1000, 0, true},
    };
    std::cout << "{\"schema\":1,\"sampleRate\":48000,\"producer\":\"" << opus_get_version_string() << "\",\"cases\":[";
    bool first = true;
    for (const auto& scenario : scenarios) {
        if (!first) std::cout << ',';
        first = false;
        write_scenario(scenario);
    }
    std::cout << "]}\n";
}
