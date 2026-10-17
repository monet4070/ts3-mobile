# 0011: Manual retry after refusal, encrypted form memory and talkspurt readiness

**Status:** Accepted

**Date:** 2026-10-18

## Context

On the tested Xiaomi device, launcher task removal can kill the socket owner
before a callback is delivered. An old session can therefore remain registered
until the server times it out. The former 1/2/4/8-second clone-limit retries,
combined with manual reconnects, contributed to a burst of seven attempts in
28.82 seconds. A temporary ban was reported and later cleared; its exact server
rule was not captured. Repeating live connections is not an appropriate test.

The user requested manual retry after refusal, secondary diagnostics, a visible
disconnect action, and persistence of nickname, endpoint and server password.
The user also explicitly included the voice queue gap bug in this scope.

## Decision

- Supersede ADR-0009's automatic clone-limit recovery with a terminal refusal.
  Close the refused attempt, stop automatic recovery and preserve its message.
  Recommend waiting at least 60 seconds and then manually retrying. This is not
  a timer, an enforced cooldown or a promise about server cleanup/ban duration.
  Other command refusals remain terminal; ordinary network recovery retains
  its existing policy. Do not invent undocumented ban error codes.
- Put diagnostics/background checks in More options. Put a labeled, accented
  Disconnect action beside the server information. Keep the full localized
  connection-limit instructions scrollable, including explicit disconnect
  before task removal. No UI change can guarantee vendor callback delivery.
- Extend ADR-0002's persistence scope with one last-edited connection form,
  including server password, under the user's explicit request. Encrypt the
  entire versioned snapshot with AES-GCM and a separate Android Keystore key in
  app-private DataStore. Exclude it from cloud backup and device transfer.
  Keep the existing identity and successful-server history formats unchanged.
- Merge delayed loads per field, preserve user edits, and never restore a saved
  password onto another endpoint. Endpoint changes clear the old password.
  Serialize saves, conflate rapid edits with a short delay, and await a save
  attempt before connecting. Display storage errors without blocking connection.
- Separate active transmission from encoded queue readiness. The pinned ts3j
  sender skips an empty provided frame while ready, but emits its end marker
  when readiness becomes false. Queue gaps retain active readiness; capture
  stop ends it. A source exception reports one failure instead of disappearing.

## Migration and compatibility

An absent encrypted form falls back to the old successful endpoint history;
there is no previous nickname/password to migrate. No new identity is generated
by this change. New snapshot decoding rejects unknown versions and malformed
payloads, reporting a visible storage error. Clearing fields overwrites their
saved values; clearing app storage or uninstalling removes the snapshot and key.
Deleting an endpoint history entry does not delete the separate form snapshot.

`EncodedVoiceSource.isTransmitting` has a default based on existing `isReady()`;
existing source implementations remain source compatible. All repository
modules are rebuilt together; binary compatibility with separately compiled
implementations is not promised. `OpusMicrophoneCapture` overrides the property.
Historical clone-recovery journal enum values remain readable, but no new
clone-recovery events are emitted.

## Verification and limits

Use fake protocol sockets and virtual time for terminal refusal, manual retry,
late callbacks and ordinary network recovery. Exercise the actual pinned ts3j
sender against fake datagrams to prove that queue gaps send no end marker and
explicit stop sends exactly one. Device tests use an isolated application ID,
fixture credentials and no server connections to verify encryption and UI
restoration. See the [implementation report](../testing/MANUAL_RETRY_MEMORY_VOICE_2026-10-18.md).

A process killed before a delayed edit save completes can lose that last edit.
Keystore loss makes existing ciphertext unreadable; errors are surfaced. The
form snapshot is not a password manager and has no per-server password list.
Physical Bluetooth, long-running voice quality and OEM task-killing behavior
still require their separate acceptance matrix.
