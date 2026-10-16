# Task removal disconnect: implementation and independent review

Date: 2026-10-13. This record separates code review, local automated checks,
and physical-device/server acceptance. It is not a release approval.

## Verified starting point

- Source: detached commit `e9e0b4182db61d098f5e9f0cf7c1b8827888993c`.
- Existing README additions, Android microphone probe manifest/tests, the
  task-removal plan and audio diagnosis were saved before implementation and
  must remain intact.
- `TeamSpeakService` has no `onTaskRemoved` override and returns
  `START_NOT_STICKY`. `ConnectionCoordinator` owns session generation and a
  session mutex; explicit disconnect invalidates the generation but then uses
  the library's blocking disconnect. Destruction only closes local resources.
- The pinned ts3j revision's `LocalTeamspeakClientSocket.close()` closes the
  datagram socket and shuts down the command executor; it sends no exit command.
  Its `disconnect(String)` can first wait 10 seconds for connection and then
  30 seconds for the disconnected state. These facts were independently checked
  in the resolved jar bytecode.
- `AbstractTeamspeakClientSocket.writePacket(Packet)` serializes COMMAND
  packets with a Java monitor. Reliable sends can wait for ACK/retry. Thread
  interruption can wake an ACK wait, but cannot cancel monitor acquisition.
  A bounded facade must account for both, not merely return from a coroutine.
- The protocol classifies the observed clones command text as retryable, but
  the coordinator terminates retryable failures before an initial success.

## Bounded implementation sequence

1. D1: protocol bounded exit and independent protocol regressions.
2. Parent review and repair of D1 before D2 starts.
3. D2: task-removal lifecycle, exit ownership and concurrent connection tests.
4. Parent review and repair of D2 before D3 starts.
5. D3: bounded first-connect clones recovery, localized messages and tests.
6. Parent review/repair, documentation and complete repository gates.
7. Physical-device and managed-server acceptance at the original clones limit.

Each implementation item is delivered to a built-in DeepSeek agent as a
complete plaintext task with an exact workspace and scope acknowledgement.
Only one implementation agent edits at a time; no more than two DeepSeek
agents may run concurrently. Child test claims require parent verification.

## Decomposition and compatibility assessment

The coordinator, service and protocol facade already exceed the preferred
300-line boundary. This repair extracts focused exit/recovery behavior while
leaving generation and session ownership with the coordinator. Broad listener,
binder and audio refactoring remains separate work in `ARCHITECTURE.md`.
Existing `disconnect(reason)` and local `close()` semantics remain available.
Any new optional protocol method needs a non-I/O `Unsupported` default.
No stored identity, credential, configuration or dependency-version migration
is intended. Home, app switching and lock-screen behavior must be preserved.

## Evidence status

- Initial Gradle invocation failed because SDK configuration was absent.
  The cached SDK contains Platform 35, NDK 27.0.12077973 and CMake 3.22.1.
  An isolated cached JDK 17 was prepared without changing shell configuration.
- With JDK 17 and the cached SDK, the starting protocol suite passed 48 tests
  and the app suite passed 97 tests, with no failures, errors or skipped tests.
  The repository's PowerShell environment and privacy scripts also passed.
  macOS used the JDK executable on PATH rather than the script's Windows-only
  `JAVA_HOME/bin/java.exe` lookup; SDK configuration was supplied by environment.
- D1 parent review: locally accepted. The child's 78-test delivery allowed a
  post-deadline cleanup window and a queued writer to survive until another ACK
  slice. A stricter parent regression reproduced 509 ms for a 250 ms total budget.
  Parent repairs added per-socket operation cancellation and reclamation, a
  cleanup reserve inside one deadline, safe positive-duration joins, finally
  cleanup on interrupted confirmation and first-datagram send progress.
  The socket implementation was extracted to keep cancellation ownership clear.
  Parent gates passed 82 protocol tests, 97 app tests, protocol ktlint and
  `git diff --check`. A pool-reuse regression verifies no stop-induced interrupt
  flag leaks into the next task. These remain JVM/fake-transport results.
