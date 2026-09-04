# 0005: Extract a ConnectionCoordinator from TeamSpeakService

**Status:** Accepted (implemented 2026-09-04)
**Date:** 2026-08-26

> Implementation notes (2026-09-04): the extraction landed in the three
> committed steps described below. One deviation from the original sketch: the
> host interface exposes behavior-level operations (start/stop playback,
> create identity, attach microphone) instead of component references
> (`audioPlayer`, `identityVault`, …), because the app module's JVM unit tests
> cannot construct those Android-bound classes; a fake host implementing the
> operations keeps the coordinator fully testable on the JVM. The coordinator
> also receives a `sessionFactory` seam so tests can substitute the session.
> `TeamSpeakService.kt` dropped from 1018 to 494 lines; the new
> `ConnectionCoordinator.kt` is 678 lines and remains above the 300-line
> boundary — the follow-up extractions (event mapping and the reconnect loop)
> should target it next. `ConnectionCoordinatorTest` covers the four minimum
> cases below with a fake host and virtual-time coroutines.

## Context

`TeamSpeakService.kt` is 1018 lines and holds five distinct responsibilities that share one
Android `Service` lifecycle:

1. **Connection orchestration** — `beginConnection`, `performConnectionAttempt`,
   `requestDisconnect`, `disconnect`, the inner `SessionListener`, `onSessionConnected`,
   `onEstablishedSessionEnded`, `launchReconnect`, `reconnectLoop`, `suspendAudioForReconnect`,
   `finishTerminalFailure`, `scheduleStableConnectionReset`, `restoreLastChannelIfNeeded`.
2. **Foreground notification** �� `createNotificationChannel`, `buildNotification`,
   `updateNotification`, `updateForegroundType`, `startForegroundWithTypes`.
3. **Participant audio settings** — `setParticipantMuted`, `setParticipantVolume`,
   `updateParticipantAudioSettings`, `applyParticipantAudioSettings`.
4. **Audio routing glue** — `onAudioRoutingChanged`.
5. **Diagnostics refresh** — `startDiagnosticsRefresh`, `refreshDiagnostics`, and the
   `DiagnosticsRecorder` ownership.

`ARCHITECTURE.md` already commits to the extraction order: service orchestration first, then
playback/routing and foreground-notification controllers. This record makes the first step
concrete. The goal is to reduce `TeamSpeakService.kt` toward a thin binder/notification owner
without changing the public `SessionBinder` API, the session-generation rejection of stale
callbacks, the bounded audio queue, or any existing test fixture.

The service currently mixes two kinds of state that are easy to conflate during review:

- **Session-epoch state** that must be invalidated atomically when a new connection starts or
  the user disconnects: `session`, `activeListener`, `connectedOnce`, `restorePending`,
  `desiredConfig`, `identityMaterial`, `lastChannel`, `reconnectAttempt`, `connectionJob`,
  `reconnectJob`, `stableConnectionJob`.
- **Cross-session state** that survives a reconnect: `microphoneMode`, `playbackMuted`,
  `participantAudioSettings`, `audioRouting`, `diagnostics`.

The first group is what makes the connection logic hard to follow; it is exactly what a
`ConnectionCoordinator` should own.

## Decision

Introduce an internal `ConnectionCoordinator` that owns the session-epoch state and the
connection/reconnect state machine. Keep it inside the `app` module (not a new Gradle module)
and inside the `service` package, so the public `SessionBinder` and the foreground-service
lifecycle stay in `TeamSpeakService`. The coordinator is an internal collaborator, not a new
module boundary.

### What moves into `ConnectionCoordinator`

```
app/src/main/java/io/github/ts3mobile/app/service/ConnectionCoordinator.kt
```

- The session-epoch fields: `session`, `activeListener`, `connectedOnce`, `restorePending`,
  `desiredConfig`, `identityMaterial`, `lastChannel`, `reconnectAttempt`, `connectionJob`,
  `reconnectJob`, `stableConnectionJob`.
