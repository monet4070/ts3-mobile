# ADR 0035: bounded offline receive/playback diagnostics

Date: 2026-11-09. Status: accepted for the local development candidate; not a published release.

## Context

A connected cellular listening session can stutter without a connection failure.
The existing report/v2 `voiceFramesDropped` counts rejected session callbacks,
not network loss or audio output drops. The receive pipeline already counted
stale/late media, concealment, rebuffers and output failures, but only printed
its totals on close. A killed process or overwritten logcat buffer lost that evidence.
USB charging, wake state and the current network also need not match field conditions.

## Decision

Keep `session` and `history` in report/v2 and add an optional `audio` section with
schema `ts3-mobile-audio/v1`. Expose fixed numeric/enumerated snapshots from
`audio-opus`. Do not retune jitter, queues, retries, routing or audio focus.

The player provides per-generation cumulative counters and interval peaks.
A service collaborator targets a five-second sample cadence and records fixed
route/focus/network events. Actual route kinds, output underruns, input/PCM
clears, scheduling gaps and write duration complement existing receive statistics.
A measurement failure is counted and cannot prevent AudioTrack retirement.

An application-owned repository appends small checkpoints on IO, coalesces bursts
and limits checkpoint frequency to at most once per second. It keeps the last three
audio playback generations, up to 120 samples and 64 events per generation.
Of the 64 event slots, 16 are reserved for user marks and 48 for ordinary events,
so output/route churn cannot immediately evict an interruption mark. The same
reservation applies when reopening the append log.
At the normal cadence, 120 samples are approximately ten minutes. Initial/final
samples and explicit shutdown snapshots can shorten that time span; actual
window durations and timestamps are exported. Cumulative counters in the newest
sample still cover the generation, even if early samples have rotated out.
The log is capped at 1 MiB and atomically compacted on recovery or rotation;
normal five-second writes append instead of rewriting the entire retained history.

Storage uses only fixed enum names, booleans and validated numbers. The repository
survives service replacement within the process; an old worker's final callback
can update only its own archive session. Normal worker shutdown seals an immutable final
snapshot, which remains available if polling beats the shutdown callback. Reopening after an abrupt exit marks a previously open generation as
`PREVIOUS_RUN_INTERRUPTED`; its recorded end is the last observation, not an
asserted kill time. Sampling delays and failed/queued writes can extend the missing
tail beyond five seconds. Export includes window lengths, last persistence time,
write failures and invalid-record counts rather than promising lossless telemetry.

The diagnostic page can mark an interruption, copy a report or save UTF-8 JSON
through Android's document picker. A historical session can be exported without
connecting again. File writes/report formatting run away from the UI/audio threads. Saving prepares
one already-redacted report before opening the picker: Activity.onStop can unbind
the service, so the result callback must not depend on a live binder. A bounded
512,000-byte pending file in noBackupFilesDir survives rotation/process recreation,
is removed after success/cancellation, and can remain after an interrupted flow
until replaced by the next export. The callback never stores persistent URI access.
No storage/installer/network permission or runtime dependency is added.

## Interpretation and compatibility

- `counterKeys` and `peakKeys` map the corresponding numeric arrays in every sample.
  This bounds clipboard transactions; samples do not repeat dozens of metric names.
- Counters are cumulative in one audio generation. Compare adjacent samples with
  the same archive session; do not subtract across sessions or process restarts.
- Peaks cover the interval since the preceding collection and use the units in
  their names (`NS`, samples or packets). Wall-clock and elapsed-realtime values
  help identify clock changes and delayed sampling.
- Arrival gaps include natural sender silence. `PLC`, FEC, sequence skips, stale
  frames and queue/PCM clears are not a measured UDP packet-loss percentage.
- `PCM_DROPPED` is FIFO overflow; `PCM_RESET_DROPPED` is buffered PCM discarded
  during reset. Output samples mean accepted writes, not proof of audible delivery.
- Network transport and route values are coarse kinds. Device names/IDs, endpoints,
  participant IDs, packet IDs, payloads, identities and exception text are never stored.
- A mark is the time the user taps, including after disconnect; it does not assert
  the precise onset of a previously heard glitch. Event history is bounded too.
- Existing JSON fields and audio public constructors remain. The added observation
  APIs are source compatible; all local modules are rebuilt together. Old reports
  remain interpretable, and old builds do not acquire this ability retroactively.

## Validation

Independent JVM regressions cover counter/peak semantics, callback lock ownership,
partial/zero writes, bounded retention, append/compaction, corrupted tails, failed
storage and retiring-service races. Offline Android tests cover the native worker
with a fake sink, archive reopening, production service wiring, combined JSON and
UI actions/large text. Live cellular listening remains a separate field test;
telemetry alone does not prove that the earlier audio regression is fixed.
