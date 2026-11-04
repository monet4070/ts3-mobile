# ADR-0018: staged audio quality upgrade

Date: 2026-10-24. Status: accepted for staged implementation; A0/A1 and A2
offline implementations validated, A3 opt-in prototype added, A4a codec upgrade
validated offline; A4b receive scheduling added. Live APM integration and physical
acoustic acceptance remain pending.

## Context

The user requests an audio improvement design after reviewing the current
AudioRecord/RNNoise/Opus and jitter-buffer/AudioTrack path. Current code can flush
written but unplayed PCM on temporary starvation; manual gain and mixing use
hard saturation. Historical device measurements do not establish the cause of
the reported Bluetooth sound quality or validate a replacement DSP stack.

## Decision

Keep TS3 transport, Opus framing, Android audio I/O, public entry points and the
existing microphone/foreground-service rules. Implement and validate in small,
independently reversible stages: baseline fixtures, continuous playback and
bounded drain, smooth gain/limiting, an optional WebRTC APM prototype, a pinned
Opus upgrade, bounded adaptive jitter/FEC, then route-specific device acceptance.

The Chinese [design and execution plan](../plans/AUDIO_QUALITY_UPGRADE_2026-10-24_ZH.md)
is the single source for proposed parameters, phase gates and test coverage.
Its numeric targets are provisional, not measured results.

Natural playback completion waits for accepted samples to be played within a
deadline. Explicit mute, focus loss, disconnect and output replacement discard
old audio immediately. Session/output generations reject stale writes and AEC
references. No receive end-marker semantics are invented without pinned ts3j
source and fixture evidence.

APM remains an experiment until final rendered PCM and capture/render timing
are reliable. Use one controllable AEC and one software suppression path, with
route-specific fallback to the current platform-AEC/RNNoise implementation.
Adaptive AGC2 starts disabled: automatic gain must not redefine the existing
post-suppression/manual-gain volume-gate threshold or amplify silence into speech.

Audio processing, reference queues and output clocks belong to audio-opus;
app owns lifecycle, settings and truthful status. ts3-protocol remains JVM-only.
Extract playback output control before changing its 492-line owner; the broader
connection coordinator refactor is independent. Do not introduce global DSP state.

