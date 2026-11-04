# Architecture

The audio review follow-up is specified in
[the repair plan](docs/plans/AUDIO_REVIEW_FIXES_2026-10-24_ZH.md).
`OpusAudioPlayer` owns Android focus, routing and its worker; the serialized,
JVM-testable `PlaybackSession` owns bounded output replacement and retry timing.
The playback worker exclusively mutates the session. UI/focus/routing calls only
advance an atomic generation; timeline writes recheck it before submitting PCM.
Stop invalidates first, interrupts the worker and joins only off the main thread.
Failed retirement keeps the old control reachable and blocks a new output until
cleanup succeeds. Successful samples accumulate across short talkspurts to clear
the failure streak, and idle wall time advances the limiter's release envelope.
Playback failure guidance is shown only for an established connection. Retirement
errors during intentional disconnect remain diagnostic events without prompting
the user to reconnect. `MicrophoneController` owns capture/send serialization;
`MicrophoneFeedback` owns permission messages and failure-state reporting, and
`MicrophoneTeardown` owns stop completion and its watchdog.
An output that cannot be released blocks replacement. A failed session exposes
`PlaybackStatus.FAILED`, consumed by service state, UI, notifications and a fixed
diagnostic event; aggregate receive/recovery counters are emitted once on
close without media or participant data. `MicrophoneVoiceSource` invalidates the
send generation synchronously, while capture stop/close runs on the controller's
IO dispatcher under its transmit mutex. Capture routing revisions invalidate
queued audio, partial input and DSP history before new-route samples are encoded.
`CaptureResources` owns creation rollback and cleanup. Capture stop requests close
the send port immediately; the microphone foreground type remains until actual
resource release. Service destruction requests stop without joining the main
thread and retains a cleanup completion callback across service-scope cancellation.
The framework controls foreground-service lifetime after onDestroy; a permanently
blocked HAL cannot be safely forced to release from Java. Such a timeout records a
fixed event and keeps resource ownership. Volume updates preserve denoiser history
and frame assembly; acoustic route revisions reconfigure effects and DSP. Unrelated
route-list/error updates do not invalidate capture.

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
versions before the communication-device API. `AudioRoutePlatform` isolates Android calls from
the JVM-tested router: startup/new Bluetooth routes are selected automatically; device removal,
route loss, switch refusal and timeout latch a safety mute before fallback. `BluetoothRouteMonitor`
observes communication-device changes and legacy SCO/noisy broadcasts. `BluetoothPlaybackGuard`
checks actual `AudioTrack` routes across output lifetimes; waiting routes discard stale voice and
unexpected fallback silences output. Service state publishes the safety mute through the existing
listening control. Only a manual unmute clears it; reconnecting a headset cannot resume playback.
See [ADR 0020](docs/decisions/0020-bluetooth-playback-protection.md) for boundaries and verification.

`ChannelRestorePolicy` keeps the last channel target, including its password,
outside the session callback implementation. A reconnect snapshot cannot replace
that target while restoration is pending, and an already-restored channel is not
joined again.
Manual and restore joins capture both the protocol session and listener before
dispatch; completion and cleanup recheck those identities under the session epoch.
Restore completion also refreshes chat's switching state, including on refusal.

## Visual presentation

The Compose layer follows TailSync's warm Canvas palette, with a cream light
default, coordinated warm dark preview tokens and restrained 4/6/10 dp geometry.
`ui/theme` owns colors, typography and shapes; `UiComponents` owns shared panels
and icon tiles. `AppIcon` renders
bundled Lucide 0.468.0 vector drawables without a runtime icon dependency.
Connection fields, status surfaces, session header/navigation and channel
password entry are separate components. `ServerHistorySection` renders the
imported history store's endpoints and forwards selection/removal callbacks.
The background dialog and deferred microphone state use the remote application
ports. Service state and callbacks remain the
single source of connection and audio behavior. See ADR 0010.

The November layout refinement keeps bookmarks beside the connection form
heading and groups the overflow menu by session actions and audio/tools.
`SessionAudioActions` owns route selection, search/presence shortcuts and
playback mute; shortcuts release PTT before opening their existing dialogs.
Session tabs use one row with a separate, localized unread badge and scroll
the selected tab into view when space is limited. Microphone mode selection
uses the component's measured width, switching to a menu for narrow layouts
or larger text. See [device and regression evidence](docs/testing/UI_LAYOUT_2026-11-02.md).

