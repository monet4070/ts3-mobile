# Continuation plan

中文版：[CONTINUATION_PLAN_ZH.md](CONTINUATION_PLAN_ZH.md)

This plan takes the project from the current M11 preparation state to a
signed production-beta release candidate, then into the first post-beta
maintenance cycle. It does not add features. Whisper, voice activation, text
chat, file transfer, permission administration, bookmarks, an in-app language
picker, and multi-server tabs stay out of scope until a new decision record
says otherwise (ADR-0002).

The acceptance bar is the existing
[M11 release checklist](M11_RELEASE_CHECKLIST.md) and
[M10 stability matrix](../testing/M10_STABILITY_MATRIX.md). This document
sequences that work. It does not replace either checklist. When a row is
executed, mark it on the source checklist the same day and link the redacted
evidence. Do not let this plan and those checklists drift apart.

Status words used below:

- **Done** — implemented and covered by an automated test or a recorded device run.
- **Partial** — code exists, but the release evidence is incomplete or stale.
- **Open** — not started, or started and explicitly deferred.

## Where the project is

Recorded against commit `9af47e8` on 2026-09-24.

| Area | State | Evidence |
| --- | --- | --- |
| Protocol, voice, routing, reconnect, channels, diagnostics, English/Chinese resources | Done in code | `README.md`, `CHANGELOG.md` Unreleased |
| Automated JVM gate | Partial | Local run on 2026-09-21: 26 suites, 81 tests, 0 failures. The M11 checklist still cites the older 2026-09-04 run of 61 tests |
| Debug CI | Done | `.github/workflows/android.yml` runs privacy scan, `quality`, unit tests, lint, `assembleDebug` |
| Device smoke and M10 long rows | Open | Only the 2026-08-16 Android 16 session; 20 of 30 screen-off minutes; no Bluetooth, no password channel, no second device |
| Version and signing | Open | `versionName` is still `0.11.0-m10` while the milestone is M11. No release signing, no published SHA-256 |
| ts3j ownership | Partial | Lockfiles and R8 rules are done (ADR-0004 steps 2–4). The fork (step 1) is not |
| Structural split | Partial | Service 498, coordinator 579, player 492 lines. Listener, notification, and `AudioTrack` extractions remain |

## Rules for every slice

Each slice is one pull request unless it is a device-evidence update with no
code.

1. Read the files the slice touches, plus the matching tests, before editing.
2. Change only what the slice needs. Do not bundle a structural extraction with a device-matrix claim.
3. Add or extend a regression test for every behavior change. Bug fixes need a failing test first.
4. Run the automated gate in the "Verification" section before merging.
5. Update `CHANGELOG.md` Unreleased, and update `README.md` or an ADR when user-visible behavior or a decision changes.
6. Device notes stay redacted: model category, API level, route category, commit, result. No host, password, nickname, identity, channel name, or participant data.
7. Stop the slice if it would require weakening a test, committing `local.properties`, or recording a secret.

## Verification

Run from a clean tree after every code slice:

```powershell
.\tools\check-build-environment.ps1
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
.\tools\check-repository-privacy.ps1
git diff --check
```

On a slice that changes native audio, also run this on an authorized device:

```powershell
.\gradlew.bat :audio-opus:connectedDebugAndroidTest
```

A release-candidate slice additionally runs `:app:assembleRelease` and records
the APK SHA-256 outside the repository.

## Phase 0 — Sync the written baseline

Goal: the checklists match the tree that will be tested. No product code.

- [ ] Replace the M11 checklist's "61 tests on 2026-09-04" sentence with the 2026-09-21 result (26 suites, 81 tests, 0 failures), and note that CI has not been re-run against `9af47e8` since that local run.
- [ ] Re-run the automated gate on current `main` and attach the new date to section 2 of the M11 checklist. If the count moves again, record the new count. Do not leave two conflicting numbers.
- [ ] Point the README "Project status" paragraph at this plan as well as the two checklists.
- [ ] Leave every device box unchecked. The 2026-08-16 session is historical evidence, not a pass of the current build.

Exit: one commit, checklists and README agree, automated gate green on the commit just made.

## Phase 1 — Close the M11 device smoke gate

Goal: section 3 of the M11 checklist, on the current debug APK, one primary device. This is the short gate, not the 30-minute rows.

Device: one phone on Android 10 or newer. Record model category and API level only.

- [ ] Cold launch and notification/microphone permission flow, no crash.
- [ ] Connect, manual disconnect, manual reconnect. Confirm the status and the channel snapshot belong to the new session.
- [ ] Expand a channel and read its members.
- [ ] Double-tap a public channel and confirm current-channel state.
- [ ] Microphone off, push-to-talk, and continuous. Return to push-to-talk afterward.
- [ ] Copy the diagnostics export and confirm it has no endpoint, password, nickname, or identity.
- [ ] Repeat the native device suite (`connectedDebugAndroidTest`) and record the pass count.

Exit: section 3 boxes checked with a dated, redacted subsection in `docs/testing/M10_STABILITY_MATRIX.md` under "Run log". If a row fails, file it as a bug with a regression test and stop Phase 2 until it is fixed.

## Phase 2 — Close the carried M10 blockers

Goal: section 4 of the M11 checklist. One row per session so a failure is attributable. Use a provisioned test server and a provisioned password channel; do not use a production community server.

Order matters. Long runs come before route changes so a crash in the simple case is seen first.

