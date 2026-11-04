# ADR-0013: Bounded, session-owned channel text

Date: 2026-10-23. Status: accepted for F1a development.

F1a adds current-channel text only. The protocol stays JVM-only; the service
owns volatile chat state and Compose receives read-only state and narrow actions.
Existing identity, form/history storage, reconnect policy and audio ownership stay
unchanged. New listener methods have empty defaults and unsupported send ports
return Unsupported, preserving source compatibility for existing fakes/adapters.

## Pinned protocol findings

The pinned db57d60 ts3j uses targetmode=2 and an explicit channel target. Its
command queue is FIFO and timeout does not remove a queued command. A server
success response means Accepted, never delivered/read. Uncertain submission,
timeout, interruption and transport loss mean Unknown; there is no automatic
retry or migration to another socket. Incoming channel events can lack a reliable
channel target; the UI must retain unknown attribution.

CommandSingleParameter trims values before escaping. Text therefore needs a
small parameter override using the pinned Ts3String.escape exactly once, without
trimming. No dependency/fork change is needed.

A per-adapter submission lock orders channel moves and text command enqueueing.
It is released before waiting for command responses. The five-second target
covers lock acquisition, executeCommand (including its synchronous reliable
write) and response waiting on one monotonic deadline. A dedicated registered
worker and an atomic pre-submission claim bound caller waiting even if the upstream command monitor is busy.
Interruption cannot revoke commands already accepted by ts3j. A socket permits
at most one live text worker; close/SocketOperations interrupts and reclaims its
blocking work. The deadline is a local outcome boundary, not a strict network
cutoff. Late completion never changes Unknown.

## Decomposition assessment

Ts3jSessionClient, ConnectionCoordinator and TeamSpeakService already exceed
300 lines. Keep only protocol forwarding, active-listener binding and component
wiring there. New mapper/commands, ChatStore/SessionChatController and Compose
chat components own their separate responsibilities. Preserve existing public
facades; extracting the general connection listener and audio controls remains
follow-up work recorded in ARCHITECTURE.md.

## Privacy and remaining interoperability

Bodies, drafts and sender names stay in bounded service memory, outside
SavedState, logs, diagnostics and stores. Text-bearing models redact toString.
Explicit copying exports only the selected body to Android's clipboard.
Server-specific echo, target interpretation and rejection codes require later
real-server interoperability checks; deterministic tests use the pinned encoder,
command queue and synthetic responses, without credentials or a server.

## UI and compatibility

The composer remains multiline with the keyboard closed and uses a two-line
viewport while the IME is open (the draft can still contain newlines). It hides
normal hint text and floating labels in that compact layout, keeping large-font
input visible. During typing the app/session headers and chat navigation give
space to input; dismissing the keyboard restores them. Target context and PTT/stop
remain visible. MainActivity explicitly uses adjustResize for older Android IME
insets. No new background capture, microphone mode or storage schema is introduced.
