# TS3 Mobile

[![Android CI](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/monet4070/ts3-mobile/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/monet4070/ts3-mobile?label=latest%20release)](https://github.com/monet4070/ts3-mobile/releases/latest)

中文文档：[README_ZH.md](README_ZH.md)

TS3 Mobile is an experimental, open-source Android client for TeamSpeak 3,
built on the full client protocol provided by
[Manevolent/ts3j](https://github.com/Manevolent/ts3j).

**[Download the latest APK](https://github.com/monet4070/ts3-mobile/releases/latest)**
(Android 8.0+, about 14 MB). Do not install the old **v0.1** build; it predates
password-protected server support and the fix for the identity-loading crash in
0.11.1-m10. See [Download and install](#download-and-install).

This is an unofficial community project. It is not affiliated with, endorsed
by, or sponsored by TeamSpeak Systems GmbH. TeamSpeak and related names and
marks are the property of their respective owners.

## Project status

The latest M10 maintenance build is **0.11.1-m10**, published as a regular
GitHub release; the app itself is still pre-release quality. It fixes the
Release identity-loading crash reported in issue #3, adds encrypted
connection-form memory and manual-only identity-limit recovery, and improves
the disconnect UI. Existing M10 installations can update in place without
resetting identity.

The next feature proposal prioritizes text chat, named server favorites and
voice activation. See the [feature roadmap (2026-10-23, Chinese)](docs/plans/NEXT_PHASE_2026-10-23_ZH.md).
Channel/private chat, poke, microphone gain/volume gate and named bookmarks are
implemented in the development workspace. This does not change
the published 0.11.1-m10 release.
Tasks, interface contracts and acceptance criteria are in the
[detailed execution plan (Chinese)](docs/plans/FEATURE_EXECUTION_PLAN_2026-10-23_ZH.md).

Feature development and M11 production-beta release acceptance are tracked
separately. The automated build gate and M10 code-hardening baseline are in
place; the unfinished physical-device matrix does not block new feature work. See
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

## Download and install

The current build is **0.11.1-m10**, available from the
[latest release](https://github.com/monet4070/ts3-mobile/releases/latest):

- APK: `ts3-mobile-0.11.1-m10-release.apk` (about 14 MB)
- Android 8.0 (API 26) or newer, with `arm64-v8a`, `armeabi-v7a`, `x86` and
  `x86_64` libraries
- SHA-256 checksum: published in the release notes and in `SHA256SUMS.txt`
- Existing M10 installations can install over the old build; identity and
  settings are preserved

The APK is signed with the M10 development certificate, not a production
release key. A future production-signed build may require uninstalling this
one. Keep another client available on important servers.

The app does not check for updates yet. Follow the
[releases page](https://github.com/monet4070/ts3-mobile/releases) or use
**Watch → Custom → Releases** on GitHub to be notified of new builds.

If you landed on the **v0.1** release page: that is the first experimental
build from August 2026, released before password-protected server support and
before the 0.11.1-m10 identity-loading fix. Use the latest release instead. If
Android refuses to install the new APK over an older build because of a
signature mismatch, uninstall the old app first; uninstalling also removes its
locally stored TeamSpeak identity.

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

### Channel and private text (development)

- Channel / Users / Chat navigation, plain-text messages, long-press copy and unread counts
- Users → user actions → Send private message; switch conversations with the chat header
- Users → user actions → Poke, with an optional message (100 UTF-8 bytes); received pokes show an in-app dialog and private history
- Separate drafts and unread counts; stable identity checks pause offline or ambiguous targets
- Explicit sending, server-accepted, rejected and unknown outcomes; no automatic resend
- Up to 20 conversations sharing 500 messages / 2 MiB in service memory, with 1,024-byte UTF-8 input validation
- Messages and drafts survive ordinary reconnect; manual exit, terminal failure and new manual sessions clear them
- Keyboard-aware composer and compact voice controls; input focus/tab changes release PTT
- Close private conversations to clear their messages and drafts; no chat history on disk, text notifications or read receipts

See [channel checks](docs/testing/F1A_CHANNEL_CHAT_2026-10-23.md) and
[private conversation checks](docs/testing/F1B_PRIVATE_CHAT_2026-10-23.md).
Poke implementation and offline checks are tracked [separately](docs/testing/POKE_2026-10-23.md).
Channel interoperability passed on the Android 16 Debug package; private and
Release text interoperability remain unverified, as does live poke interoperability. See [phone results](docs/testing/F1A_DEVICE_2026-10-23.md).

### Bookmarks, presence and channel discovery (development)

- More options → Server bookmarks: name/edit/delete up to 50 servers, optional
  encrypted server/channel passwords, nickname, default channel ID, fill form and quick connect
- Disconnect from the current server before selecting another bookmark to connect
- More options → Away and status: away/message and confirmed server-visible input/output mute
- Channel information buttons: topic, plain-text description, users and configured user limit
- More options → Find channels and users: local name/topic/nickname search and join actions

Bookmark storage is separate from history, form memory and identity. New installs
start empty; loading does not connect. Clearing a bookmark password does not clear
independent form memory. Quiet gate listening and released PTT remain available
rather than toggling the server mute flag every frame. These features have offline
checks; real-server presence and channel-info permissions remain unverified. See
[delivery record](docs/testing/FEATURES_134_2026-10-23.md).
The subsequent [workspace review](docs/testing/REVIEW_2026-10-24.md) covers session
replacement, restored-chat availability, permission synchronization and shared-identity UI fixes.
The current requested scope is bookmarks, presence and channel discovery; identity
import/export, friends/block lists, moderation, connection-quality dashboards and
file transfer are outside it.

### Voice and audio

The [audio review repair plan](docs/plans/AUDIO_REVIEW_FIXES_2026-10-24_ZH.md)
tracks playback recovery, capture shutdown and route-boundary regressions.
The [follow-up](docs/plans/AUDIO_REVIEW_FOLLOWUP_2026-10-24_ZH.md) covers failure
guidance, worker-owned retirement and processing continuity during volume updates.
Physical route/acoustic acceptance remains pending.

- OPUS_VOICE and OPUS_MUSIC receive support through official libopus
- per-speaker jitter buffers, packet-loss concealment and PCM mixing
- foreground playback with speaker mute and per-user mute controls
- per-user 0–200% playback gain keyed by stable TeamSpeak identity
- off, push-to-talk, continuous and RMS volume-gate microphone modes
- 48 kHz mono, 20 ms, 64 kbps Opus voice encoding and ts3j voice transmission
- constrained VBR, fullband Opus, acoustic echo cancellation and RNNoise v0.2
- microphone input volume (0–200%), optional 10 ms RNNoise suppression and device AEC controls
- adjustable volume gate with bounded onset/tail; distinct listening/transmitting states
- Android AGC and platform NS disabled when controllable; actual AEC status shown
- bounded capture and encoded-frame queues to prevent latency growth
- microphone foreground-service activation during capture, including silent gate listening
- communication-device selection for earpiece, speaker, wired, Bluetooth and USB
- automatic fallback to system routing when a selected device is disconnected

Microphone controls: More options → Microphone settings. Gain and processing settings
stay in service memory; restarting the service restores unity gain, suppression/AEC on
and push-to-talk. Gate defaults are offline starting values; acoustic performance
remains unverified. See [implementation and checks](docs/testing/MICROPHONE_2026-10-23.md).

The [audio quality upgrade plan](docs/plans/AUDIO_QUALITY_UPGRADE_2026-10-24_ZH.md)
is now in staged implementation. A0 baseline fixtures and A1 playback continuity
retain output across short gaps, account for partial writes and drain accepted
tail samples before releasing focus. Forced stops still discard immediately.
See [offline checks and remaining acceptance](docs/testing/AUDIO_A0_A1_2026-10-24.md).
A2 adds float gain with 20 ms volume ramps and a -1 dBFS limiter. Mixing preserves
single-talker unity without count-based volume jumps; concurrent voices sum before
bus limiting. See [A2 implementation and checks](docs/testing/AUDIO_A2_2026-10-24.md).
Limiter parameters await device acceptance. A3 provides an opt-in standalone APM
development prototype with bounded render references and synthetic AEC/NS checks;
default builds do not include its native library. See [A3 build and evidence](docs/testing/AUDIO_A3_2026-10-24.md).
Opus is now built from pinned 1.6.1 sources; existing encoder settings are retained,
with old/new mono/stereo/DTX compatibility fixtures. See [A4a verification](docs/testing/AUDIO_A4A_2026-10-24.md).
A4b bounds global input residence to 60 ms, shares a 240 ms per-speaker media
budget and adapts startup buffering within 40–120 ms. FEC requires confirmed
framing and actual redundancy; unknown TS3 framing retains bounded PLC.
See [A4b implementation and offline traces](docs/testing/AUDIO_A4B_2026-10-24.md).
Live APM capture integration and physical-route acceptance remain pending;
capture defaults, permissions and protocol formats are unchanged.

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

There is currently no in-app language selector. Whisper transmission, file
transfer, permissions administration and multi-server tabs are not implemented.

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

The app builds BSD-licensed Xiph libopus 1.6.1 from a SHA-256-pinned source archive and vendors the
official Xiph RNNoise v0.2 model at commit `904a876d`. License texts and exact
provenance are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build and verification

Requirements:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android NDK 27.0.12077973
- CMake 3.22.1

The first native build downloads the verified Opus source archive. An offline
archive can be supplied with `-PopusSourceArchive=/absolute/path/opus-1.6.1.tar.gz`;
the same pinned SHA-256 is always required. See [native compatibility checks](CONTRIBUTING.md#opus-source-and-compatibility).

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
