# Architecture

TS3 Mobile is a single-server Android client. It keeps the TeamSpeak session in a
foreground service, exposes immutable state to Compose, and isolates protocol
and audio implementations behind small interfaces.

## Modules and boundaries

```text
Compose UI / MainActivity
        │  intents and immutable StateFlow
        ▼
TeamSpeakService (Android application layer)
        ├── Ts3SessionClient (protocol port)
        ├── OpusAudioPlayer / OpusMicrophoneCapture (audio ports)
        └── IdentityVault (local encrypted storage)
                │
                ├── ts3-protocol (JVM models, session adapter, channel ordering)
                └── audio-opus (JNI libopus/RNNoise and Android audio I/O)
```

`ts3-protocol` is JVM-only and does not import Android classes. `audio-opus`
contains the concrete audio infrastructure and depends on protocol data types,
not on Compose. The Android `app` module owns lifecycle, permissions,
notifications, reconnection orchestration and user-visible error mapping.

## State and concurrency

`TeamSpeakServiceState` is the single UI state model. The service publishes it
through a read-only `StateFlow`; UI code never mutates service state directly.
`ConnectionCoordinator` owns the session-epoch state and the connection/
reconnect state machine behind the internal `ConnectionCoordinatorHost` seam;
the host exposes behavior-level operations (start/stop playback, identity
creation, microphone control, foreground teardown) rather than component
references, so the coordinator is JVM-unit-testable with a fake host and a
substitutable session factory. Session replacement is guarded by a `Mutex` and
`SessionGeneration`, a
thread-safe monotonically increasing lifecycle token. The protocol adapter uses
the equivalent `SessionGenerationGate` to serialize generation checks with
snapshot mutations, so callbacks from an old session cannot write into a new
snapshot. A per-connection `ConnectionAttemptGate` atomically orders asynchronous
failures with the `CONNECTED` status; a failure cannot be overwritten by a late
success notification. `MicrophoneController`
owns microphone permissions, push-to-talk intent, capture lifecycle and the
separate transmit mutex; it updates the shared immutable service state through
the application-layer state flow.

`SessionSnapshotStore` is the protocol adapter's synchronized mutable boundary.
It converts ts3j events into immutable `SessionSnapshot` values before crossing
the module boundary.

`Ts3jSessionClient` keeps its public no-argument construction compatible while
obtaining sockets through an internal `Ts3jClientSocket` port. The production
adapter delegates to ts3j; JVM facade tests use a fake port to exercise session
replacement, stale event delivery and connection-status ordering without UDP.

`OpusAudioPlayer` uses Android's voice-communication audio attributes and asks
for audio focus only while a remote talkspurt is being played. `AudioFocusPolicy`
classifies framework callbacks into gain, interruption and ignore signals;
interruptions clear queued voice and mute output without changing the user's
microphone mode. `AudioDeviceRouter` owns communication-device selection, while
playback and capture receive legacy preferred-device hints only on Android
versions before the communication-device API.

`ChannelRestorePolicy` keeps the last channel target, including its password,
outside the session callback implementation. A reconnect snapshot cannot replace
that target while restoration is pending, and an already-restored channel is not
joined again.

## Diagnostics

`DiagnosticsRecorder` is a thread-safe, in-memory counter owned by the service.
It records connection attempts, reconnects, failures and audio frame counts
without storing host names, passwords, nicknames or participant data.
`DiagnosticsSnapshot.toRedactedJson()` is suitable for bug reports after the
user has reviewed it.

The service now also owns `SessionDiagnostics` and an atomic `DiagnosticJournal`
with at most 128 fixed-category events in no-backup storage.
Events append in memory on callback threads; one conflated signal drives an IO
writer, and service destruction flushes the final snapshot. Its v2 report wraps
the existing counters and the persistent event history, including power flags
and Android 11+ process-exit reason codes. `SessionNotifications` owns foreground
types and notification actions. `BackgroundRuntimeController` connects Android
power state and an opt-in preference to the JVM-tested `SessionPowerController`;
timed leases renew for connected sessions, and recovery has a two-minute budget.
`DefaultNetworkMonitor` retains default-network identity, capability and blocked
state for switch-aware liveness checks and reconnect waiting.

`SuccessServerHistoryStore` shares one preferences DataStore between the service
and the view model. Only an accepted current-session success stores host/port,
with ten-entry recency and deletion. The form's initial history load cannot
overwrite user-edited endpoint fields. Nicknames and passwords are not saved.
See [ADR-0007](docs/decisions/0007-background-voice-and-server-history.md).

## Known decomposition work

`MainScreen.kt` now owns only the top-level scaffold and status surfaces.
Connection, connected-session, channel, participant, microphone and diagnostics
surfaces live in focused files under the same `ui` package, each below the
preferred 300-line review boundary.

`TeamSpeakService.kt` owns the binder, participant audio settings, routing glue
and component wiring. Notifications, network observation, power control and
persistent diagnostics now live in focused collaborators. `ConnectionCoordinator`
owns the connection/reconnect state machine and bounded switch checks behind
the JVM-tested `ConnectionCoordinatorHost` seam.

The remaining first-party files near or above the preferred 300-line review
boundary have been assessed as follows. Vendored RNNoise sources are excluded
from this project-specific decomposition policy.

| File | Current responsibility assessment | Planned extraction |
| --- | --- | --- |
| `ConnectionCoordinator.kt` | Above the boundary: attempt orchestration, session listener, switch liveness and binder-facing entry points | Extract the session listener/event handling and network liveness bookkeeping next |
| `TeamSpeakService.kt` | Above the boundary: binder, participant audio settings, routing glue and component wiring; notification handling is extracted | Extract binder-facing audio controls separately when this responsibility grows |
| `OpusAudioPlayer.kt` | 492 lines: playback lifecycle, focus and AudioTrack output; the per-talker jitter pipeline now lives in `TalkerJitterPipeline` | Extract `AudioTrack` creation/output control once the coordinator follow-ups land |
| `OpusMicrophoneCapture.kt` | 275 lines: capture lifecycle and worker thread; the effect chain (`MicrophoneAudioEffects`) and measurement bookkeeping (`CaptureSessionStats`) are extracted | Below the boundary — no further split needed |
| `Ts3jSessionClient.kt` | Above the boundary: connection, disconnect, bounded control checks and voice forwarding; snapshot mapping lives in `Ts3jEventAdapter` | Extract the failure/diagnostics helpers if the facade grows again |
| `AudioDeviceRouter.kt` | Slightly above the boundary but still one cohesive routing responsibility | Extract device classification only if routing families or platform branches grow |

The extraction order is the coordinator's listener/event handling, then
playback output control, then protocol failure adapters. Each step must
preserve the public service
binder and protocol facade, session-generation rejection of stale callbacks,
the bounded audio queue, and existing test fixtures. This avoids combining a
large structural change with the outstanding M10 physical-device acceptance
matrix.

## Compatibility and release scope

The public app API is intentionally small and internal to the Android app. The
protocol facade pins a ts3j revision. The current release target is a pre-release
single-server client with successful endpoint history; text chat, file transfer, named server bookmarks and
multi-server tabs are outside this architecture until a new decision record is
approved.
