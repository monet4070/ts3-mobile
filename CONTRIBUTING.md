# Contributing

Thanks for helping improve TS3 Mobile. This is a community-maintained client;
changes should preserve connection stability, bounded audio latency, and user
control over microphone transmission.

The first stable release is 1.0.0 (2026-11-04). User documentation starts
at the [README](README.md); release roles, artifact/signature requirements and
open acceptance items are in the [release pack](docs/release/README.md).
Do not mark a release or a device-matrix row passed merely because docs are ready.

## Before opening a change

- Search existing issues and pull requests.
- Use an issue for behavior changes that need design agreement.
- Never include server passwords, identity files, real server addresses,
  participant information, local SDK paths, APKs, keystores, or signing keys.
- Keep changes focused and follow the existing Kotlin and Compose style.
- New protocol and audio behavior should include focused tests where practical.

## Local checks

Use JDK 17 and the Android/NDK versions listed in the README. Before submitting
a pull request, run the environment check first:

```powershell
.\tools\check-build-environment.ps1
```

Then run:

```powershell
.\gradlew.bat :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

After changes to identity handling, dependencies or shrinker rules, also run the
offline regression against the actual minified APK on a booted Android emulator:

```powershell
.\tools\check-release-identity.ps1 -DeviceSerial emulator-5554
```

This requires PowerShell 7 and `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or an explicit
`-SdkRoot` argument. Select the serial from `adb devices`; `-ApkPath` can select
another APK. The Java probe in `app/src/releaseSmokeTest` is compiled separately
and runs via ART `app_process`, without installing the app or contacting a server.
Keep it outside the production build so test references cannot hide R8 mistakes.
CI runs this check on API 35. A successful release build alone cannot validate
reflective constructors; see the [issue #3 report](docs/testing/ISSUE_3_RELEASE_IDENTITY_FIX.md).

The repository quality gate also checks Kotlin formatting across all modules:

```powershell
.\gradlew.bat quality
```

For an acceptance record, force the test tasks to execute instead of counting
older XML from an `UP-TO-DATE` or `FROM-CACHE` task as a fresh run:

```sh
./gradlew --no-daemon --max-workers=2 --no-build-cache --rerun-tasks quality :ts3-protocol:test :audio-opus:testDebugUnitTest :app:testDebugUnitTest
```

Save the command, task outcomes and copied result XML alongside source/configuration
SHA-256 manifests taken before and after the run. A later source change invalidates
that acceptance record; distinguish reused Android/Release evidence explicitly.

Before publishing a branch, run the privacy-safe repository scan:

```powershell
.\tools\check-repository-privacy.ps1
```

Documentation-only changes should validate local links/anchors, issue-template
YAML, examples, whitespace and the privacy scan. Attribute reused application
test evidence to its dated record; do not present it as a new run. GitHub CI skips
the paths listed in its documentation-only filters. Publishing uses the exact
reviewed source commit and final signed APK, as described in the release pack.

Use `.\gradlew.bat ktlintFormat` only for a focused formatting change, then
review the complete diff before committing it.

For UI changes, run the offline Compose regression/preview suite on an Android
emulator (no TeamSpeak server or credentials are required):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The preview tests write PNGs into the app's external `ui-previews` directory.
Check both palettes, Chinese text and large text before changing shared UI tokens.

Changes to native audio or device interaction should also be tested on a
physical device. Describe the tested device, Android version, audio route, and
result without exposing private server details.
For background voice or saved endpoint changes, also follow
[the background acceptance checklist](docs/testing/BACKGROUND_VOICE_ACCEPTANCE.md).

### Offline playback regression

For the staged audio-quality upgrade, see the
[A0/A1 execution record](docs/testing/AUDIO_A0_A1_2026-10-24.md) and
[A2 gain/limiting checks](docs/testing/AUDIO_A2_2026-10-24.md).
With JDK 17 and `ANDROID_HOME` configured, macOS/Linux can use:

```sh
./gradlew quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :audio-opus:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.audio.opus.OpusAudioPlayerFocusTest,io.github.ts3mobile.audio.opus.AudioFocusInterruptionTest,io.github.ts3mobile.audio.opus.AudioDeviceRouterTest,io.github.ts3mobile.audio.opus.NativeOpusCodecTest,io.github.ts3mobile.audio.opus.NativeOpusCompatibilityTest,io.github.ts3mobile.audio.opus.NativeOpusReceiveTest,io.github.ts3mobile.audio.opus.NativeRnNoiseProcessorTest,io.github.ts3mobile.audio.opus.MicrophonePipelineTest,io.github.ts3mobile.audio.opus.NativeAudioLifecycleTest,io.github.ts3mobile.audio.opus.PlaybackLifecycleTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.app.service.MicrophoneControllerTest,io.github.ts3mobile.app.ui.PlaybackFailureTest
```

The selected Android tests use synthetic PCM and local playback/routing only;
they exclude microphone-recording probes and require no server or credentials.
`MicrophoneControllerTest` constructs only a fake capture; it verifies foreground
type retention through blocked stop, shutdown after scope cancellation and quick
off/on. `PlaybackLifecycleTest` checks main-thread stop and output retirement;
`PlaybackFailureTest` verifies visible failure/recovery guidance. JNI concurrency
is covered by `NativeAudioLifecycleTest`.
JVM fixtures use virtual time, including the ten-minute receive/playback trace.
Run formatting separately before checks; do not run format and check tasks
together with Gradle parallel execution. These checks do not establish physical
audio quality, Bluetooth bandwidth or power consumption.

A4b adds seeded ten-minute loss/reorder/clock/stall simulations and real LBRR
fixtures. Parameters and conservative FEC eligibility are documented in the
[A4b record](docs/testing/AUDIO_A4B_2026-10-24.md). Fixed 60 ms receive buffering is
available through internal `ReceivePolicy(adaptive = false)` for comparison and
rebuild rollback; no persisted preference is added.

The [audio review repair plan](docs/plans/AUDIO_REVIEW_FIXES_2026-10-24_ZH.md)
covers bounded output recovery, immediate send invalidation with background
capture teardown, route generations and lifecycle regressions. Also run:

```sh
python3 -m unittest discover -s tools/tests -p 'test_audio_build_config.py'
```

Audio helper scripts read SDK/NDK/CMake choices from the Gradle files and the
production Opus version from `opus-source.cmake`. Missing or ambiguous tools
fail explicitly. The Release smoke checks the runtime codec version through JNI.
APM toolchain/source/options changes invalidate its Meson build fingerprint and
wipe/reconfigure the cache; an existing build directory is not proof of the chosen compiler.

### Optional APM experiment

The default application does not enable or package the APM prototype. For offline
development, use Python 3.9+, the documented NDK/CMake versions and JDK 17:

```sh
python3 tools/build-apm-experiment.py --sdk "$ANDROID_HOME"
./gradlew :audio-opus:connectedDebugAndroidTest -PapmExperimentRoot="$PWD/build/apm-experiment" -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.audio.opus.NativeApmExperimentTest
```

The script verifies pinned archives and extracted sources; Meson cannot download
additional dependencies. Native artifacts and all required notices stay under
the ignored build root. Preserve its `notices/` directory if sharing experiment
binaries. Do not enable the experiment on physical routes without validating
the source/effects policy and timestamp mapping. See [A3 evidence and remaining
integration](docs/testing/AUDIO_A3_2026-10-24.md). Run normal gates without the
`apmExperimentRoot` property to verify that default APKs exclude the optional library.

### Opus source and compatibility

Production builds use the official Opus 1.6.1 archive pinned in
`tools/opus-compat.lock.json`. CMake downloads and verifies it, falling back to
the recorded Xiph mirror. For offline use, supply
`-PopusSourceArchive=/absolute/path/opus-1.6.1.tar.gz`; this changes the archive
location, never the required SHA-256. No Prefab/Maven Opus package is needed.
Do not modify extracted sources under `.cxx`; change the lock/options and rebuild
when upgrading. Codec optimization remains enabled in Debug to preserve real-time behavior.

With Python 3.9+, a host C/C++ compiler, JDK 17 and the Android SDK configured:

```sh
python3 tools/check-opus-compat.py --sdk "$ANDROID_HOME"
./gradlew :audio-opus:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.ts3mobile.audio.opus.NativeOpusCompatibilityTest,io.github.ts3mobile.audio.opus.NativeOpusCodecTest
python3 tools/check-release-codec.py --device emulator-5554 --sdk "$ANDROID_HOME"
```

The host check validates 1.3.1/1.6.1 four-way codec combinations; Android uses the
same checked-in synthetic corpus through production JNI. Regenerate assets only
with an intentional reviewed `--write-fixtures` run. All four ABIs must build;
runtime evidence must name the ABI actually tested. See [A4a evidence](docs/testing/AUDIO_A4A_2026-10-24.md).

## Pull requests

Explain the user-visible behavior, technical approach, tests performed, and
known limitations. By submitting a contribution, you represent that you have
the right to provide it under the repository's Apache License 2.0 and that any
third-party material is identified with its applicable license.

Create changes on a focused branch and open a pull request into `main`. Do not
force-push shared branches or merge directly into `main`. A change is ready to
merge only when formatting, affected unit/integration tests, Android Lint, the
debug build, security/privacy review, and documentation synchronization have
all passed. Physical-device evidence is required for audio routing, microphone,
foreground-service, and native codec behavior.

All contributors must follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