- The methods: `beginConnection`, `performConnectionAttempt`, `requestDisconnect`,
  `disconnect`, `onSessionConnected`, `onEstablishedSessionEnded`, `launchReconnect`,
  `reconnectLoop`, `suspendAudioForReconnect`, `finishTerminalFailure`,
  `scheduleStableConnectionReset`, `restoreLastChannelIfNeeded`.
- The inner `SessionListener` and the `AttemptResult` sealed interface.
- `isListenerActive` / `isEpochActive` helpers, backed by the same `SessionGeneration` and
  `SessionGenerationGate` instances (passed in, not re-owned).

### What stays in `TeamSpeakService`

- The `Service` superclass: `onCreate`, `onBind`, `onStartCommand`, `onDestroy`.
- `SessionBinder` and all its public methods (unchanged signatures).
- Foreground notification ownership: `createNotificationChannel`, `buildNotification`,
  `updateNotification`, `updateForegroundType`, `startForegroundWithTypes`.
- `DiagnosticsRecorder` ownership and `refreshDiagnostics` / `startDiagnosticsRefresh`.
- `MicrophoneController`, `OpusAudioPlayer`, `AudioDeviceRouter`, `IdentityVault` ownership.
- Network callback registration and `updateNetworkAvailability`.
- Participant audio settings (this moves in a later step, not this one).

### The seam

The coordinator talks to the service through a small internal interface so the service keeps
owning the UI state flow, the audio components, the notification, and the diagnostics recorder:

```kotlin
internal interface ConnectionCoordinatorHost {
    val serviceScope: CoroutineScope
    val sessionMutex: Mutex
    val sessionGeneration: SessionGeneration
    val mutableState: MutableStateFlow<TeamSpeakServiceState>
    val reconnectPolicy: ReconnectPolicy
    val diagnosticsRecorder: DiagnosticsRecorder
    val networkAvailable: StateFlow<Boolean>

    val identityVault: IdentityVault
    val audioPlayer: OpusAudioPlayer
    val microphoneController: MicrophoneController
    val audioRouter: AudioDeviceRouter

    fun conciseMessage(error: Throwable): String
    fun updateNotification()
    fun refreshDiagnostics()
    fun startForegroundForConnection(host: String)
    fun stopForeground()
    fun stopSelf()
}
```

The service implements this interface (as an internal `ConnectionCoordinatorHost`); the
coordinator holds a reference to it. This keeps the dependency direction honest — the
coordinator depends on the host abstraction, not on `Service` — and lets the coordinator be
unit-tested with a fake host the same way `MicrophoneController` is today.

The coordinator exposes back to the service:

```kotlin
internal class ConnectionCoordinator(private val host: ConnectionCoordinatorHost) {
    fun beginConnection(config: ServerConfig)
    fun requestDisconnect()
    fun joinChannel(channelId: Int, password: String)
    fun reconcileMicrophoneAfterConnect()
    fun onNetworkChanged(available: Boolean)
    fun close()   // cancels epoch, joins jobs, releases the session
}
```

`SessionBinder`'s `joinChannel` delegates to `coordinator.joinChannel(...)` instead of the
service's private `joinChannel`. The network callback's
`updateNetworkAvailability` delegates to `coordinator.onNetworkChanged(available)` for the
reconnect-driving cases and keeps the `mutableState` update for the UI status line.

### Step ordering

Do this in three reviewable commits, each leaving the build green:

1. **Introduce the host interface and an empty coordinator.** Add
   `ConnectionCoordinatorHost`, make `TeamSpeakService` implement it, add an empty
   `ConnectionCoordinator` constructed in `onCreate`, and wire `beginConnection` /
   `requestDisconnect` to no-op calls on the coordinator. The service's existing methods still
   run. This proves the seam compiles and the binder still works.

2. **Move the session-epoch state and connection methods.** Relocate the fields and methods
   listed above into the coordinator, one logical group at a time (connection attempt, then
   reconnect loop, then channel restore). The `SessionListener` and `AttemptResult` move with
   them. After this commit the service no longer references `session`, `activeListener`, etc.
   directly; it goes through the coordinator.

