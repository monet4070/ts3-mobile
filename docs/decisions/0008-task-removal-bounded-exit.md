# 0008: Leave the session bounded when the task is removed

**Status:** Accepted (implemented 2026-10-13; Android 16 protocol acceptance recorded 2026-10-17; Xiaomi launcher swipe exit fails)
**Date:** 2026-10-13

## Context

Removing the app from the recents list used to leave the TeamSpeak session behind:
`TeamSpeakService` had no `onTaskRemoved` override. The manifest did not
explicitly declare `stopWithTask` (whose Android default is already false), but
there was no callback implementation to send a leave request. The explicit disconnect path invalidated the session
generation but then called the pinned library's `disconnect(reason)`, which can
first wait up to 10 seconds for `CONNECTED` and then up to 30 seconds for
`DISCONNECTED`, and still proves nothing about the server releasing the session.
A user who removed the task and immediately reconnected could therefore be refused
by the server's clone limit while the old session was still registered.

At the same time, a removal callback is not a guarantee: Android may kill the
process without calling `onTaskRemoved`, and vendor task managers can stop an app
directly. Only three outcomes can be reported honestly — the callback ran and the
server confirmed the leave, the callback ran and the leave was not confirmed, and
no callback ran at all.

## Decision

### Lifecycle

- `TeamSpeakService` overrides `onTaskRemoved`. It records the fixed
  `TASK_REMOVED` category and submits the exit request; the journal append is
  in-memory and registration is synchronous, so neither disk nor network blocks
  the main thread.
- The manifest sets `android:stopWithTask="false"` to explicitly preserve the opportunity for the callback;
  this does not guarantee delivery or change the Android default. `START_NOT_STICKY` is unchanged, and
  nothing in the activity lifecycle (`onStop`/`onDestroy`) triggers an exit, so
  Home, app switching and screen lock keep the existing background voice
  behaviour.
- `onDestroy` keeps releasing local resources synchronously. It closes the socket
  an in-flight exit captured instead of starting a network exit on a scope that is
  about to be cancelled, and it never waits for the server.

### One bounded exit for every path

The UI disconnect, the notification action and task removal all register the same
exit through `ConnectionCoordinator.requestExit`. Everything up to the job launch
runs synchronously on the caller's thread:

1. the exit token is registered and the captured session leaves the coordinator's
   `session` field, so only this exit may touch it;
2. the session generation is invalidated, reconnect/liveness/stable-reset/connected
   watch jobs are cancelled, and the captured socket's voice source is detached so
   no further frame can be sent;
3. the state moves to `DISCONNECTING` and the notification is updated.

The IO part spends one monotonic budget of two seconds (`EXIT_BUDGET_MS`). As
corrected on 2026-10-17, protocol exit and captured-socket release run first,
before the session mutex wait and parallel capture/playback reclamation. Voice
has already been detached and the registered exit job remains the barrier for
new connections, so slow cleanup must not defer notifying the server. Blocking audio operations run in
interruptible IO tasks, never on the main thread. A handshake is never waited for:
the captured socket is closed before any lock is taken, because the attempt that
is blocked inside the native connect holds the session mutex; that close is the
lever that wakes it. Nothing is sent in that case and the result is reported as
`NO_ACTIVE_SESSION`, which is not a server confirmation.

The protocol call is `Ts3SessionClient.disconnectBounded(remainingMs, reason,
onRequestSent)`; the old `disconnect(reason)` is never used on this path.
`onRequestSent` fires only after the first exit datagram is written, so "sent" and
"server confirmed" stay separate facts. Timeout, failure and unsupported results
all still release the captured session locally.

### Ownership and concurrency

`SessionRequestOwnership` serializes connect and exit requests behind one
monotonic revision. Every exit request carries a token; a connect request
invalidates it, and a second exit request while an exit already owns the service
reuses the running token so that exit can finish the teardown. A boolean "an exit
is latest" is deliberately not used: an old exit whose teardown was still queued
would find it true again after a later exit and could stop the service under it.

`beginConnection` registers first, synchronously, and therefore takes ownership
back from a finishing exit: the exit still releases the socket it captured, but it
may no longer clear shared state, remove the notification or stop the service.
The exit's final state write happens inside the same ownership lock as request
registration. A registered exit job is a barrier before any new attempt starts
audio, including while the exit has not yet acquired the session mutex. Further
exits after a connect request queue behind that barrier with a new token. Genuine
duplicate exits neither restart cleanup nor refresh the deadline.

