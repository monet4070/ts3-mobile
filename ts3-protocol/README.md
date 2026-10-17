# ts3-protocol

JVM-only protocol adapter. It owns the TeamSpeak session behind
`Ts3SessionClient`, converts ts3j events into immutable `SessionSnapshot` values
and keeps every ts3j type inside this module. See
[`docs/decisions/0004-take-ownership-of-ts3j-dependency.md`](../docs/decisions/0004-take-ownership-of-ts3j-dependency.md)
for the pinned dependency policy and [`ARCHITECTURE.md`](../ARCHITECTURE.md) for
the module boundaries.

## Session interface compatibility

`Ts3SessionClient` keeps three separate capabilities:

- `disconnect(reason)` — the original blocking exit. It invalidates the session
  immediately and calls the transport's own disconnect; it does not bound the
  call, so it must not run on a user-visible path.
- `close()` — local resource release only. It never performs a protocol exit.
- `disconnectBounded(timeoutMs, reason)` — the new bounded, server-confirmed
  exit. The default implementation returns
  `DisconnectOutcome.UNSUPPORTED` and performs no I/O, so every existing
  implementation and test double stays source-compatible; only
  `Ts3jSessionClient` overrides it.

`DEFAULT_DISCONNECT_REASON` is the single source of the default reason string
used by both `disconnect` and `disconnectBounded`.

`DisconnectOutcome` reports what was observed, never a guess:

| Outcome | Meaning |
| --- | --- |
| `SERVER_CONFIRMED` | This session's own leave notification arrived inside the budget. |
| `NO_ACTIVE_SESSION` | No established session existed; no command was sent and nothing was waited for. |
| `TIMED_OUT` | The budget elapsed before the server confirmed the leave. The session is still released locally. |
| `FAILED` | Sending the leave command failed before any confirmation could arrive. |
| `UNSUPPORTED` | This transport cannot leave a session gracefully. |

A UDP acknowledgement, a local `close()`, a watchdog transition to
`DISCONNECTED` and another client's leave notification never produce
`SERVER_CONFIRMED`.

`Ts3jSessionClient.disconnectBounded` captures and invalidates the current
session before anything else, so the old listener, generation and microphone stop
being used at once, and a replacement session started meanwhile is never touched.
It emits no status: the caller owns the user-visible state once the session is
gone. The transport port `Ts3jClientSocket.disconnectBounded` carries the same
default `UNSUPPORTED`, so transports without a graceful leave stay compatible.

### Budget accounting

`timeoutMs` covers sending, confirmation and local reclamation in one monotonic
budget. The runner reserves up to 250 ms (at most one quarter of a short budget)
for cleanup before the deadline; there is no extra grace period afterwards.
The facade subtracts time spent detaching the session and entering its IO context.
A cleanup that cannot reclaim its workers reports `FAILED`, not completion.
`onRequestSent` is an optional callback after the first exit datagram is written;
it does not establish server release, and ACKs do not establish it either.

## Fixed ts3j blocking points

The pinned artifact is `com.github.Manevolent:ts3j:db57d60c989e399626aa16d921390f5033e6cdeb`.
The findings below come from the cached jar (`javap -p -c` on
`LocalTeamspeakClientSocket`, `AbstractTeamspeakClientSocket`, `PacketResponse`,
`PacketBodyType`) and the matching upstream source.

- `LocalTeamspeakClientSocket.disconnect(String)` waits up to 10 s for
  `CONNECTED` when the state is `CONNECTING`/`RECONNECTING`, writes
  `clientdisconnect` with `reasonid=8` and `reasonmsg`, then waits up to 30 s for
  `DISCONNECTED`. Worst case 40 s, on the caller's thread, and it never proves the
  server released the session. This is why the bounded path does not call it.
- `AbstractTeamspeakClientSocket.writePacket(PacketBody)` builds the command and
  delegates to `writePacket(Packet)`. `COMMAND` is splittable, so the write takes
  `synchronized (sendLocks.get(PacketBodyType.COMMAND))` and then calls
  `writePacketIntl` per fragment.
- `writePacketIntl` encrypts through `getTransformation().encrypt`, registers a
  `PacketResponse` in `sendQueue` with `maxTries = 30` for resendable types
  (`COMMAND.canResend()` is true), calls `writeNetworkPacket` (the
  `DatagramSocket` send) and then loops
  `while (retries < maxTries && getState() != DISCONNECTED) { response.waitForAcknowledgement(1000L) }`.
  So one command write can block for about 30 s; it exits early once the state is
  `DISCONNECTED`; the 1 s acknowledgement wait is a `CompletableFuture.get` and is
  interruptible; an `InterruptedException` is wrapped into a `RuntimeException`.