3. **Route the binder and network callback through the coordinator.** Change `SessionBinder`
   to delegate `joinChannel` to the coordinator, and change the network callback to call
   `coordinator.onNetworkChanged(...)`. Remove the now-dead private methods from the service.

### What must not change

- `SessionBinder`'s public method names and signatures (called from `MainActivity`/Compose).
- The `Intent` extras and `ACTION_CONNECT`/`ACTION_DISCONNECT` contract (used by
  `TeamSpeakService.connect`/`disconnect` companions and the notification's disconnect action).
- `SessionGeneration` invalidation semantics and the `SessionGenerationGate` integration in
  `Ts3jSessionClient` — the coordinator must still call `sessionGeneration.beginSession()` on
  connect and `sessionGeneration.invalidate()` on disconnect, exactly as today.
- The bounded audio queues in `OpusAudioPlayer` and `OpusMicrophoneCapture`.
- The `ConnectionAttemptGate` ordering inside `Ts3jSessionClient`; the coordinator only consumes
  the `ConnectionStatus` it emits.
- All existing tests. `ReconnectPolicyTest`, `ChannelRestorePolicyTest`,
  `MicrophoneCapturePolicyTest`, `SessionGenerationTest`, and the protocol fixture tests must
  pass unchanged.

### New tests

Add a `ConnectionCoordinatorTest` (JVM, `app/src/test`) with a fake host, mirroring how
`MicrophoneController` is tested through its host callbacks. The minimum cases:

- A stale `SessionListener` callback (token mismatch) does not mutate `mutableState` and does
  not start a reconnect.
- `requestDisconnect` invalidates the generation and cancels `connectionJob`/`reconnectJob`
  without calling `launchReconnect`.
- `onNetworkChanged(false)` while `CONNECTED` drives a retryable disconnect into the reconnect
  loop; `onNetworkChanged(true)` while `RECONNECTING` unblocks the
  `networkAvailable.first { it }` wait.
- `finishTerminalFailure` clears the session, stops the foreground service, and calls
  `stopSelf`.

These are the cases the current service tests cannot isolate because the state machine is
wired into the `Service`. They are the point of the extraction.

## Consequences

- `TeamSpeakService.kt` drops to roughly 350–450 lines and its remaining responsibilities
  (binder, notification, diagnostics, component ownership) become cohesive.
- The reconnect state machine becomes unit-testable without a `Service` instance, which is the
  single largest test-coverage gap today.
- Reviewers stop needing to hold five responsibilities in mind to judge a connection-logic
  change.
- The host interface is internal to the `app` module; no public API or cross-module contract
  changes, so no migration plan is required.

## Alternatives considered

- **Extract the notification controller first.** Rejected: the notification code is cohesive
  and low-risk; the connection logic is the part that is hard to review and test, and
  `ARCHITECTURE.md` already orders it first.
- **Make `ConnectionCoordinator` a separate Gradle module.** Rejected: it depends on
  `TeamSpeakServiceState`, `MicrophoneController`, `OpusAudioPlayer`, `IdentityVault`, and
  `AudioDeviceRouter`, all of which are Android-bound and in `app`. A new module would either
  depend on Android (defeating the JVM-test benefit) or require pulling those out too, which
  is far beyond this step.
- **Rewrite the state machine as a sealed-state reducer.** Rejected for now: the current
  `ConnectionPhase`-driven flow is readable and tested. A reducer is a larger redesign that
  should wait until the coordinator exists and its state transitions are isolated enough to
  model cleanly.

## Re-evaluate when

- The coordinator extraction is green and the remaining service responsibilities are still
  above the 300-line boundary, at which point the notification and participant-audio
  extractions follow the same host-interface pattern.
- A multi-server architecture (ADR-0002 re-evaluation) lands, which would turn the coordinator
  into a per-session object and likely move it across a module boundary.
