# M11 Release Checklist

M11 is the production-beta preparation milestone. It does not declare the M10
physical-device matrix complete; deferred long-duration, Bluetooth, password
channel and second-device rows remain release blockers until they are executed.
The [continuation plan](CONTINUATION_PLAN.md) sequences this checklist; it does
not replace it.

## 1. Local Environment

- [x] Run the repository-owned environment check:

  ```powershell
  .\tools\check-build-environment.ps1
  ```

  Passed on 2026-09-04 after fixing the script's JDK probe so Windows
  PowerShell 5.1 no longer aborts when `java -version` stderr is wrapped as an
  error record.

- [ ] Use JDK 17, Android SDK Platform 35, Build Tools 35.0.0, NDK
  27.0.12077973 and CMake 3.22.1.
- [ ] Keep `local.properties`, signing files and identity material untracked.
- [ ] If the UDP bind check fails, resolve the local resource exhaustion before
  retrying Gradle. Do not work around it by weakening tests or committing local
  endpoints.

## 2. Automated Gate

Run from a clean working tree or record unrelated pre-existing changes before
starting the check:

```powershell
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace
```

- [x] `quality` passes for all Kotlin modules.
- [x] Protocol JVM tests pass, including stale-session and facade race tests.
- [x] Audio JVM tests, Android lint and App JVM tests pass.
- [x] Debug APK assembly succeeds.
- [ ] Release APK assembly succeeds with R8 enabled for the release candidate.
- [ ] On a booted emulator, run `tools/check-release-identity.ps1 -DeviceSerial
  <serial>` with `ANDROID_HOME` set (or `-SdkRoot`). It must pass against the exact
  candidate APK; use `-ApkPath` if it is not the default unsigned release output.
  Preserve its log and the matching R8 `mapping.txt`. See the
  [issue #3 regression report](../testing/ISSUE_3_RELEASE_IDENTITY_FIX.md).
- [x] `git diff --check` passes.
- [x] Run `.\tools\check-repository-privacy.ps1`; it reports no tracked or
  untracked endpoint, APK, keystore or local configuration material.

All automated-gate items were verified on 2026-09-04 (JDK 17.0.20, Gradle
wrapper build): `BUILD SUCCESSFUL` with 61 JVM/unit tests passing across 22
suites and zero failures.

## 3. Device Smoke Gate

Record only redacted device model/category, Android API level, route category,
build commit and result. Never record a server address, password, identity,
nickname or participant data.

- [ ] Cold launch and permission flow complete without a crash.
- [ ] Connect, disconnect and reconnect complete without a stale status or
  stale snapshot.
- [ ] Expand a channel and display its members.
- [ ] Double-tap a public channel and verify the current-channel state.
- [ ] Exercise microphone off, push-to-talk and continuous modes.
- [ ] Verify diagnostics export contains no endpoint or identity data.

## 4. M10 Acceptance Rows Carried Into M11

- [ ] 30-minute speaker duplex.
- [ ] 30-minute screen-off/background run.
- [ ] Physical Bluetooth duplex and removal fallback.
- [ ] Wired/Bluetooth route hot-plug coverage.
- [ ] Password-protected channel restore on a provisioned test channel.
- [ ] A second Android generation/device.

## 5. Release Artifact Review

- [ ] Confirm `versionCode` and `versionName` are intentionally selected for
  the release; do not bump them merely to bypass a failed gate.
- [ ] Review `CHANGELOG.md`, `README.md`, privacy/security notices and third-
  party notices for synchronized behavior and dependency changes.
- [ ] Inspect the APK manifest and permissions for unexpected additions.
- [ ] Preserve the generated APK outside Git and record its SHA-256 in the
  private release record, not in a public issue.
- [ ] Mark the milestone as release candidate only after every blocker above
  has evidence attached.

## Current Known Blocker

~~On 2026-08-17, Gradle 8.9 and JDK 17 wrapper startup passed, but the full gate
failed in `FileLockCommunicator` before compilation with `No buffer space
available (maximum connections reached?): bind`. This is an environment/resource
problem and must be resolved before claiming the full local gate is green.~~

Resolved: on 2026-09-04 the environment check (including the temporary UDP
bind) passed and the full automated gate
(`quality` + all JVM/unit tests + Android lint + `assembleDebug`) completed
with `BUILD SUCCESSFUL`. The device smoke gate and the M10 physical-device
acceptance rows in section 4 remain open release blockers.


## 2026-10-18 manual retry / memory / voice verification

The current working tree passes the full automated gate including Release lint
and minified Release assembly: 261 JVM tests, zero failures. The actual Release
APK passes the offline identity/INI ART probe on the connected Android 16 phone.
An isolated application ID passes 28 offline UI tests and a separate process
restart check for the encrypted form. The standard Debug APK has been installed
as a data-preserving update, left disconnected. See the
[implementation report](../testing/MANUAL_RETRY_MEMORY_VOICE_2026-10-18.md).
These are local working-tree results, not an approved/signed release candidate;
remaining long-duration, physical routing and second-device rows stay open.


On 2026-10-18, a short live-server acceptance of the minified, non-debuggable
Release passed two connections and two server-confirmed exits with the existing
identity, a 63-second Home/background hold and silent capture. The installed APK
was hash-checked against the tested artifact. It uses the existing local signing
certificate, not a newly configured production key; long-duration/audio-listening
and Android 14 rows remain open. See the
[Release acceptance report](../testing/RELEASE_ACCEPTANCE_2026-10-18.md).
