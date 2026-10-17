# 0009: Bound first-connect clone-limit recovery

> Historical implementation: automatic clone-limit retries described below were removed on 2026-10-18. See [ADR-0011](../decisions/0011-manual-refusal-and-form-memory.md) and the [current report](../testing/MANUAL_RETRY_MEMORY_VOICE_2026-10-18.md).
**Status:** Accepted (local implementation; device/server acceptance pending)
**Date:** 2026-10-13

## Context

A server can refuse a user-started connection because the same identity already
has too many active connections. An old session after process killing is one
possible cause; another legitimate client is also possible. No verified structured
error code is available. The observed refusal is a `CommandProcessException`
containing `too many clones already connected`, case-insensitively.

The refusal can arrive through the listener before blocking `connect()` returns.
Counting a deadline without cutting that first call, or closing a socket only
once timeout cancellation has returned, leaves an unbounded live attempt.

## Decision

### Classification and compatibility

The protocol owns one cause-chain classifier, used for both retryability and
`ConnectionFailureCategory`. It is cycle-safe. The first command error takes
precedence over deeper causes: only the observed `CommandProcessException` text
qualifies as `CLONES_LIMIT`. Another command error wrapping a clone refusal stays
terminal. Network wrappers preserve the existing transient classification.
The app reads the category and does not re-parse text or invent an error code.

`ConnectionStatus.category` is a trailing field with a default, preserving
existing Kotlin constructor calls. All consumers in this repository are rebuilt;
this is source compatibility, not an ABI promise for precompiled consumers.
The optional internal coordinator policy parameter preserves the trailing
session-factory lambda. There is no identity, password, dependency or stored
configuration migration.

### One fixed window with an independent watchdog

`ClonesRecoveryPolicy` defines 1/2/4/8-second backoff, then 8 seconds, and one
60-second total budget. `ClonesRecoveryEngine` creates an immutable window at the
first observed refusal for a user-started request that has not yet connected.
Registration records STARTED and starts an independent watchdog immediately,
including while the original refused handshake remains blocked. The window's
end result and jobs are synchronized and cannot be inherited by a replacement.

The same deadline covers initial/retry handshakes, identity/audio preparation,
mutex waiting, offline waiting and backoff. Offline recovery waits on the network
flow without spinning. A refusal, transient network failure or network switch
never refreshes the window. Ordinary first-connect network failure still stops;
established-session reconnect keeps its existing engine.

At expiry the coordinator detaches the matching listener and captures the socket
and connection job under the request boundary. It closes that socket **before**
canceling work, without waiting for the attempt mutex or coroutine completion.
`runInterruptible` complements the close. The independent watchdog can therefore
wake a handshake that ignores interruption. The captured job is canceled rather
than reading a potentially replaced `connectionJob` after close.

Deadline checks also protect socket publication after identity loading and
connection callbacks. Success is atomically accepted against the window deadline;
a callback delivered at/after expiry cannot publish CONNECTED or persist a success.
Every failed attempt releases its socket before backoff. A terminal error observed
while a handshake remains blocked ends the window and closes that attempt promptly.
Cancellation, task removal and replacement requests revoke the old window.

Identity material is reused for attempts and only published for the active epoch.
Retries do not invoke the manual-session power-budget reset. Success uses existing
microphone reconciliation, including the restriction on starting stopped capture
while backgrounded.

### End state, cleanup and messages

Expiry closes the socket first, then finishes local audio/routing cleanup under
the attempt mutex and epoch guard. Final cleanup is non-cancellable so a pending
attempt cannot leave shared resources live; a newer request waits for the mutex
and old cleanup cannot mutate or remove its state/notification. The error
explanation stays visible while the activity is bound, with a main-thread,
epoch-guarded notification removal and no further automatic attempts.

An Android scheduler, native audio driver or system name resolver that does not
respond to cancellation can delay final local reclamation. JVM close/interruption
fixtures establish the owned protocol-wait mechanism, not a universal real-device
wall-clock guarantee. Device acceptance must measure these separately.

Retrying, scheduled attempts, offline waiting and expiry use English/Chinese
`UserMessage` resources. RECONNECTING retains the existing cancel entry. Expiry
asks the user to check other clients using the same identity and retry manually;
it refers to the original refusal, without asserting the server was still refusing
at the instant the window ended or that another client is stale.

On 2026-10-18 the localized guidance was expanded to explain both a leftover
session after process killing and another legitimate client using the identity.
It states the retry window, the cancel action, the expiry/manual-retry steps and
the instruction to disconnect before swiping the app away. Server cleanup may
take longer than the retry window. The displayed default budget and recovery
policy share `CLONES_RECOVERY_BUDGET_MS`; there is no second UI timeout policy.
The full message scrolls with the connection form, and a categorized refusal
without a service message gets localized guidance rather than raw protocol text.

### Fixed diagnostics

One window records `CLONES_RECOVERY_STARTED` and exactly one
`CLONES_RECOVERY_ENDED`, including cancellation before the retry job starts.
The result code contains no free text:

| Code | Result |
| --- | --- |
| 0 | Connected within the window |
| 1 | Cancelled or replaced |
| 2 | Fixed deadline expired |
| 3 | Terminal refusal |
| 4 | Unexpected recovery failure; terminal cleanup invoked |

No endpoint, nickname, identity, password, exception text or protocol request is
written into these events.

## Independent review evidence

The first child delivery passed its suites, but parent regressions reproduced:
initial rejected connect outliving the window, retry cancellation waiting before
close, success accepted after the monotonic deadline, and inconsistent terminal
precedence between category/retryability. Repairs added the independent watchdog,
per-window immutable state, callback arbitration and the shared classifier.

A child concurrency fixture reused one socket across attempts. First-failure
close had already released its connect gate, so its next call did not prove a
blocked retry was reclaimed. That case was replaced by
`ClonesRecoveryConcurrencyTest`: separate sockets and per-attempt close/return
signals, including calls that ignore interrupts, late success, terminal refusal,
and a new request registered inside the old socket's close. Parent-owned gates
and counts are recorded in the task-removal review.

## Decomposition and limits

Recovery policy and engine are focused collaborators; session/listener/generation
ownership stays in the coordinator. The coordinator remains above the preferred
300-line boundary. Further listener/liveness extraction follows ARCHITECTURE.md
and is not mixed with this repair. If the recovery engine grows beyond one window
lifecycle responsibility, extract window registration/result arbitration first,
keeping the coordinator's socket ownership and existing tests intact.

The 60-second parameter is not a promise of server release. A persistent legitimate
parallel client causes expiry without being removed. A server that changes the
unverified refusal text will fall back to existing terminal behavior. Android may
kill the process without task-removal callbacks. Real refusal, lifecycle, audio,
server release and the original-limit device matrix remain unverified; no server
permission change is used as evidence.