The follow-up completes Material 3's elevated surface and inverse-color roles,
which are independent of the base `surface` color. Menus and dialogs therefore
inherit warm colors without per-popup overrides. `ChannelParticipantRow` now
owns the compact roster row and its icon-only poke shortcut; `ConnectedContent`
shares captured-recipient creation across channel and Users entry points. The
existing dialog and application layer still validate session/identity and own
sending. Connection and app-bar headings omit promotional subtitles and the
duplicate introduction icon. See [feedback refinement checks](docs/testing/UI_REFINEMENT_2026-11-02.md).

Poke member actions now render an original outline SVG through the matching
Android vector. Submission preserves both the selected tab and conversation;
`PokeFeedbackEffect` reports completed outcomes through a brief snackbar.
`PokeMessage` accepts omitted optional reasons, while sender/session validation
remains at the existing boundaries. `PokeAlertObserver` observes chat state and
app visibility, deduplicating generic background notifications via
`PokeNotifications`. Foreground notices remain in-app; notification content and
intents contain no participant/server/message details. Channel-detail UI and
app plumbing are removed; the protocol's optional read-only port remains for
compatibility. See [verification](docs/testing/POKE_UX_2026-11-02.md).

`AwayStatusButton` renders the original coffee/clock vector and confirmed-away
emphasis, exposing pending/unconfirmed states through accessibility semantics.
It opens the existing presence editor without changing synchronization rules.
The overflow support action delegates through `MainActivity` to `ProjectLinks`,
which owns the verified public repository URL and Android HTTPS intent/error
handling. No session data enters that intent. See [checks](docs/testing/AWAY_SUPPORT_2026-11-02.md).

## Task removal and bounded exit

Task removal, UI disconnect and notification disconnect use one coordinator
request path. `SessionRequestOwnership` assigns a monotonically increasing
request revision; a newer connection invalidates an exit's right to clear state
or stop the service. `SessionExitRunner` executes cleanup for a captured socket
without owning the active session. Registered exit jobs form a barrier before
new attempts start shared audio, including exits queued after a new connection.
Terminal failure cleanup holds the attempt mutex and guards state mutation with
`SessionGeneration.withActive`; both terminal and explicit-exit service teardown
re-check ownership in one main-thread notification/`stopSelfResult` action.

One monotonic two-second target covers protocol exit, lock waiting and parallel
audio stops. With voice already detached, the captured session sends its exit
before shared-audio cleanup or session-mutex waiting. Server confirmation remains
distinct from cleanup timeout/failure (`EXIT_CLEANUP_OVERRUN` / `EXIT_CLEANUP_FAILED`).
Native/scheduler overruns are reported and final local audio
cleanup completes before new audio starts. Handshake sockets close before lock
waiting. `close()` keeps its local-release meaning; the optional protocol
`disconnectBounded` method has a no-I/O `UNSUPPORTED` default for other adapters.
The pinned ts3j adapter sends the existing encrypted reliable command and requires
this socket's own server leave notification for `SERVER_CONFIRMED`. Per-socket
`SocketOperations` interrupts and reclaims registered blocking writers, including
writers queued behind the library's command monitor, inside the same deadline.
A local close or UDP ACK cannot serve as confirmation.

`onTaskRemoved` only registers work and appends an in-memory journal category.
Explicit `stopWithTask=false` preserves the callback opportunity and retains the
Android default; `START_NOT_STICKY` stays unchanged. Home/lock/Activity lifecycle
does not invoke this path. Android process killing can bypass callbacks entirely
or deliver them only to a new process that no longer owns the session, as
[verified on Xiaomi / Android 16](docs/testing/TASK_REMOVAL_DEVICE_2026-10-17.md).
See [ADR-0008](docs/decisions/0008-task-removal-bounded-exit.md).

## Terminal identity-limit refusal

