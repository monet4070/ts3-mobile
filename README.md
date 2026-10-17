# TS3 Mobile

[![Android CI](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml)

中文文档：[README_ZH.md](README_ZH.md)

TS3 Mobile is an experimental, open-source Android client for TeamSpeak 3,
built on the full client protocol provided by
[Manevolent/ts3j](https://github.com/Manevolent/ts3j).

This is an unofficial community project. It is not affiliated with, endorsed
by, or sponsored by TeamSpeak Systems GmbH. TeamSpeak and related names and
marks are the property of their respective owners.

## Project status

The latest M10 maintenance pre-release is **0.11.1-m10**. It fixes the Release
identity-loading crash reported in issue #3, adds encrypted connection-form
memory and manual-only identity-limit recovery, and improves the disconnect UI.
Existing M10 installations can update in place without resetting identity.

Development is currently focused on the M11 production-beta preparation
milestone. The automated build gate and the M10 code-hardening baseline are in
place, but the complete physical-device acceptance matrix is not finished. See
the [continuation plan](docs/release/CONTINUATION_PLAN.md), the
[M11 release checklist](docs/release/M11_RELEASE_CHECKLIST.md) and the
[M10 stability matrix](docs/testing/M10_STABILITY_MATRIX.md) for the remaining
release evidence.

A short Android 16 device smoke test has verified password-protected server
connection, manual disconnect and reconnect, channel and participant loading,
the foreground service and notification, and both English and Simplified
Chinese resources. The native device suite also passes all Opus, RNNoise,
audio-focus and route-selection checks. This does not replace the outstanding
long-duration, physical Bluetooth/hot-plug, password-channel restore or
second-device testing.

Treat the app as pre-release software and keep another client available when
testing on important servers.

## Features

### Connection and sessions

- TeamSpeak identity generation with AES-GCM storage backed by Android Keystore
- foreground UDP connection service with a notification disconnect action
- server address, port, TeamSpeak server password and nickname input, restored from an encrypted local snapshot
- up to ten successfully connected addresses and ports, with recent-address prefill and individual deletion
- correct TeamSpeak Base64(SHA1) server-password authentication
- connection, cancellation, disconnection and error states
- cancellable automatic reconnection with network-aware 1–30 second backoff
- active session exit when Android delivers a task-removal callback, with a two-second protocol budget
- manual retry guidance after an identity connection-limit refusal; no automatic retry
- default-network identity and blocked-state tracking, with a bounded control-response check on network switches
- stale-session callback rejection and in-memory restoration of the last channel
- hierarchical channel view with direct channel-member rosters
- single-tap channel expansion and double-tap channel joining
- public and password-protected channel switching with current-channel state

### Voice and audio

- OPUS_VOICE and OPUS_MUSIC receive support through official libopus
- per-speaker jitter buffers, packet-loss concealment and PCM mixing
- foreground playback with speaker mute and per-user mute controls
- per-user 0–200% playback gain keyed by stable TeamSpeak identity
- always-off, push-to-talk and continuous microphone modes
- 48 kHz mono, 20 ms, 64 kbps Opus voice encoding and ts3j voice transmission
- constrained VBR, fullband Opus, acoustic echo cancellation and RNNoise v0.2
- always-on 10 ms neural denoising with Android automatic gain control disabled
- bounded capture and encoded-frame queues to prevent latency growth
- microphone foreground-service activation only while transmitting
- communication-device selection for earpiece, speaker, wired, Bluetooth and USB
- automatic fallback to system routing when a selected device is disconnected

### Interface and diagnostics

- TailSync-inspired warm cream visual style with terracotta accents, compact cards
  and licensed Lucide outline icons

- English UI by default, with Simplified Chinese selected automatically for a
  Chinese system or app locale
- localized service status, notification, audio-route and error messages
- diagnostics and background voice checks in the secondary More options menu
- a labeled, accented Disconnect button beside the server information
- background voice runtime check with an Android battery-optimization entry, a system app-settings entry, and an optional, session-scoped CPU wake-lock request that the system or a vendor battery policy can disable
- a bounded local event journal and Android process-exit reasons that survive process recreation
- background reconnection restores listening; stopped microphone capture resumes when the app is visible
- silent protocol transport closures trigger recovery even when the default network is unchanged; failed retries share one recovery wake-lock budget
- redacted JSON export that excludes server, password, nickname, channel and
  participant data
- fixture-based protocol mapping regression tests and sanitized failure logging

There is currently no in-app language selector. Whisper transmission, automatic
voice activation, text chat, file transfer, permissions administration,
bookmarks and multi-server tabs are also not implemented.

## Removing the task

Removing the app from recents requests a disconnect if Android delivers the
callback. Home, app switching and locking the screen keep the existing background
session. The exit result distinguishes a request sent from server-confirmed
release; if the network is unavailable, the server may retain the session until
its own timeout. Force stop and vendor process killing may provide no callback.
Reopening the app does not connect automatically.

On the tested Xiaomi / Android 16 device, launcher swipe removal kills the process
before delivering the callback to a newly created service. That service cannot
disconnect the lost socket, even with unrestricted battery policy or a locked
recent task. Use the app or notification disconnect action before removal for a
server-confirmed exit. See the [device report](docs/testing/TASK_REMOVAL_DEVICE_2026-10-17.md).

If the server refuses an identity connection limit, the app stops retrying and
explains leftover sessions and other clients using that identity. Wait at least
60 seconds before manually reconnecting; this is guidance, not a guaranteed
server cleanup deadline. Repeated attempts may trigger a temporary server ban.
Ordinary network interruption recovery remains automatic. Disconnect with the
app or notification before swiping the app away. The complete localized guidance
scrolls with the form. See [ADR-0011](docs/decisions/0011-manual-refusal-and-form-memory.md).

Local regressions and a debug build cover the implementation. Callback delivery,
actual microphone stop time and server release still require device acceptance:
see [the review and device matrix](docs/testing/TASK_REMOVAL_DISCONNECT_REVIEW_2026-10-13.md).

## Privacy

The project includes no analytics, advertising, telemetry, crash-reporting SDK
or project-operated backend. The app connects directly to servers selected by
the user. The TeamSpeak identity is encrypted locally using Android Keystore.
Up to ten successful server addresses and ports are stored as history. The last
connection form (address, port, nickname and server password) is saved separately
with AES-GCM and an Android Keystore key, excluded from Android backup. Edits
are saved after a short delay; connecting waits for a save attempt. A
password used to restore a channel remains in the foreground service's memory
only. Local diagnostics retain at most 128 events with fixed categories and
numeric system state; they exclude connection details and audio.

See [PRIVACY.md](PRIVACY.md) for the complete data-handling statement.

## Architecture

- `app`: Compose UI, Android lifecycle, foreground service and identity vault
- `ts3-protocol`: JVM-only ts3j facade, models, session generation and channel ordering
- `audio-opus`: JNI libopus codec, capture, denoising, jitter buffering, mixing
  and Android audio I/O

The Android interface depends on the protocol and audio adapters; the JVM
protocol module does not depend on Android. Service state is exposed to Compose
as immutable `StateFlow` data, and user-facing messages are resolved at the
Android boundary. See [ARCHITECTURE.md](ARCHITECTURE.md) and the records under
[`docs/decisions`](docs/decisions/) for the detailed boundaries and rationale.

The compatibility layer pins ts3j commit
`db57d60c989e399626aa16d921390f5033e6cdeb` through JitPack. Every module's
dependency graph is locked to committed `gradle.lockfile` files in STRICT mode,
and ts3j's transitive dependencies are additionally capped by explicit Gradle
constraints. Version bumps must run the build with `--write-locks` and include
the reviewed lockfile diff. A maintained fork of ts3j is still required before
a stable product release; see
[ADR-0004](docs/decisions/0004-take-ownership-of-ts3j-dependency.md).

The app uses the BSD-licensed Xiph libopus 1.3.1 Prefab package and vendors the
official Xiph RNNoise v0.2 model at commit `904a876d`. License texts and exact
provenance are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build and verification

Requirements:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1

Set `sdk.dir` in an untracked `local.properties`, then verify the local setup:

```powershell
.\tools\check-build-environment.ps1
```

Run the complete automated gate:

```powershell
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace
.\tools\check-repository-privacy.ps1
```

With a booted Android emulator or an authorized device, verify identity loading
in the minified release APK (JDK 17 and PowerShell 7; set `ANDROID_HOME` or pass
`-SdkRoot` with your SDK path):

```powershell
.\tools\check-release-identity.ps1 -DeviceSerial emulator-5554
```

Use the serial reported by `adb devices`. The check loads the APK without installing
it and tests temporary identity reloads and INI parse/store entirely offline.
Its separate probe cannot change R8's reachability analysis. Output is saved under
`build/release-identity-smoke/`. See the [issue #3 report](docs/testing/ISSUE_3_RELEASE_IDENTITY_FIX.md).

With an authorized physical Android device connected, run the native device
suite:

```powershell
.\gradlew.bat :audio-opus:connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
For opt-in microphone signal comparisons and the latest device findings, see
[the audio diagnosis record](docs/testing/AUDIO_QUALITY_DIAGNOSIS_2026-10-13.md).
GitHub Actions runs the privacy scan, formatting checks, JVM/unit tests, Android
Lint, debug/release assembly and the minified identity smoke check for every pull
request and push to `main`.

## Android support

The APK remains installable on Android 8.0 (API 26) and newer. The beta quality
target is Android 10 and newer; Android 8/9 receive compatibility fixes when
practical but are not part of the primary physical-device matrix.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a change and follow
the [Code of Conduct](CODE_OF_CONDUCT.md). Do not open a public issue for a
suspected vulnerability; use the process in [SECURITY.md](SECURITY.md).

Thanks to [Rupert Polley (@polleyr)](https://github.com/polleyr) for reporting
and fixing TeamSpeak server-password authentication in
[PR #1](https://github.com/monet4070/ts3-mobile/pull/1), and for the English
localization work that informed the current resource-based implementation.

See [CHANGELOG.md](CHANGELOG.md) for milestone history and unreleased changes.

## License

TS3 Mobile is licensed under the [Apache License 2.0](LICENSE). Components from
other projects remain under their respective licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