Opus 1.6.1, [released upstream on 2026-01-14](https://opus-codec.org/release/stable/2026/01/14/libopus-1_6_1.html),
is the initial upgrade candidate, subject to reproducible native builds,
licenses, existing ABI support and old/new codec fixture checks. Preserve codec
parameters first. Oboe, DeepFilterNet, neural PLC, DRED and bandwidth extension
remain later experiments with separate evidence requirements.

Keep the existing audio-boundary monotonic arrival timestamp and mono output
for both OPUS_VOICE and OPUS_MUSIC. A shared Opus decoder can downmix stereo
packets; explicit music/stereo fixtures are a coverage requirement, not evidence
that the current path is inherently invalid. Baseline fingerprints include all
Gradle lockfiles and actual vendored RNNoise source/model checksums alongside
VERSION.md provenance.

Move capture-source baseline design and existing-sample analysis into A0; source
device comparisons may run independently in A0/A1 when separately authorized.
Synthetic/offline processing cannot establish OEM capture-source behavior.
Define short-block tail/zero-padding and normalization tests explicitly. Bound
global input residence age as well as packet count, rejecting stale backlog on
recovery rather than treating the 256-packet memory guard as a latency budget.

## Consequences and validation

The initial design was documentation-only. A0/A1 implementation now extracts the
playback output/timeline, retains short-gap state and drains accepted tail samples;
explicit stop/mute/focus loss and route changes still discard immediately.
See the [execution record](../testing/AUDIO_A0_A1_2026-10-24.md) for evidence and
remaining baseline/device checks. Capture defaults, persistent settings, permissions
and dependencies are unchanged; no microphones or servers are used for validation.
The implementation requires deterministic JVM/fake-output tests, synthetic native
audio tests, Android focus/lifecycle checks, release packaging checks and later
physical-route/acoustic/power acceptance. Offline checks cannot prove real AEC,
Bluetooth bandwidth or perceived quality.

A2 extracts session-owned float gain envelopes and a peak limiter. Non-muted gain
changes ramp for 20 ms; mute is immediate. Use fixed unity summation with bus
limiting to avoid talker-count normalization steps. Multiple quiet voices sum more
loudly than the old square-root normalization. The initial limiter has zero
lookahead, immediate attack, 5 ms peak hold and 80 ms exponential release with a
-1 dBFS ceiling. It has no retained PCM or tail to flush. Gate RMS branches before
limiting using equivalent saturated PCM16 levels; settings revisions discard all
old audio while retaining only the scalar gain envelope position. These parameters
remain experimental until source calibration and acoustic tests.
See the [A2 execution record](../testing/AUDIO_A2_2026-10-24.md).

A3 uses the standalone freedesktop APM 1.3 source archive rather than a complete
WebRTC SDK. Pin its source and Abseil/Meson inputs by SHA-256, verify extracted
sources and link four Android ABI builds through an explicit development property.
Default packages do not include the experiment library. Keep source notices
beside optional artifacts. The old snapshot is an experimental build/API baseline,
not a claim of current upstream coverage or production readiness.
An opt-in reference sink receives only accepted final PCM and invalidates old
generations on flush/gaps. A single worker consumes at most five 10 ms reference
blocks, estimates device queue delay from sample clocks, and restores an explicitly
provided baseline on sustained failure. No automatic microphone start or experimental
reenable occurs. Route policy refuses Bluetooth/unknown routes and requires source
calibration and disabled controllable platform effects. APM NS replaces RNNoise
within the experiment, with both AGCs off. Live capture wiring and system-volume/
timestamp/effect verification remain device work; synthetic acceptance does not
enable APM in the service. See [A3 execution record](../testing/AUDIO_A3_2026-10-24.md).

Keep stage-specific rollback. An APM failure can restore the existing processing
path with explicit status and existing capture-start policy. Codec rollback is a
rebuild against the previous dependency, not a hidden runtime exception fallback.

A4a replaces Prefab Opus 1.3.1 with the official 1.6.1 archive under a fixed
SHA-256. Use upstream CMake without algorithm patches, one static codec per JNI
library, float/hardening builds and explicit disabled DRED/deep PLC/OSCE. Retain
the existing encoder controls to isolate version effects. A local archive override
changes only the download location and still requires the same hash. Keep codec
optimization enabled in Debug and 16 KiB LOAD alignment on all four ABIs.
The old/new host matrix and shared Android mono/stereo/music/DTX/duration corpus
provide compatibility evidence; real-server end-marker behavior and physical
device audio/power remain separate acceptance. An independent minified-APK probe
checks actual packaged JNI loading and codec lifetimes. See the
[A4a execution record](../testing/AUDIO_A4A_2026-10-24.md).

A4b shares per-client media leases across input, pending and decoded stages and
enforces 60 ms global input residence before decode. Initial limits are 240 ms
per client, 256 global input packets, 512 KiB encoded reservations and 64 tracked
clients/decoders. Old-generation resets retain still-fresh input and original
timestamps; intentional drops are not expanded through PLC. Startup target begins
at 60 ms and adapts within 40–120 ms, with bounded rebuffering only at explicit
empty-PCM gaps. Keep fixed 60 ms as an internal comparison/rebuild option.
The decoder port now exposes validated duration, LBRR and FEC. Missing media
duration cannot be proven from TS3 sequence IDs alone, so default TS3 input does
not guess it from adjacent packets. Conditional FEC requires explicitly confirmed
framing and actual redundancy, validated with paired native fixtures and PLC
comparisons. Sender settings and public protocol types stay unchanged.
See [A4b execution record](../testing/AUDIO_A4B_2026-10-24.md).

The [review repair plan](../plans/AUDIO_REVIEW_FIXES_2026-10-24_ZH.md) keeps these
codec/transport choices while adding bounded output recovery, serialized capture
teardown and input/output acoustic-route invalidation. FEC exceptions reset the
decoder before consuming the next real packet; they never run PLC against an
unknown mutated state. Native calls and close share an instance monitor. The
experimental APM remains excluded from default APKs.