The final teardown is handed to the main thread as one action carrying the exit
token: `finishExitOnMainThread(exitToken)` re-checks the token there and then
removes the notification and calls `stopSelfResult(latestStartId)` together. Both
the check and the side effects therefore happen on the thread that also processes
a new `ACTION_CONNECT`, and the newest start id keeps a notification action that
arrived in between from being stopped by an older exit. `finishTerminalFailure`
keeps shared audio cleanup inside the attempt mutex, atomically checks its epoch
when updating state, and posts a separately epoch-guarded main-thread teardown
for the same reason.

### Diagnostics

The existing fixed-category journal gains `TASK_REMOVED`, `EXIT_STARTED`,
`EXIT_REQUEST_SENT`, `EXIT_SERVER_CONFIRMED`, `EXIT_TIMED_OUT`, `EXIT_FAILED`,
`EXIT_NO_ACTIVE_SESSION`, `EXIT_UNSUPPORTED` and numeric
`EXIT_CLEANUP_OVERRUN`. Only categories and numbers are
stored: no reason text, exception text, address, nickname, identity or password.

`EXIT_CLEANUP_FAILED` separately reports local reclamation errors. A confirmed
server leave is not overwritten by a later audio timeout or cleanup failure.

## Consequences

- One removal gesture produces at most one leave command, and a reconnected
  session is never torn down by the exit it replaced.
- One deadline bounds the protocol exit, audio tasks and mutex wait. A missed
  deadline closes the captured socket and reports `EXIT_TIMED_OUT`. If a native
  audio driver or scheduling delay outlasts cancellation, final local audio
  cleanup completes in a non-cancellable section before a new attempt starts.
  Any overrun is recorded as a numeric duration; a universal two-second native
  cleanup guarantee is not claimed. Cleanup never proves server confirmation.
- The server-side release is only proven when the server sends this client its own
  `notifyclientleftview`. A lost acknowledgement, a local close and a watchdog
  transition are all reported as "not confirmed", and the server may still hold
  the old session until its own timeout. First-connect clone recovery is a
  separate item and is not part of this decision.
- Android gives no guarantee that `onTaskRemoved` runs. A process killed by the
  system or by a vendor task manager still leaves the session to the server's
  timeout; the app cannot promise an immediate release.
- On the tested Xiaomi Android 16 device, `SwipeUpClean` killed the original
  process before a newly created service received `onTaskRemoved`. Battery
  unrestricted mode and task locking did not prevent it. Three framework task
  removals and three manual disconnects did receive server confirmations, but
  launcher swipe removal failed. See the
  [device report](../testing/TASK_REMOVAL_DEVICE_2026-10-17.md); the original
  physical-device launcher acceptance remains open.
- `ConnectionCoordinator` and `TeamSpeakService` remain above the roughly
  300-line guidance. `SessionExitRunner` executes captured-resource cleanup and
  `SessionRequestOwnership` governs request revisions; session/generation
  ownership stays in the coordinator. This change avoids the broader split. The extractions already
  planned in `ARCHITECTURE.md` (event mapping, reconnect loop, notification and
  playback controllers) remain the follow-up work.

## Alternatives considered

- **Use the library's `disconnect(reason)` from the removal callback.** Rejected:
  up to 40 seconds of blocking, no server confirmation, and it would still run on
  a scope that Android may cancel.
- **Only close the socket and let the server time the session out.** Rejected as
  the sole behaviour: it is what the old path did, and it is the reason the clone
  limit blocks an immediate reconnect.
- **Keep a boolean "an exit is latest" for the teardown check.** Rejected after
  review: it cannot distinguish an old queued teardown from a newer exit.
- **Stop the service from the IO thread as soon as the exit returns.** Rejected: a
  connect that the main thread is already processing would be stopped underneath.
- **Let new attempts overtake shared audio cleanup.** Rejected: a new attempt
  could start audio and then be silenced by old cleanup. The registered exit
  barrier and the terminal-failure attempt mutex prevent that ordering.

## Re-evaluate when

- Device acceptance shows the two second budget is wrong for a real network, or
  the capture stop regularly consumes most of it, or
- the first-connect clone recovery item (D3) changes what "the session is gone"
  means for the UI, or
- the notification and playback controllers are extracted from the coordinator,
  which should move the exit sequencing into its own collaborator.
