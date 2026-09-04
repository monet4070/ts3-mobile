# Changelog

This project follows milestone-style pre-release versioning while core protocol
and audio behavior is still being validated.

## Unreleased

### Added

- High-volume 16-bit voice packet sequence regression coverage.
- Repeated reconnect backoff verification and a 60-second native Opus device soak.
- A privacy-safe M10 physical-device stability and audio test matrix.
- A thread-safe session-generation gate with concurrent stale-callback tests.
- Device coverage that verifies route selection reaches Android's active
  communication device and that playback uses the voice-communication strategy.
- Audio-focus policy and device arbitration regression coverage for transient
  interruptions and recovery.
- A focused channel-restore policy with password-preservation regression cases.
- Protocol session-generation gating that rejects stale ts3j snapshot and voice
  callbacks during reconnect races.
- Atomic connection-attempt status ordering so asynchronous protocol failures
  cannot be overwritten by a racing connected notification.
- A narrow internal ts3j socket port with fake-backed facade race regression tests;
  the public session-client construction remains unchanged.
- An M11 release checklist and privacy-safe local build-environment diagnostic
  covering the required Java, Android SDK and UDP bind prerequisites.

### Fixed

- Removed an accidentally pasted SVG data-URI line from the packet-sequence
  unwrapper test that broke Kotlin compilation.
- The build-environment check no longer aborts on Windows PowerShell 5.1 when
  `java -version` writes its version banner to stderr.

### Changed

- Split the Compose connection, connected-session, channel, participant,
  microphone, diagnostics and top-level screen surfaces into focused files.
- Moved microphone permission, push-to-talk intent, serialized capture lifecycle
  and failure recovery into a focused controller with a unit-tested capture
  decision policy.
- Route remote voice through Android's voice-communication audio strategy so
  earpiece, speaker and communication-device selection affect actual playback.
- Isolated audio-focus signal classification so unknown framework callbacks do
  not unexpectedly mute playback.
- Isolated channel restore decisions from the foreground service so reconnect
  callbacks cannot overwrite a pending password target.
- Bound channel-switch snapshot updates to the socket generation that performed
  the blocking join, preventing an old session from changing a replacement session.

## [0.10.0-m9] - 2026-08-15

### Added

- In-app connection diagnostics with thread-safe counters and a redacted JSON export.
- Protocol event fixtures and mapping regression tests that do not require an online server.
- Repository architecture documentation, decision records, configuration example,
  root AI/maintainability policy, and a Kotlin formatting gate.

### Changed

- Split microphone mode, participant audio settings, protocol mapping, and diagnostics
  into focused production files.
- Sanitized protocol and audio logs so they contain failure types instead of server,
  participant, password, or exception-message data.
- CI now checks Kotlin formatting before tests, Android Lint, and assembly.

## [0.9.0-m8] - 2026-08-10

### Added

- Expandable channel rows with direct member rosters.
- Single-tap expansion and double-tap channel joining.
- Password prompt for protected channels reached by double tap.
- Public-repository license, notices, privacy and security policies,
  contribution guidance, templates, and CI configuration.

### Changed

- The current channel expands automatically after connection or a channel move.
- Channel member rows expose talking, microphone-muted, output-muted, and own
  client states.

## [0.8.0-m7] - 2026-08-09

### Added

- Per-user mute and 0-200% playback gain keyed by stable TeamSpeak identity.

## Earlier milestones

M0-M6 established ts3j protocol connectivity, foreground lifecycle,
bidirectional Opus audio, RNNoise denoising, microphone modes, audio routing,
reconnection, and channel switching.