`ConnectionFailure` owns a cycle-safe classifier with terminal command precedence.
`CLONES_LIMIT` is nonretryable. `ConnectionCoordinator` atomically detaches the
refused listener, stops `ReconnectEngine`, closes the captured handshake before
cancellation, and publishes localized manual-wait guidance. Epoch checks reject
late callbacks and isolate replacement connections. The former clone recovery
engine/window are removed. Ordinary network recovery retains its existing policy.
The 60-second recommendation is a message constant, not a scheduler or unban
promise. Old journal enum values remain readable for historical diagnostics.
See [ADR-0011](docs/decisions/0011-manual-refusal-and-form-memory.md).

`TalkspurtMicrophone` separates active capture from queue readiness. A temporarily
empty queue returns an empty frame while still ready, which the pinned ts3j
sender skips. Explicit capture stop makes readiness false and produces the end
marker. Source failures report once and end capture readiness. The protocol
module stays JVM-only; `OpusMicrophoneCapture` supplies its capture-active state.

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
timed leases renew for connected sessions, and recovery has a two-minute budget
that survives transient ERROR states and resets for a new manual connection.
`DefaultNetworkMonitor` retains default-network identity, capability and blocked
state for switch-aware liveness checks and reconnect waiting.
The connected-session watch also observes silent transport closure. A per-attempt
volatile latch publishes ts3j watchdog disconnects; pending server disconnects
defer to callbacks, preserving terminal kick/ban behavior. Atomic listener claiming
and session/epoch checks prevent duplicate and stale loss reports.

`SuccessServerHistoryStore` shares one preferences DataStore between the service
and the view model. Only an accepted current-session success stores host/port,
with ten-entry recency and deletion. The form's initial history load cannot
overwrite user-edited endpoint fields. This successful-endpoint history remains
separate from `ConnectionSettingsVault`, which AES-GCM encrypts the last form
(host/port/nickname/password) in a serialized preferences DataStore with a
separate Keystore alias. `ConnectionFormMemory` merges delayed loads per field
without overwriting edits or attaching an old password to a new endpoint.
`MainViewModel` conflates delayed saves and waits for a save attempt before
connecting; errors remain visible. A missing snapshot falls back to legacy
history without changing identity storage. The snapshot is excluded from backup.
See [ADR-0007](docs/decisions/0007-background-voice-and-server-history.md) and
[ADR-0011](docs/decisions/0011-manual-refusal-and-form-memory.md).

Diagnostics and background checks open from the secondary More options menu;
the connected server header owns the visible Disconnect action.

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
| `OpusAudioPlayer.kt` | About 300 lines after A1: lifecycle/focus and bounded worker scheduling | Output construction is now in `AndroidPlaybackOutput`, partial writes/drain in `PlaybackTimeline`, and per-talker receive/mix in `PlaybackStreams`; keep public entry points compatible |
| `OpusMicrophoneCapture.kt` | Near 300 lines: capture lifecycle/worker glue; DSP assembly, gain, gate/onset and session queue now live in `MicrophoneCapturePipeline`, `MicrophoneFrameProcessor`, `VolumeGate`, `CaptureVoiceBuffer` | Keep lifecycle cohesive; extract AudioRecord construction if device-specific setup grows |
| `Ts3jSessionClient.kt` | Above the boundary: connection, disconnect, bounded control checks and voice forwarding; snapshot mapping lives in `Ts3jEventAdapter` | Extract the failure/diagnostics helpers if the facade grows again |
| `AudioDeviceRouter.kt` | Slightly above the boundary but still one cohesive routing responsibility | Extract device classification only if routing families or platform branches grow |

The audio upgrade has completed the narrow playback output-control extraction
in phase A1, independently of the broader coordinator work.
The remaining general extraction order is the coordinator's
listener/event handling, then protocol failure adapters. Each step must
preserve the public service
binder and protocol facade, session-generation rejection of stale callbacks,
the bounded audio queue, and existing test fixtures. This avoids combining a
large structural change with the outstanding M10 physical-device acceptance
matrix.

## Compatibility and release scope

The release APK keeps ini4j's reflection graph, including SPI parser, builder and
formatter constructors. `Ts3IdentityCodec.decode` calls ts3j's `LocalIdentity.read`
before the network connection; ini4j resolves these helpers through `ServiceFinder`
and `Class.newInstance`. Keeping only the directly referenced `Ini` class does not
make the reflective implementations reachable to R8.