- [ ] **30-minute speaker duplex.** No crash, no growing latency, no stuck microphone. Record start/end latency impression, battery, temperature, and the redacted diagnostics counters.
- [ ] **30-minute screen off.** The 20-minute run does not count. Foreground service stays up and the notification disconnect works at the end.
- [ ] **Password channel restore, 5 reconnects.** Last channel returns, or the UI shows an actionable error. Policy unit tests are not a substitute.
- [ ] **Wired hot-plug, 10 changes.** Unplug falls back to the system route without a crash.
- [ ] **Bluetooth duplex, 15 minutes, plus removal fallback.** Requires a connected headset. JVM route classification does not close this row.
- [ ] **Second device, different Android generation.** Minimum coverage is Android 10, 12, 14, or 15, not another Android 16 phone. Repeat the Phase 1 smoke there, plus one 15-minute duplex.

Exit: section 4 boxes checked, each with its own run-log subsection. M10 may then be called complete. M11 may not, until Phase 4.

## Phase 3 — Release hygiene that does not need a device

These can run between device sessions. Do not start them by rewriting audio code.

### 3a. Version and changelog

- [ ] Decide the beta version name. Recommendation: `0.12.0-m11` with `versionCode` 12, bumped only when the RC is cut, not before the device rows pass.
- [ ] Move `CHANGELOG.md` Unreleased into a dated `0.12.0-m11` section at RC time. Until then, keep new notes under Unreleased.

### 3b. ts3j fork (ADR-0004 step 1)

This is maintainer account work, not an app refactor.

- [ ] Fork `Manevolent/ts3j` at `db57d60c989e399626aa16d921390f5033e6cdeb` into an organization the project controls.
- [ ] Tag it `ts3mobile-1` and confirm JitPack builds that tag.
- [ ] Switch `gradle/libs.versions.toml` to the fork coordinates, refresh lockfiles with `--write-locks`, and record the source commit in `THIRD_PARTY_NOTICES.md`.
- [ ] Re-run the automated gate and `:app:assembleRelease`.

Do not upgrade ts3j, BouncyCastle, or the other pinned transitives in the same change.

### 3c. Remaining structural split

Do this only after Phase 2 is green, or in parallel if a device is not available. One extraction per pull request. Preserve the binder API, session-generation rejection, the bounded audio queue, and current tests.

- [ ] Move the session listener out of `ConnectionCoordinator` (579 lines). Coordinator should keep attempt orchestration and binder-facing entry points.
- [ ] Move `AudioTrack` creation and output writes out of `OpusAudioPlayer` (492 lines). Leave the focus policy where it is.
- [ ] Move notification channel and foreground-type updates out of `TeamSpeakService` (498 lines).

`AudioDeviceRouter` (317 lines) stays as one type unless a new route family appears. `OpusMicrophoneCapture` is already under the line.

### 3d. Test gaps worth closing before RC

Not a coverage percentage target. These are the production types whose failure would not be caught by a unit test today.

- [ ] `ReconnectEngine`: backoff handoff, cancel, and terminal failure, with a fake clock.
- [ ] `UserMessageText`: every `UserMessage` and `AudioRoutingError` variant resolves to a non-empty string in both `values/` and `values-zh/`.
- [ ] `IdentityVault`: round-trip with a fake `MasterKey` or instrumented test, without logging key material.

Compose screenshot tests and a `TeamSpeakService` instrumentation suite are explicitly deferred. They need a harness this project does not have yet.

## Phase 4 — Cut the release candidate

Start only when Phases 1, 2, and 3b are done. 3c and 3d may land before or immediately after, but not inside the RC commit.

- [ ] Set `versionCode` and `versionName` as decided in 3a.
- [ ] Review `CHANGELOG.md`, `README.md`, `PRIVACY.md`, `SECURITY.md`, and `THIRD_PARTY_NOTICES.md` against the diff since `0.10.0-m9`.
- [ ] Inspect the release manifest. Expected permissions stay limited to internet, network state, audio settings, record audio, post notifications, and the three foreground-service types already declared.
- [ ] Build `:app:assembleRelease` with the project signing config kept outside git.
- [ ] Store the APK and its SHA-256 in the private release record, not in an issue or this file.
- [ ] Mark M11 as release candidate in the checklist only after the boxes above are checked.

Exit: a tagged RC build that a second person can install from the recorded SHA-256. The app remains pre-release. Users still keep another client available for important servers.

## Phase 5 — Steady cadence after RC

This is the continuous part. One weekly rhythm, no new milestone until the RC has a week of real use.

| When | What | Done when |
| --- | --- | --- |
| Every code change | Automated gate in "Verification" | Command exits 0 on the merge commit |
| Weekly, 30 minutes | One matrix row on a rotating device (speaker, screen-off, Bluetooth, wired, password restore) | A redacted run-log entry, pass or fail |
| Weekly | Triage failures from that row before any feature work | A regression test exists for each confirmed bug |
| Monthly | Re-read ADR-0002 | Either the single-server scope still holds, or a new ADR is proposed. Do not start chat or bookmarks without it |
| On dependency bumps | `--write-locks` plus a reviewed lockfile diff, fork coordinates unchanged | Gate and `assembleRelease` green |

Stop conditions, which override the cadence:

- A crash, stuck microphone, or stale session in a weekly row blocks feature work until a regression test covers it.
- A JitPack or fork outage blocks release builds until the artifact resolves again. Do not vendor a random newer ts3j to get unblocked.
- Any request to persist server passwords, nicknames, or bookmarks stops for a new ADR. Identity encryption is the only deliberate persistence.

## What this plan will not do

- It will not declare M10 or M11 complete from JVM tests.
- It will not treat the 2026-08-16 session as a pass of today's APK.
- It will not fork ts3j and refactor the service in one change.
- It will not add a product feature to make the beta "feel bigger" before the device rows exist.