- D2 parent review: locally accepted. Regressions exposed new attempts overtaking
  pre-mutex exit cleanup, a newer exit losing teardown ownership, canceled identity
  loading opening a socket, audio budget exhaustion leaving DISCONNECTING/resources,
  capture and playback waits adding beyond the deadline, and terminal failure cleanup
  stopping a newer connection. Repairs serialize registered exits and attempts,
  guard publication after identity loading, run blocking operations interruptibly,
  extract SessionExitRunner with parallel audio cleanup, and guard terminal state
  updates plus main-thread teardown. Three red runs are preserved in parent logs.
  Parent gates passed 128 app tests, 82 protocol tests, app ktlint/Lint/debug build
  and diff checks. Native cleanup that ignores cancellation may overrun the two-second
  target; final cleanup completes before new audio starts and records numeric
  EXIT_CLEANUP_OVERRUN rather than claiming server confirmation.
- D3 parent review: locally accepted. Four new parent regressions failed against
  the first delivery: the original refused handshake outlived the window, a retry
  that ignored interrupts was not closed before timeout cancellation waited, late
  success reached the connected/history path, and terminal command precedence
  differed between category and retryability. Parent repairs use one shared
  classifier, immutable per-epoch windows and an independent watchdog registered
  at first rejection; it captures/closes the socket and old job before cancellation.
  Success and socket publication check the deadline, and callback state mutation
  shares the generation registration boundary. A targeted mutation removing that
  callback guard is rejected by a snapshot-replacement regression.
  Further tests cover cancellation before launch, terminal refusal during the
  initial blocked call, replacement during old close, offline expiry without
  reopening, unexpected failure cleanup, audio preparation retaining cancellation,
  failed sockets closing before backoff, and protocol-facade category delivery.
  The weak child same-socket concurrency case was replaced: its first failure had
  already opened the connect gate, so the retry could not prove blocking-worker
  reclamation. Per-attempt sockets/latches now ignore interrupts and require close.
- Final parent gate: 89 protocol, 159 app and 20 audio tests passed (268 total;
  zero failures/errors/skips). Full unfiltered quality/ktlint, app/audio Android
  Lint and app Debug assembly passed. Privacy scan and git diff --check passed.
  The final gate is local; hosted CI and device/server acceptance were not run.
- Physical-device baseline: not run; adb reported no attached device at review
  start. The user confirmed device/server unavailable and requested local
  verification first. No managed-server credentials or permission changes were used.
- Original-server-limit 20-cycle acceptance, vendor lifecycle behavior,
  microphone/UDP stop timing and actual server release: pending.

The device procedure must distinguish callback arrival, exit request sent,
server confirmation, local timeout and process termination without a callback.
JVM fakes cannot establish any of these Android/server observations.

## Physical acceptance record template

Keep the server's effective clones limit unchanged. Record device model,
Android/vendor version, build source and dirty-state fingerprint, network,
monotonic operation timestamps and the redacted journal for each run.

| Scenario | Required observation | Status |
| --- | --- | --- |
| Swipe task, normal network, immediately connect, 20 repetitions | Callback, exit send, server self-leave confirmation, server offline time and next connection result per repetition | Pending |
| Transmit continuously then swipe | Microphone/UDP sending stop, notification and service teardown | Pending |
| Swipe during handshake, backoff or network change | Old attempt canceled, resources/workers stop, no background reconnect | Pending |
| Reopen/connect within exit window | Queued new request survives; old completion cannot remove new notification or stop service | Pending |
| Drop request/confirmation or swipe offline | Unconfirmed exit is reported accurately; user-started clones recovery succeeds or reaches its fixed deadline | Pending |
| Force stop or vendor process kill | No callback guarantee; relaunch does not connect without a user request | Pending |
| Another legitimate client remains connected with same identity | Recovery reaches deadline without removing that client | Pending |
| Home, app switch, lock, UI and notification disconnect | Established background behavior and manual cancellation preserved | Pending |

Classify each task removal separately: callback and confirmed exit; callback
with unconfirmed exit; process termination without a callback; or surviving
process/service. Android's previous-exit reason alone identifies none of the
user actions. Initial target: confirmation within two seconds of callback on
normal usable networks. Report timing distribution and failures; a send event
alone does not pass the server-release criterion.

## Local verification inventory

