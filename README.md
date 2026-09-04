# TS3 Mobile

TS3 Mobile is an experimental, open-source Android client for TeamSpeak 3,
built on the full client protocol provided by
[Manevolent/ts3j](https://github.com/Manevolent/ts3j).

This is an unofficial community project. It is not affiliated with, endorsed
by, or sponsored by TeamSpeak Systems GmbH. TeamSpeak and related names and
marks are the property of their respective owners.

## Current milestone: M11 production-beta preparation

M10 code hardening is in place, while its physical-device acceptance matrix is
still partially deferred. M11 focuses on reproducible build gates, release
evidence and production-beta readiness; see the
[M11 release checklist](docs/release/M11_RELEASE_CHECKLIST.md).

The current build provides:

- TeamSpeak identity generation and AES-GCM storage backed by Android Keystore
- foreground UDP connection service with a notification disconnect action
- server address, port, password, and nickname input
- connection, cancellation, disconnection, and error states
- hierarchical channel view with direct channel-member rosters
- single-tap channel expansion and double-tap channel joining
- public and password-protected channel switching with current-channel state
- OPUS_VOICE and OPUS_MUSIC receive support through official libopus
- per-speaker jitter buffers, packet-loss concealment, and PCM mixing
- foreground playback with a speaker mute control
- always-off, push-to-talk, and continuous microphone modes
- 48 kHz mono, 20 ms, 64 kbps Opus voice encoding and ts3j voice transmission
- constrained VBR, fullband Opus, acoustic echo cancellation, and RNNoise v0.2
- always-on 10 ms neural denoising with Android automatic gain control disabled
- bounded capture and encoded-frame queues to prevent latency growth
- microphone foreground-service activation only while transmitting
- communication-device selection for earpiece, speaker, wired, Bluetooth, and USB
- automatic fallback to system routing when a selected device is disconnected
- cancellable automatic reconnection with network-aware 1-30 second backoff
- stale-session callback rejection and in-memory restoration of the last channel
- per-user mute and 0-200% playback gain keyed by stable TeamSpeak identity
- an in-app diagnostics tab with thread-safe connection/audio counters
- a redacted JSON export that excludes server, password, nickname, channel, and participant data
- fixture-based protocol mapping regression tests and sanitized failure logging

Whisper transmission, automatic voice activation, text chat, file transfer,
permissions administration, bookmarks, and multi-server tabs are not
implemented. Treat the app as pre-release software and keep another client
available when testing on important servers.

## Privacy

The project includes no analytics, advertising, telemetry, crash-reporting SDK,
or project-operated backend. The app connects directly to servers selected by
the user. The TeamSpeak identity is encrypted locally using Android Keystore.
Connection details are kept in app/service memory while needed by the
connection workflow; they are not deliberately persisted. A password used to
restore a channel remains in the foreground service's memory only.

See [PRIVACY.md](PRIVACY.md) for the complete data-handling statement.

## Modules

- `app`: Compose UI, Android lifecycle, foreground service, and identity vault
- `ts3-protocol`: JVM-only ts3j facade, models, session generation, and channel ordering
- `audio-opus`: JNI libopus codec, capture, denoising, jitter buffering, mixing,
  and Android audio I/O

The compatibility layer pins ts3j commit
`db57d60c989e399626aa16d921390f5033e6cdeb` through JitPack. Every module's
dependency graph is locked to committed `gradle.lockfile`s in STRICT mode,
and ts3j's transitive dependencies (bcprov-jdk15on, commons-lang, dnsjava,
ini4j) are additionally capped by explicit Gradle constraints, so supply-chain
drift surfaces as a lockfile diff instead of a silent version change. Version
bumps must run the build with `--write-locks` and commit the lockfile diff. A
maintained fork of ts3j is still required before a stable product release —
see [ADR-0004](docs/decisions/0004-take-ownership-of-ts3j-dependency.md) for
the plan and current status.

The app uses the BSD-licensed Xiph libopus 1.3.1 Prefab package and vendors the
official Xiph RNNoise v0.2 model at commit `904a876d`. License texts and exact
provenance are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build

Requirements:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1

Set `sdk.dir` in an untracked `local.properties`, then run:

```powershell
.\tools\check-build-environment.ps1
```

Then run the automated build gate:

```powershell
.\gradlew.bat :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Formatting gate:

```powershell
.\gradlew.bat quality
```

With a physical Android device connected, run the native codec test with:

```powershell
.\gradlew.bat :audio-opus:connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions runs the JVM/unit tests, lint, and debug build for every pull
request and push to `main`.

## Android support

The APK remains installable on Android 8.0 (API 26) and newer. The beta quality
target is Android 10 and newer; Android 8/9 receive compatibility fixes when
practical but are not part of the primary physical-device matrix yet.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change and follow
the [Code of Conduct](CODE_OF_CONDUCT.md). Do not open a public issue for a
suspected vulnerability; use the process in [SECURITY.md](SECURITY.md).

## License

TS3 Mobile is licensed under the [Apache License 2.0](LICENSE). Components from
other projects remain under their respective licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The M10 implementation baseline is in place, including audio-focus recovery,
communication-device routing, password-channel restore protection, stale-session
gating, and redacted diagnostics. The physical-device acceptance matrix is
tracked in [docs/testing/M10_STABILITY_MATRIX.md](docs/testing/M10_STABILITY_MATRIX.md).
Long-running duplex/background runs, physical Bluetooth and hot-plug coverage,
password-channel device coverage, and a second Android generation are
intentionally deferred for a later test session. Automatic voice activation
remains outside the current microphone-mode scope until its false-trigger and
latency behavior can be measured.
