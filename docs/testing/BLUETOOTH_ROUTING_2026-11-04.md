# Bluetooth routing protection — 2026-11-04

## Scope and findings

The previous implementation did not automatically select Bluetooth at startup or
hot-plug. Removing a selected route cleared communication-device selection without
muting; actual-output changes rebuilt playback and allowed new voice on the system
fallback. Unexpected phone-speaker playback was therefore possible.

[ADR 0020](../decisions/0020-bluetooth-playback-protection.md) records the replacement
policy and the extraction assessment for the 317-line original router. Startup and
newly available Bluetooth communication devices now trigger selection. Removal,
switch failure, established Bluetooth loss and confirmation timeout latch listening
mute. Actual-output protection persists across talkspurts and rejects stale output
callbacks after an explicit selection. Pending routes discard old voice. A manual
unmute retries a selected Bluetooth route; a refused retry stays muted.

Reconnecting selects Bluetooth but cannot clear listening mute. Microphone mode is
unchanged. No protocol, configuration, dependency, permission or storage changes.
Existing uncommitted work elsewhere in this checkout was retained.

## Changed areas

- Production: router/platform/device classification/monitor and actual-playback
  guard in `audio-opus`; player/output/session integration; the service's routing
  and manual-unmute paths; localized route-loss guidance in the app.
- Tests: independent router fake-platform tests and playback-guard JVM tests;
  route-readiness/session regressions; an actual-speaker/desired-Bluetooth emulator
  regression and updated startup-selection expectations.
- Documentation: English/Chinese README, user guide and troubleshooting text;
  architecture, ADR and current hot-plug matrix expectations.
- Configuration: none.

Production files in `audio-opus/src/main/java/io/github/ts3mobile/audio/opus/`:
`AudioDeviceRouter.kt`, `AudioRoutePlatform.kt`, `AudioRouteDevices.kt`,
`BluetoothRouteMonitor.kt`, `BluetoothPlaybackGuard.kt`, `AudioRouting.kt`,
`OpusAudioPlayer.kt`, `AndroidPlaybackOutput.kt`, `PlaybackOutput.kt` and
`PlaybackSession.kt`. App integration: `TeamSpeakService.kt`, `UserMessageText.kt`
and `app/src/main/res/values{,-zh}/strings.xml`.

Tests: `AudioDeviceRouterPolicyTest.kt`, `BluetoothPlaybackGuardTest.kt`,
`PlaybackSessionTest.kt`, `FakePlaybackOutput.kt`, `AudioDeviceRouterTest.kt` and
`OpusAudioPlayerFocusTest.kt`, in their respective module test/androidTest sources.
Documentation: `README.md`, `README_ZH.md`, `ARCHITECTURE.md`,
`docs/USER_GUIDE{,_ZH}.md`, `docs/TROUBLESHOOTING{,_ZH}.md`,
`docs/testing/M10_STABILITY_MATRIX.md`, ADR 0020 and this record.

## Executed checks

JDK 17, configured Android SDK, offline Gradle resolution. Final Kotlin formatting
ran separately from checks. Unit-test tasks below used `--rerun` to produce fresh
XML rather than reusing old test results.

```sh
./gradlew :audio-opus:ktlintFormat --offline --max-workers=2
./gradlew quality :audio-opus:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :ts3-protocol:test --rerun :audio-opus:lintDebug :app:lintDebug :app:assembleDebug --offline --max-workers=2
./gradlew :audio-opus:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.audio.opus.OpusAudioPlayerFocusTest,io.github.ts3mobile.audio.opus.AudioDeviceRouterTest,io.github.ts3mobile.audio.opus.PlaybackLifecycleTest,io.github.ts3mobile.audio.opus.AudioFocusInterruptionTest --offline --max-workers=2
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.app.service.MicrophoneControllerTest,io.github.ts3mobile.app.ui.PlaybackFailureTest :app:lintRelease :app:assembleRelease --offline --max-workers=2
pwsh -NoProfile -File ./tools/check-repository-privacy.ps1
git diff --check
```

| Check | Result |
| --- | --- |
| Formatting / repository `quality` | Pass |
| Audio JVM | 183 passed, 0 failures/errors/skips |
| App JVM | 239 passed, 0 failures/errors/skips |
| Protocol JVM | 129 passed, 0 failures/errors/skips |
| Debug Kotlin compilation and assembly | Pass |
| Audio Debug Lint | Pass, no issues |
| App Debug Lint | Pass, 0 errors; advisory version/dependency/plural/unused-resource warnings remain |
| Audio instrumentation, Android 15 / API 35 arm64 emulator | 12 passed |
| App instrumentation, same emulator | 11 passed |
| App Release Lint / minified assembly | Pass; toolchain/dependency R8 advisories remain |
| Privacy scan / whitespace | Pass |
| Local documentation links | Checked after completion of this record |

Logs, copied XML and matching before/after source/configuration SHA-256 manifests
are retained locally under ignored `build/bluetooth-routing-validation/`.
No source changes may be made after the final recorded run without rerunning the
affected checks. These results apply to this working tree, not a published APK.

## Maintainer confirmation and artifact boundary

On 2026-11-04 the maintainer confirmed that the remaining hands-on checks were
completed on a Xiaomi 14, using the previous day’s test package. The local artifact
was located as `build/stable-full-2026-11-03/qa-release.apk` (isolated package
`io.github.ts3mobile.app.stableqa`, version `0.11.1-m10` / code 12). That APK predates
the Bluetooth changes in this record. Preserve the maintainer’s test confirmation
as existing-feature evidence; it does not identify a physical test of today’s new
routing code or the final signed `1.0.0` APK. See the
[release preparation record](../release/1.0.0/PREPARATION_2026-11-04.md).

## Original remaining physical acceptance

No physical phone or Bluetooth headset was connected. The emulator has no Bluetooth
communication route; the desired-Bluetooth/actual-speaker regression uses a synthetic
requested route and the real Android speaker output, with synthetic silent PCM.
It proves software gating, not physical HFP/SCO/BLE establishment or acoustic output.
No TeamSpeak server or credentials are used by these tests.

Verify on physical Android 8–11 and Android 12+ devices, with BLE where supported:

1. Connect before joining and during playback; confirm incoming voice and microphone
   use the Bluetooth communication path.
2. Power off the headset, leave range and toggle Bluetooth while voice is active;
   confirm listening mutes and stays muted during subsequent incoming packets.
3. Interrupt SCO/communication routing with another communication app while keeping
   the headset connected; verify no sustained fallback to the phone speaker.
4. Repeat with the screen off; reconnect and confirm Bluetooth is selected while
   listening remains muted until the user explicitly unmutes.
5. Deny/interfere with route restoration, then manually recover; confirm a failed
   retry cannot release the mute and old speech never replays.

Android/vendor hardware may reroute buffered PCM before delivering callbacks.
Immediate volume zero, output retirement and checks before writes reduce that
window, but this record does not claim zero audible samples on every device.
