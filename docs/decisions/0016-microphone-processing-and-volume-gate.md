# ADR-0016: microphone gain, processing controls and volume gate

Date: 2026-10-23. Status: accepted for offline implementation.

## Context and scope

The user requests microphone volume, noise suppression, echo cancellation and a
volume gate. Capture already uses RNNoise v0.2 and attempts Android AEC, but
there are no controls or truthful effect status. The protocol treats readiness
as a talkspurt, not queue occupancy (ADR-0011).

## Decision

Keep Android capture/effects in audio-opus and service/permissions/UI in app.
Add pure Kotlin gain, RMS gate, bounded onset selection and transport buffer
with independent tests. No protocol facade or wire-format change is required.

The signal order is VOICE_COMMUNICATION/platform AEC, optional RNNoise in 10 ms
blocks, digital gain with PCM16 saturation, 20 ms RMS gate, Opus encoding.
Volume is 0–200%, default 100%. Gate opening threshold is -60 to -15 dBFS,
default -40 dBFS, measured after suppression and gain. Opening requires two
consecutive 20 ms blocks (40 ms); closing uses 6 dB hysteresis and 300 ms hold.
The most recent six blocks (120 ms, including attack) form a bounded onset
buffer. These are initial offline defaults, not an acoustic calibration.

A capture owns its queue, counters and callbacks. Queue gaps while a gate is
open do not end speech. Closing drains bounded tail frames before ending the
talkspurt. The encoded queue holds at most nine frames (180 ms including onset
backlog). Stop/configuration changes discard stale frames; a retired worker
cannot enqueue into or notify a new capture. OFF immediately stops capture and
clears audio. Silence still captures locally in gate mode; foreground microphone
type follows capture, not transmission. Already-stopped capture cannot restart
in the background. Activation mode is not persisted across service recreation.

Settings are in service memory, like the existing microphone mode, and survive
connection changes in that service. Service recreation restores defaults and
push-to-talk; there is no new disk schema or preference migration. The settings
screen explicitly states this lifetime. No recording, audio files, PCM logs or
new permissions/dependencies are added.

RNNoise defaults on; disabling bypasses it. AEC defaults on when available, and
UI reports pending/active/disabled/unavailable/configuration failure from the
actual effect result. Android AGC and platform NS are explicitly disabled when
controllable to avoid stacked processing. VOICE_COMMUNICATION/OEM processing
may still vary. AEC support is not proof of effective acoustic cancellation.
Effects update in the existing capture session, without background restarts.

## Decomposition assessment before editing

OpusMicrophoneCapture is near 300 lines; gain/gate/onset/queue ownership will be
extracted now, retaining only capture lifecycle and loop glue. MainActivity,
TeamSpeakService and ConnectionCoordinator already exceed 300 lines; this task
adds narrow callbacks/state preservation only. Their existing extraction plan
in ARCHITECTURE.md remains valid. Settings UI is a separate file, avoiding growth
of MainScreen/MicrophonePanel. Further lifecycle extraction should preserve the
session ownership and immediate stop semantics established here.

## Validation and remaining acceptance

Use deterministic PCM/gate/buffer tests, permission/background policy tests,
synthetic native RNNoise/Opus tests and Compose controls/layout tests. Run lint,
formatting, JVM tests, Debug/Release builds and the minified identity probe.
No real server or physical microphone is used for this task. Real room noise,
voice intelligibility, clipping audibility, speaker echo and wired/Bluetooth/USB
AEC effectiveness remain device acceptance work.
