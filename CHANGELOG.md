# Changelog

This project follows milestone-style pre-release versioning while core protocol
and audio behavior is still being validated.

## Unreleased

## 0.11.1-m10 — 2026-10-18

- Fix the minified Release crash when reading an existing TeamSpeak identity
  (#3), preserving ini4j reflection constructors and adding an actual APK smoke
  check to CI. No identity reset or app-data deletion is required.
- Preserve active voice transmission across temporarily empty encoded queues.
- Stop automatic retries after identity connection-limit refusals; show localized
  wait/manual-retry guidance and keep ordinary network recovery automatic.
- Prioritize server exit before audio cleanup, preserving server-confirmed results.
  OEM task killing can still bypass task-removal callbacks: disconnect explicitly.
- Remember the last address, port, nickname and server password with a separate
  Keystore-backed AES-GCM snapshot, excluded from Android backup/transfer.
- Refresh the warm cream UI, emphasize Disconnect next to the server and move
  diagnostics/background checks into More options.
- Include successful endpoint history, network-switch/silent-loss recovery,
  bounded background power handling and privacy-safe persistent diagnostics
  added since the previous published M10 build.
- Local verification: 261 JVM tests, 28 offline UI tests, minified APK identity/INI
  smoke and two live-server Release connections/server-confirmed exits on Android 16.
  Actual voice listening, long-duration Bluetooth and Android 14 acceptance remain open.

## Earlier milestone development notes

### Added

- A warm cream Compose interface with terracotta accents and licensed Lucide
  outline icons, retaining server history, background voice checks and the
  microphone-paused state from the latest connection implementation.

- English as the default UI language with Simplified Chinese kept under
  `values-zh/`. All user-facing text now lives in string resources, and the
  service and audio layers emit typed `UserMessage` / `AudioRoutingError`
  values that are resolved to text at the Android boundary (ADR-0006).
  The English wording follows @polleyr's `fb_localization_english` branch.
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

- Keep a voice talkspurt active across temporary encoded-frame queue gaps; only
  an explicit capture stop emits the end marker. Exercise the actual pinned
  ts3j sender with offline datagram fixtures.

- Send the captured session's exit before audio cleanup or session-mutex waiting
  consumes its budget; preserve server confirmation when later cleanup times out
  or fails. Xiaomi Android 16 testing confirms that launcher process killing can
  still precede the task-removal callback; this is not a guaranteed swipe exit.

- Release connection failure while reading a TeamSpeak identity: preserve ini4j's
  reflective parser, builder and formatter instead of only its public `Ini` entry
  point (#3). CI now builds release and checks offline identity/INI roundtrips
  against the actual minified APK.

- Password-protected servers rejected the connection because the server password
  was handed to ts3j in plaintext. ts3j forwards `client_server_password`
  verbatim, so it now receives the TeamSpeak Base64(SHA1) representation.
  Thanks to @polleyr for the report and fix (#1).
- Removed an accidentally pasted SVG data-URI line from the packet-sequence
  unwrapper test that broke Kotlin compilation.
- The build-environment check no longer aborts on Windows PowerShell 5.1 when
  `java -version` writes its version banner to stderr.
- The repository privacy scan no longer fails when a tracked file is deleted
  in the working tree but not yet committed.

### Changed

- Stop automatic retries after identity connection-limit refusals. Localized
  guidance recommends waiting at least 60 seconds before manual retry, explains
  temporary bans from repeated attempts, and recommends explicit disconnect
  before task removal. Ordinary network recovery remains automatic.
- Move diagnostics/background checks to More options; emphasize Disconnect to
  the right of the connected server information.
- Restore the last address, port, nickname and server password from a separate
  Keystore-backed AES-GCM form snapshot, excluded from backup. Existing identity
  and successful endpoint history formats remain unchanged.

- Completed the architecture decomposition plan for the oversized first-party
  files: the reconnect loop and channel-restore bookkeeping moved out of
  ConnectionCoordinator into ReconnectEngine and ChannelRestoreTracker
  (678 to 568 lines), ts3j event-to-snapshot mapping moved into
  Ts3jEventAdapter (Ts3jSessionClient 487 to 405 lines), and the microphone
  effect chain and measurement bookkeeping moved into MicrophoneAudioEffects
  and CaptureSessionStats (OpusMicrophoneCapture 373 to 275 lines, now below
  the review boundary). All existing tests pass unchanged; the tracker and
  stats gained dedicated JVM tests.
- Extracted the per-talker jitter/reordering/concealment pipeline from
  OpusAudioPlayer into a JVM-testable TalkerJitterPipeline behind an
  OpusPacketDecoder port (the native decoder remains the production
  implementation); OpusAudioPlayer dropped from 668 to 492 lines with frame
  timing unchanged, and the reordering, duplicate/stale rejection,
  initial-hold gating, talkspurt-finish and packet-concealment behavior now
  has fake-decoder unit coverage.
- Extracted the connection/reconnect state machine from TeamSpeakService into
  a JVM-testable ConnectionCoordinator behind an internal host seam, per
  ADR-0005; TeamSpeakService dropped from 1018 to 494 lines and now owns only
  the binder, notification, participant settings and components. The
  coordinator is covered by new fake-host unit tests for stale-callback
  rejection, user disconnect, network-driven reconnect and terminal failure.
- Consolidated the duplicated sanitized failure-type logging helper into the
  protocol module as a public API; the audio module now imports the single
  implementation instead of maintaining a byte-identical copy.
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
