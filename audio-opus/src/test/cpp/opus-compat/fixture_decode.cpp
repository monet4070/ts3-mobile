#include "fixture_tools.h"
#include <opus.h>

#include <algorithm>
#include <cmath>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

void decode_packets() {
    int error = OPUS_OK;
    std::unique_ptr<OpusDecoder, decltype(&opus_decoder_destroy)> decoder(
        opus_decoder_create(48000, 1, &error), opus_decoder_destroy);
    if (error != OPUS_OK) throw std::runtime_error(opus_strerror(error));
    std::string line;
    long long samples = 0;
    int packets = 0;
    int channel_mask = 0;
    int measured = 0;
    int peak = 0;
    double energy = 0;
    double real[2] = {};
    double imaginary[2] = {};
    while (std::getline(std::cin, line)) {
        if (line.empty() || line.size() % 2 != 0 || line.size() > 15360) throw std::runtime_error("Invalid fixture packet text");
        std::vector<unsigned char> packet;
        for (size_t index = 0; index < line.size(); index += 2) {
            packet.push_back(static_cast<unsigned char>(std::stoul(line.substr(index, 2), nullptr, 16)));
        }
        opus_int16 pcm[5760];
        const int count = opus_decode(decoder.get(), packet.data(), static_cast<int>(packet.size()), pcm, 5760, 0);
        if (count < 0) throw std::runtime_error(opus_strerror(count));
        if (count != opus_packet_get_nb_samples(packet.data(), static_cast<int>(packet.size()), 48000)) {
            throw std::runtime_error("Decoded duration disagrees with packet");
        }
        channel_mask |= 1 << (opus_packet_get_nb_channels(packet.data()) - 1);
        packets++;
        for (int index = 0; index < count; ++index) {
            if (samples + index < 4800) continue;
            const double sample = pcm[index];
            energy += sample * sample;
            peak = std::max(peak, std::abs(static_cast<int>(pcm[index])));
            for (int tone = 0; tone < 2; ++tone) {
                const double phase = 2.0 * std::acos(-1.0) * (tone + 1) * 1000 * (samples + index) / 48000;
                real[tone] += sample * std::cos(phase);
                imaginary[tone] += sample * std::sin(phase);
            }
            measured++;
        }
        samples += count;
    }
    if (packets == 0 || measured == 0) throw std::runtime_error("No fixture samples");
    std::cout << "{\"version\":\"" << opus_get_version_string() << "\",\"packets\":" << packets
              << ",\"samples\":" << samples << ",\"channelMask\":" << channel_mask
              << ",\"rms\":" << std::sqrt(energy / measured) << ",\"peak\":" << peak
              << ",\"tone1000\":" << 2 * std::hypot(real[0], imaginary[0]) / measured
              << ",\"tone2000\":" << 2 * std::hypot(real[1], imaginary[1]) / measured << "}\n";
}
