# 0007: Harden background voice and remember successful endpoints

**Status:** Accepted

**Date:** 2026-10-08

## Context

Background disconnect reports need evidence that survives process death. A
Boolean network signal misses Wi-Fi/mobile handovers, and the voice service's
dataSync type adds an Android 15 time limit unrelated to an ongoing voice
session. Re-entering a server address also makes the connection page cumbersome.

## Decision

- Keep the existing foreground service, protocol and reconnect engine. Use
  mediaPlayback for listening and add microphone only for actual capture.
- Observe the default network's identity, INTERNET capability, blocked state
  and generation. Ignore callbacks from superseded networks. Check protocol
  control responses on an identity change before reconnecting; voice silence
  is never a failure signal. Session generations reject stale results.
- Observe the pinned ts3j transport's silent watchdog closure once per second,
  using a per-attempt volatile closure latch. No extra keepalives or voice-based
  timeout are introduced. Pending server disconnects and failures already
  reported by callbacks suppress this fallback so kick/ban semantics survive.
  Listener claiming and session/epoch checks reject duplicate or stale results.
- Offer an optional CPU-awake setting in the background runtime check, off by
  default pending device/battery evidence. Connected, network-usable sessions
  renew timed leases. Connecting/recovery gets a two-minute total budget;
  individual retries and network flaps do not renew that budget. Network loss,
  terminal exit, explicit disconnect and service destruction release the lock.
  Transient ERROR releases the lock but retains the recovery start time. Only
  a new manual connection, a confirmed live session or disconnection resets it.
  Timed-lock expiry during release is tolerated only when the lock is no longer held.
  A wake lock does not bypass Doze. Link to the system battery settings rather
  than requesting exemptions at first launch.
- Preserve already-running continuous capture in the background. Once capture
  stops, start it only with the app visible. Background reconnection restores
  listening and explains how to resume the microphone. PTT still releases on
  activity pause/stop; the notification can turn continuous transmission off.
- Save at most ten successful endpoints as host/port only. Service-side success
  records work even with no activity bound. DataStore writes are serialized;
  failed/canceled connections never create history. Restore the latest address
  once on launch without overwriting edits, offer selection/deletion, and clear
  a previous password when selecting another endpoint.
- Store at most 128 fixed-category diagnostic events in app-private,
  non-backed-up storage. Include service lifetime, foreground types, network,
  power flags, failures, microphone state and previous Android process-exit
  reason. Do not store addresses, exception text, passwords, identity or audio.

## Compatibility and migration

This narrowly supersedes ADR-0002's in-memory-only endpoint rule. The client
still has one active server; recent endpoints are not named bookmarks or
multi-server sessions. Existing users begin with an empty history and the
CPU-awake preference disabled. Password and identity storage formats do not
change. The diagnostic report becomes v2 and contains the existing v1 counter
object plus a bounded event journal. Existing protocol implementations keep
compatible defaults for the optional liveness operation.
The optional nullable transport-state property also has a compatible default;
implementations that cannot distinguish pending callbacks return null. No
dependency upgrade or stored-data migration is required for watchdog recovery.

START_NOT_STICKY remains deliberate: no evidence currently justifies saving
credentials or reviving a service after an explicit system stop. Core-Telecom
and a PTT overlay remain independent future changes.

## Decomposition review

TeamSpeakService and ConnectionCoordinator already exceed the preferred
300-line boundary. This change extracts foreground notification/type handling
into SessionNotifications, and power, diagnostics and network observation into
focused collaborators. The service keeps component wiring and binder methods;
the coordinator keeps epoch ownership. Future binder/listener extraction must
preserve the published state and stale-callback guards and should be separate
from this reliability change. Native audio files are unaffected.

## Validation

Use the repository quality, JVM test, Android Lint, debug-build and privacy
gates. The device scenarios and Doze commands are recorded in
[BACKGROUND_VOICE_ACCEPTANCE.md](../testing/BACKGROUND_VOICE_ACCEPTANCE.md).
Automated results cannot establish OEM survival, wake-lock battery cost or
Android microphone eligibility on physical hardware.
