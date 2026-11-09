# ADR-0026: subscribed microphone levels and tuning PTT

Date: 2026-11-07. Status: implemented and offline/emulator validated.

Use the existing post-denoise, post-gain and pre-limiter PCM16-saturated RMS
measurement for both the gate and the display. Keep attack, hysteresis, hold,
onset buffering and encoding unchanged. Measure non-gated input only while a
level subscription exists; a subscription never starts AudioRecord or requests
permission. Retain only the latest scalar snapshot, with capture generation,
processing revision and route revision. Invalidate it synchronously on settings,
routing, stop, failure and the last subscription closing. Reject late worker
publications against all three revisions.

Keep audio-opus independent of coroutine/UI dependencies: expose a closeable,
read-only level subscription with a default empty implementation for existing
capture adapters. The worker updates the scalar at most every 100 monotonic
milliseconds without a coroutine per frame. A cold application Flow polls that
subscription every 100 ms while the tuning UI is started, closes it in finally,
and is collected only by the indicator composable. Do not feed levels into the
service's coarse capture state or foreground-notification updates. This bounds
the producer/polling delay to roughly 200 ms plus scheduling; test the 250 ms
presentation target with synthetic emulator input and retain the physical-device
limit in the evidence. No PCM, waveform history or level trace is persisted.

Use the plan's preferred interaction: an explicit PTT control inside the tuning
dialog, labelled as transmitting to the current channel, using the existing
normal-send callback. Opening settings still only edits preferences. Existing
continuous/gated capture has a visible stop action. Release PTT on gesture
cancellation, dialog disposal, window focus loss and Activity pause. Screen-reader
semantics summarize stable capture/gate state without per-frame live announcements.

Large-file assessment: OpusMicrophoneCapture (near300), MicrophoneController
(near300), TeamSpeakService (>600), MainActivity (>300), MainScreen (>300) and
MicrophonePanel retain their lifecycle/transport responsibilities. Put level
snapshot/subscription bookkeeping, application polling, level UI and release
lifecycle effects into separate files. Pipeline/processor only expose the shared
measurement; there is no second gate or recording mode. New defaulted adapter/UI
parameters preserve source callers; compiled binary adapter compatibility is not
promised. No wire, storage, version or dependency/schema migration is required.

Validate synthetic RMS/saturation/gain/gate timing, measurement-off DSP
transparency, bounded publication and subscription lifetime, stale generations,
normal PTT safety, localized large-text layout and aggregate emulator processing
cost. Do not opt into actual microphone recordings in this round.

The level's capture-buffer readiness alone cannot claim application transmission:
the indicator also requires the coarse service send qualification. This preserves
the source-disable barrier during teardown/startup without putting level updates
into the service state. See [A5 evidence](../testing/IMPROVEMENT_A5_2026-11-07_ZH.md)
for the 282/204 JVM checks, full 177 passing Android checks plus one default-skipped
external policy fixture, final 27-case targeted checks and synthetic performance
with real RNNoise/Opus. Physical audio remains outside this round.