- `LocalTeamspeakClientSocket.close()` is only `DatagramSocket.close()` plus
  `commandExecutionService.shutdown()`. It does not change the connection state,
  does not interrupt the reader/handler threads and does not join them.
- `setState(DISCONNECTED)` sets `reading = false`, which interrupts the
  `TS3J/NetworkReader` and `TS3J/NetworkHandler` threads, and calls `onDisconnect`,
  which takes the socket's `commandSendLock` to fail and clear awaiting commands.
- `NetworkReader.run` exits on `EOFException`/`InterruptedException`/`Throwable`
  and then moves the state to `DISCONNECTED` if it is not already there, so closing
  the `DatagramSocket` converges the state without any extra help.
- `setMicrophone(null)` calls `gracefulStop()` on the microphone timer and clears
  the field, so voice sending stops on that socket.
- The server commands reach `LocalTeamspeakClientSocket.process` through
  `LocalClientHandlerFull`/`LocalClientHandlerRetrievingData`, which is where this
  module observes `notifyclientleftview` with `clid == clientId` for the
  confirmation. `Command.build()` escapes parameter values with
  `Ts3String.escape`, so the reason text is escaped by the library and not here.

## How the bounded exit is implemented

The runner sends `clientdisconnect` through ts3j's encoding, encryption,
sequence and reliable ACK path. It observes a matching server self-leave while
sending is still in flight, so a lost ACK cannot hide a confirmed exit.
Every completion and interrupted wait releases the captured transport.

`SocketOperations` registers connection, subscription, channel/liveness calls
and writers before they enter the library's monitors. Closing this socket
interrupts its registered operations, including the writer holding COMMAND's
monitor; queued writers can then leave rather than waiting for a one-second
retry slice. Nested operations retain one ownership record. Registration and
interruption share a lock so a completed call cannot be interrupted after
returning to its next dispatcher task. Flags set by this socket's stop are
cleared when the outer operation exits; pre-existing caller interrupts survive.
The runner waits for registered work and its own workers inside the same deadline.

`StateAwareTeamspeakClientSocket` is extracted into its own file because the
adapter exceeded 300 lines when cancellation/progress handling was added.
`SocketOperations` owns cancellation; session/generation ownership remains in
the facade. The facade's existing listener/diagnostics decomposition remains
separate work in `ARCHITECTURE.md`. No dependency version or lockfile changes
are needed for this approach.

## Remaining evidence limits

The library's monitor is not itself interruptible; its owner is canceled through
the controlled adapter. This relies on the pinned library's interruptible ACK
wait and socket-close behavior, which the real-command-path fixtures exercise.
A future dependency upgrade must recheck those boundaries. Arbitrary external
code that ignores interruption is outside the transport contract.
A handshake with no established local session reports `NO_ACTIVE_SESSION`,
which does not prove that the server never admitted it. Server confirmation
requires a real matching self-leave notification. Physical-device callbacks,
server release and audio-stop timing remain separate acceptance work.

## Tests

- `BoundedDisconnectTest` — the runner's deadline, release ordering, thread
  reclamation and outcome mapping against a controllable target.
- `BoundedSocketDisconnectTest` — the production adapter and the real ts3j socket:
  the command that is written, the confirmations that are accepted, a lost
  acknowledgement, a blocked write and a queued sender, all without opening a
  socket or contacting a server.
- `StateAwareTeamspeakClientSocketTest` — the confirmation and session-state
  primitives.
- `Ts3jSessionClientTest` — session-level capture, release, listener and
  generation isolation, idempotence and the `UNSUPPORTED` default.

These are JVM fixtures. They do not prove that a real server releases the session,
that a phone stops sending audio, or that the task-removal callback runs at all.

## Failure classification compatibility

ConnectionStatus has a defaulted trailing ConnectionFailureCategory. Existing
Kotlin constructor calls remain source-compatible; consumers are rebuilt together,
without an ABI promise for precompiled clients. Category and retryability use the
same cycle-safe cause-chain decision. The first command failure takes precedence:
only the observed CommandProcessException clone-limit text qualifies. No guessed
server error code or generic text exception starts clone-limit recovery.