| Stage | Protocol tests | App tests | Audio tests | Other checks |
| --- | --- | --- | --- | --- |
| Starting dirty workspace | 48 | 97 | 20 (independent audio gate) | Environment and privacy scan |
| D1 parent acceptance | 82 | 97 | Unchanged | Protocol ktlint and diff check |
| D2 parent acceptance | 82 | 128 | Unchanged | App ktlint, Android Lint, Debug assembly and diff check |
| D3 parent acceptance / final repository gate | 89 | 159 | 20 | Full quality, app/audio Lint, Debug assembly, privacy and diff checks |

Each count represents passed tests with zero failures/errors/skips for that run.
Current app Lint reports zero errors and 35 warnings (6 AGP version, 27 dependency
version and 2 plural-resource suggestions); audio Lint reports no issues. A
successful local gate does not imply
hosted CI, release packaging, installation or physical-device/server acceptance.
Environment checks use cached JDK 17 and the existing SDK supplied through process
environment, with no project-local SDK or shell configuration changes.

## Delivered change groups

- Production protocol: bounded disconnect result/runner, per-socket operation
  reclamation and extracted state-aware socket; Ts3SessionClient, Ts3jClientSocket
  and Ts3jSessionClient integration; Model and shared ConnectionFailure classifier.
- Production app: ConnectionCoordinator and TeamSpeakService lifecycle/ownership;
  SessionExitRunner, SessionRequestOwnership, ClonesRecoveryPolicy/Engine;
  MicrophoneController interruptible stopping, SessionGeneration guarded mutation,
  DiagnosticJournal fixed categories, UserMessage/UserMessageText and localized
  resources. ReconnectEngine handles the additive Expired variant.
- Tests: separate runner, socket operations, protocol facade/classification,
  ownership, generation, coordinator, journal and recovery suites. Real-thread
  per-attempt fixtures complement virtual time; there is no test branch in production.
- Documentation: README in both languages, architecture in both languages,
  protocol README, ADR-0008/0009, background acceptance and this review.
- Configuration: only the explicit app service stopWithTask=false declaration.
  No server permissions, dependencies/lockfiles, identity/credential formats or
  project/shell SDK configuration changed. Existing audio test manifest changes
  belong to the protected starting workspace.

## Final artifacts and evidence

The parent command records are in the local evidence directory
`/tmp/tsm-task-removal-20261013/`: parent-final-gate.log,
parent-final-privacy.log, parent-environment-final.log, parent-final-results.json,
and the parent red/green/mutation logs. Child delivery snapshots are separate;
child claims do not replace these final records.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (31,828,372 bytes).
SHA-256: `be1ba241fa436c2f4f2752dbcd49d389ab9add6d9c32399ce140911496ef79e6`.
This is the changed detached e9e0b418 working tree's Debug build, not a release
candidate certified by a tag or hosted CI. No commit, push, release, installation,
server change or running-app termination was performed during the local gate.
The subsequent phone-install request is recorded separately below.

All eight protected files passed the baseline check: probes, audio test manifest,
plan/image and audio diagnosis are byte-identical; all original README lines remain
in order with only additive task content. Original app/test APKs were backed up
before local builds. Device/server unavailability was confirmed by the user;
problem-phone and original-server-limit acceptance remain pending.

## Follow-up phone installation — 2026-10-13

The user requested installation after local acceptance. The connected device was
23127PN0CC on Android 16. The existing app and the locally built APK have different
debug certificates, so they cannot be updated in place while retaining data without
the old signing key. The original APK was backed up; no original app was uninstalled.

A temporary Gradle init script built a separate package, with no production-source
or persistent build-configuration changes:

- Label: TS3 Mobile · 断开修复测试
- Package: io.github.ts3mobile.app.taskremoval
- Version: 0.11.0-m10-task-removal-test
- Installed APK SHA-256: a69b0d3d07d82764953feb05d8ea306849f84d0fae1b5cb309507cff0729cfa4

The phone initially rejected USB installation. After the user allowed it and
requested a retry, adb reported Success. Pulling the installed APK confirmed its
exact fingerprint. A cold launch returned Status: ok and the process remained
present. The original app retained its previous update time and package version.
The original validated APK also retained its be1ba241… fingerprint.

The test package uses separate data and identity; it does not migrate the original
app's encrypted identity or settings. Installation/startup evidence does not pass
the task-removal/server-release matrix. No server connection or real-network
acceptance was performed. Installer evidence and temporary build inputs remain
under /tmp/tsm-task-removal-20261013/phone-install/.