`app/src/releaseSmokeTest` is an independent test source set compiled by
`tools/check-release-identity.ps1`, not by the application build. It loads the
already-minified APK on ART to verify identity and INI roundtrips without changing
production reachability, storage or network behavior. This packaging regression
complements JVM tests and device connection acceptance; see
[the issue #3 report](docs/testing/ISSUE_3_RELEASE_IDENTITY_FIX.md).

The public app API is intentionally small and internal to the Android app. The
protocol facade pins a ts3j revision. Stable 1.0.0 (versionCode 13) includes the
single-server feature set: endpoint history, channel/private text, poke,
volume gate, named bookmarks, presence and channel discovery. File transfer and
multi-server tabs remain outside this implementation. The
[release pack](docs/release/README.md) records signing, exact-artifact and device
evidence. [ADR-0021](docs/decisions/0021-stable-release-acceptance.md) records the
publication decision and remaining physical-device checks.

## Channel and private text

`Ts3jTextMessageMapper` safely decodes incoming maps without using throwing
upstream getters. `Ts3jTextCommands` uses the pinned encoder and FIFO queue,
a per-socket submission lock shared with channel moves, a monotonic operation
budget and tracked, interruptible socket work. The facade captures its socket
before dispatch and checks generation, socket identity and application context
before enqueueing. Unknown never implies that the server did not process text.

`SessionChatController` captures a client and `ChatBinding` before launching work;
`ConnectionCoordinator` forwards only its active listener/attempt and updates
channel and participant context. Text waits do not acquire the global session mutex. ChatStore
owns immutable StateFlow snapshots, at most 20 conversations, one global pending message,
per-conversation drafts/read positions and shared count/UTF-8 budgets.
`PrivateChatDirectory` resolves exact UIDs against the current attempt snapshot;
missing UIDs use attempt/client-occupancy keys that expire at attempt end.
`ChatRetentionPolicy` evicts globally by sequence while preserving pending rows.
Private submissions capture their target before dispatch, so selecting another
conversation cannot redirect a queued send. The manual scope survives ordinary
reconnect, while attempt IDs reject stale callbacks within that scope. Manual
connect, exit and terminal failure clear content synchronously. Own echoes cannot
confirm a command and do not add duplicate outgoing rows. Missing channel fields
stay explicitly unknown rather than using the receive-time channel.

The binder exposes draft, submit and visible-position actions plus a separate
chat StateFlow. Activity collects this state without putting it in MainViewModel,
SavedState or persistent storage. Focused ChatPanel/ChatComposer/ChatMessageRow
components own presentation; explicit SessionTab values preserve exhaustive
navigation. Only tab choice, numeric conversation IDs and scroll metadata use saveable UI state.
Closed conversation scroll buckets are removed, including after state recreation. See
[ADR-0013](docs/decisions/0013-bounded-channel-text-chat.md) for deadline, privacy
and decomposition boundaries and [ADR-0014](docs/decisions/0014-session-owned-private-conversations.md)
for identity mapping and conversation limits.
User-list row keys use client IDs so simultaneous clients sharing a stable UID
remain separate rows; identity-based audio preferences retain their existing meaning.

`pokeClient` and `onPoke` are separate typed ports for `clientpoke` and its incoming
event. They share captured-socket validation and the bounded message-command
executor with text. A captured `PokeRecipient` includes scope, attempt, client ID
and stable UID; the store rechecks it before claiming, and the facade checks again
before queue ownership. `ChatMessageType.POKE` distinguishes retained poke rows
without duplicating chat state or drafts. A single notice ID references the latest
retained incoming poke; dismissal cannot clear a newer notice. PokeSendDialog uses
non-saveable local state, and PokeNoticeDialog releases PTT. See
[ADR-0015](docs/decisions/0015-bounded-client-poke.md) for scope and decomposition.

## Microphone processing and volume gate

ADR-0016 adds service-memory microphone controls without a protocol wire change.
The existing OpusMicrophoneCapture(context, onFailure) constructor remains available;
the new state callback overload and MicrophoneCapture port support lifecycle tests.
The capture worker processes 10 ms RNNoise blocks, then 20 ms gain/RMS gate/Opus.
VolumeGate uses 40 ms attack, 6 dB hysteresis, 300 ms hold and six 20 ms onset blocks.
CaptureVoiceBuffer keeps an open talkspurt through empty queues and drains a closed
gate's queued tail. It holds nine encoded blocks; overload drops the oldest.
Each capture owns its buffer/counters and identity-checked callbacks. Configuration
revisions reject in-flight old frames and reset partial PCM/onset; stop retires the
buffer permanently. Failure cleanup checks the capture generation before stopping.

MicrophoneController follows capture for the foreground microphone type, and actual
buffer/gate sending for UI state. Silent gate listening remains an active microphone
use, with a persistent notification and Stop action. Reconnect cannot restart a
stopped microphone in the background. Settings do not activate capture and are
preserved across connection changes within the same service, not service recreation.
MicrophoneSettingsDialog owns gain/threshold/effect controls; narrow or large-font
mode selection uses a menu. Android AEC status is acknowledged by setEnabled and
readback, rather than inferred from isAvailable; platform AGC/NS are disabled when
controllable. OEM processing and real AEC effectiveness remain route/device dependent.

## Staged audio quality upgrade

[ADR-0018](docs/decisions/0018-staged-audio-quality-upgrade.md) and the
[audio design plan](docs/plans/AUDIO_QUALITY_UPGRADE_2026-10-24_ZH.md) track staged
implementation. A0 fingerprints and synthetic fixtures precede A1 continuity.
`PlaybackStreams` owns per-speaker receive/decode/mix; `PlaybackTimeline` owns
PLAYING/RECOVERING/DRAINING/IDLE and `AndroidPlaybackOutput` owns Android I/O.
Per-speaker startup buffering begins at 60 ms and now adapts within 40–120 ms.
Temporary starvation retains output for 60 ms without adding silence or inventing
trailing PLC. Known-gap PLC is capped at three packets and 60 ms. Natural drain
waits for accepted sample counts using the unwrapped unsigned playback head;
remaining duration plus 100 ms bounds waiting, with a 500 ms upper limit and an
explicit timeout counter. No new fixed lookahead is added.

Writes are nonblocking, limited to eight attempts per tick, retain partial blocks
and fail after 100 ms without completing a block. Explicit stop/mute/focus loss
or route change serializes invalidation with writes and discards immediately.
Actual routed-device changes and preferred-device changes reset the output origin;
initial route discovery does not count as a route change. Focus callbacks belong
to their output generation. A timed-out worker prevents restart until retired.
Shared-media overload retires only the affected speaker's old generation and
preserves fresh input, with a 5 ms fade inside valid new samples.
Empty transport payloads never reach native decode; nonempty payload size alone
does not classify DTX. Public protocol ports are unchanged.

`GainEnvelope` applies float manual gain with a sample-rate-derived 20 ms ramp;
zero gain takes effect immediately. `MicrophoneFrameProcessor` branches gate RMS
before limiting, using equivalent saturated PCM16 levels for the existing thresholds.
`PcmLimiter` uses immediate peak attack, 5 ms hold, 80 ms exponential release and
a -1 dBFS ceiling. It buffers no PCM: additional algorithm delay and pending DSP
tail are both zero. Gate onset and output therefore share the same sample indices.
Configuration revisions clear onset, partial-frame and limiter state, retaining only
the gain envelope's current scalar for the next ramp. No old audio crosses revisions.
Each `PlaybackStreams` owns its mixer, per-client gain envelopes and bus limiter.
Fixed unity summation replaces count-based normalization: a silent extra route never
attenuates another voice, while loud sums share bus limiting. Overload can still
modulate quiet voices; limiter tuning and acoustics require physical acceptance.
See [A2 implementation and checks](docs/testing/AUDIO_A2_2026-10-24.md).

A3 adds an explicit development APM library, excluded from default builds.
Its standalone source release, Abseil and build tooling are pinned in
`tools/apm-experiment.lock.json`; an opt-in Gradle property connects verified Meson
static outputs to a separate JNI library. No complete call stack is imported.
`PlaybackTimeline` can publish only accepted post-mix/limiter samples to a session's
`RenderReferenceBuffer`, assembling 480-sample float blocks across partial writes.
The optional sink is null in the public player constructor. A capture subscriber
enables a 50-block reference queue; capture absence, overflow, flush, source gaps
and generation changes discard old history. DSP consumption is capped at five
blocks per tick; it never holds an AEC lock on the output worker.
`AndroidAudioClocks` adapts monotonic AudioTrack/AudioRecord sample clocks.
`ApmDelayEstimator` combines render presentation and capture age, bounds missing
clock estimates to 100 ms and distinguishes measured/estimated/unavailable timing.
`ApmExperimentWorker` serializes reverse/capture operations, resets on discontinuity
and rejects output completed under an obsolete generation. Persistent clock/reference
failure, three consecutive >5 ms ticks or native errors request an explicit baseline
restoration callback; failed baseline initialization propagates. Policy changes
cannot automatically reenable the experiment or start a microphone.
The policy requires calibrated capture and disabled controllable platform effects;
Bluetooth/unknown routes are refused. APM NS and RNNoise are mutually exclusive
within this experiment, and AGC1/AGC2 remain off. Float APM output can use the same
manual-gain/gate processor, preserving the pre-limiter branch without PCM16 rounding.
`ApmCapturePipeline` assembles those outputs into 20 ms frames and clears old
partials/onset on revision, fallback or processed-generation changes. AEC/NS intent
changes require an explicit processor rebuild; no service integration is enabled.
See [A3 scope and evidence](docs/testing/AUDIO_A3_2026-10-24.md).

Live APM capture integration and physical acoustic acceptance remain pending.
The AudioRecord clock adapter is not enabled in the current capture loop. Real
route/effect control and capture/render mapping still require device verification;
controllable AEC/NS paths must not be stacked.
Adaptive AGC remains disabled until the existing volume-gate semantics can be
preserved. Device acoustics and power require later physical acceptance.

The narrow playback-output extraction is independent of the broader coordinator
extraction listed above. DSP, output clocks and bounded reference
queues stay in audio-opus. The service retains lifecycle/settings orchestration;
public protocol ports, persistent settings and capture defaults are unchanged.
See the [A0/A1 execution record](docs/testing/AUDIO_A0_A1_2026-10-24.md) for offline
evidence and uncompleted baseline/device acceptance items.

### Pinned Opus source build

A4a replaces the 1.3.1 Prefab dependency with official Opus 1.6.1 sources.
`opus-source.cmake` reads the archive URL/hash from `tools/opus-compat.lock.json`,
verifies it through CMake FetchContent and links one static codec into ts3opus_jni.
An optional `opusSourceArchive` Gradle property accepts a local archive with the
same mandatory hash. Source build options are centralized in `opus-options.cmake`:
float codec, hardening on, DRED/deep PLC/OSCE and custom modes off. The codec is
optimized in Debug as well as Release. JNI exports stay visible while static
dependency symbols are hidden; the shared library uses 16 KiB LOAD alignment.
No public protocol or encoder control changes accompany the library upgrade.

`tools/check-opus-compat.py` builds independent host 1.3.1/1.6.1 test binaries and
checks four-way encoded-packet compatibility. Android tests decode the same hashed
mono/stereo/music/DTX/duration fixtures with the original Prefab baseline and the
new production JNI. A separate probe loads the actual minified APK's native
encoder/decoder methods without changing R8 reachability. Runtime evidence names
the tested ABI; cross-compilation alone does not establish other-device behavior.
See [A4a execution record](docs/testing/AUDIO_A4A_2026-10-24.md).

### Bounded receive scheduling

`VoiceReceiveQueue` validates real Opus framing before admission and checks a
60 ms monotonic residence bound on enqueue and dequeue. Its 256-packet guard
remains a memory limit. `ReceiveMediaBudget` issues per-client leases that follow
input, pending packets and decoded PCM; moving a packet cannot renew its budget.
Samples release incrementally when read for the mix. Each client is limited to
240 ms, 96 packets and 120 KiB; global encoded reservation is 512 KiB with at most
64 tracked clients/decoders. Input payloads are copied after inspection.
Age/overload invalidation retires only affected leases and clears the old decoder
state rather than concealing intentionally discarded backlog. Still-fresh queued
packets retain their original arrival and are readmitted under the new generation.

`AdaptiveJitterDelay` uses a 64-observation window of continuous, equal-duration,
non-uncertain arrivals, a high quantile and 10 ms target steps. Stable decreases
require 128 valid observations; loss/DTX/discontinuity do not train the estimator.
Targets normally apply at the next talkspurt. At an explicit empty-PCM gap,
an increased target permits one 10 ms rebuffer per missing sequence, bounded at
120 ms. `ReceivePolicy(adaptive = false)` retains fixed 60 ms comparison/rollback.
No sample stretching or ordinary-speech duplication is performed.

`ReceivePcmBuffer` holds leased complete decodes, preserving short tails and long
packets. The former integer-only FIFO is retired. Sequence wrap/deduplication and
late rejection remain; a completed talkspurt retains its sequence retirement floor.
PLC stays capped at three packets and 60 ms with no invented trailing PLC.
Native metadata uses `opus_packet_parse`, actual duration and `opus_packet_has_lbrr`.
FEC requires one known-length loss, next-packet arrival by the current deadline,
matching confirmed framing and actual LBRR. It decodes the missing frame then the
next packet normally once. Lack of redundancy uses PLC; attempts are not successes.
TS3 provides no media timestamp/framing guarantee, so default receive policy does
not infer lost duration from neighboring packet sizes; FEC remains gated off for
that unconfirmed case. The explicit fixed-framing port is validated with real
redundancy fixtures, without changing production encoder FEC/loss settings.

`ReceiveStats` stores bounded aggregate counts and maximum stage/combined ages,
including accepted output after partial writes. These measure application queueing,
not capture age, network transit or acoustic latency. No identity/PCM is logged.
See [A4b traces, parameters and limits](docs/testing/AUDIO_A4B_2026-10-24.md).

## Bookmarks, presence and channel discovery

ADR-0017 fixes the current scope to recommendations 1/3/4. BookmarkVault has its
own DataStore ciphertext and Keystore alias. BookmarkCodec bounds and validates
schema/version/count/fields/IDs, strips passwords unless opted in, and compares
stable IDs/revisions on save/delete. BookmarkController serializes loading and
mutations, exposing errors without replacing corrupt storage. Existing form,
history and identity formats/keys are unchanged; downgrade simply ignores the
new file. Form selection replaces endpoint-dependent fields; default-channel
parameters stay in memory unless explicitly saved in a bookmark. Initial connect
seeds the existing ChannelRestoreTracker; plain connections reset that target.

BookmarkDialogs coordinates the list, editor and delete confirmation without
owning persistence. BookmarkListDialog keeps Add outside its scrollable cards;
BookmarkCard separates Connect from its secondary-action menu. BookmarkEditor
groups basic connection fields and expandable optional settings, retaining their
values when collapsed. Storage, callback and password opt-in contracts are unchanged.

SessionControlsController binds the actual Ts3SessionClient per attempt and
coalesces desired OwnPresence independently of frame/capture activity. AEC/gate
listening and idle PTT do not toggle protocol mute. Explicit OFF, missing permission
or microphone errors publish input mute; playback mute publishes output mute.
Permission results and foreground entry explicitly refresh the permission signal,
including idle PTT where capture state may not emit a change. Granting permission
clears permission errors without starting capture or clearing unrelated audio failures.
A binding lock and request revisions reject stale completion and replacement
sessions. Channel-details UI and service plumbing have been removed; the public
read-only protocol port remains for compatibility. Search is pure current-snapshot
filtering with up to 50 channels and 50 participants; resolving a user's channel
requires the current UID mapping.

BoundedSessionCommands is extracted from the existing text runner so presence,
channelinfo, text and poke share one claim/deadline/interrupt/FIFO implementation.
New protocol ports default to Unsupported. Model/config additions are defaulted
trailing fields; all consumers rebuild together, without a precompiled ABI promise.
MainScreenAppBar now owns the shared toolbar/menu, keeping MainScreen below the
preferred 300-line boundary. Bookmark, presence and discovery dialogs/controllers
remain separate from the connection coordinator and the activity/service wiring.
