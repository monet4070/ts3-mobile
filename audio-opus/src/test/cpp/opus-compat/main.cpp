#include "fixture_tools.h"

#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>

int main(int count, char** arguments) {
    try {
        if (count != 2) throw std::runtime_error("Use generate or decode");
        const std::string command(arguments[1]);
        if (command == "generate") generate_fixtures();
        else if (command == "decode") decode_packets();
        else throw std::runtime_error("Unknown fixture command");
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
