package io.github.ts3mobile.app.service

enum class MicrophoneMode {
    OFF,
    PUSH_TO_TALK,
    CONTINUOUS,
    VOLUME_GATE,
    ;

    val requiresContinuousCapture: Boolean get() = this == CONTINUOUS || this == VOLUME_GATE
}
