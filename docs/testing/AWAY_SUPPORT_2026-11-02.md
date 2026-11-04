# Away icon and repository support — 2026-11-02

## Scope and architecture

The user prefers an icon-only away shortcut. Replace the generic person glyph
with an original 24 × 24 coffee-cup/clock SVG and matching Android vector. Use
the same icon in the overflow menu. Keep a 48 dp touch target and the existing
localized accessible action name; confirmed away state gains a warm background.
State descriptions distinguish confirmed away, available, pending and unconfirmed
without adding visible button text. The existing presence dialog and service
remain responsible for editing and synchronizing away state.

Add a separated “Give the project a Star” entry to the top-right menu, available
with and without a connection. Its callback opens the verified origin repository,
`https://github.com/monet4070/ts3-mobile`, through Android's HTTPS ACTION_VIEW
handler. Keep the URL and launch/error handling in a focused Android helper.
Do not include session data in the intent. No new dependency, permission,
connection/presence API or storage format is required.

`MainActivity.kt` is already 302 lines. Only add the callback wiring there;
external-link construction and error reporting belong in `ProjectLinks.kt`.
The existing lifecycle/permission collaborators are not refactored in this UI
task. `MainScreen` and the shared offline fixture stay below 300 lines.

Primary risks are an unclear small icon, incorrect state emphasis, a wrong
repository URL, failed link handling and clipping in large-text menus. Verify
with separate Android regressions, an intercepted external intent and physical
device screenshots in both palettes and at 320 dp / 1.8× text. Browser tests
must not depend on network access or modify GitHub state.

## Verification

Ran with the existing JDK 17 and SDK configuration:

```sh
./gradlew :app:ktlintFormat
./gradlew -I build/ui-polish-2026-11-02/ui-debug.init.gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew quality :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest --rerun
adb -s <emulator> shell am instrument -w -r -e package io.github.ts3mobile.app.ui io.github.ts3mobile.app.uidebug.test/androidx.test.runner.AndroidJUnitRunner
```

| Check | Result |
| --- | --- |
| App JVM tests, freshly rerun | 239 passed |
| All-module Kotlin formatting and whitespace | Passed |
| App lint | Zero errors, 36 existing dependency/API/resource warnings |
| Kotlin compilation, Debug and minified Release APKs | Passed |
| Complete API 35 emulator UI suite | 96 passed |
| Xiaomi 14 / Android 16 device checks | 11 passed |
| Repository privacy scan | Passed |

Seven independent Android regressions cover the icon-only shortcut, 48 dp
target and editor callback; light/dark confirmed-away renders; pending state;
disconnected Star-menu availability; 320 dp / 1.8× menu layout and PTT release;
and the actual outgoing ACTION_VIEW intent. An Instrumentation monitor
intercepts the browser launch, verifying the exact HTTPS repository URL,
BROWSABLE category and absence of intent extras without opening a browser or
depending on GitHub/network state. Existing presence application and voice,
session shortcuts and narrow-screen navigation checks also pass on the phone.

Inspected physical screenshots of available/away icon details, the full session,
light/dark appearance and both menu sizes. The user-facing shortcut remains a
pure icon; only accessibility state descriptions carry text. No new text label
or changes to the existing presence dialog/synchronization were introduced.

Updated the existing **TS3 Mobile UI** package after a normal disconnect, with
no uninstall or data clearing. MIUI's background-activity operation was allowed
only for the isolated debug package during instrumentation and restored to its
prior `ignore` mode. The app is reopened for review afterward.

Keep synthetic screenshots, logs, copied unit XML, source SHA-256 manifests and
APKs under ignored `build/away-support-2026-11-02/`. Offline fixtures establish
UI callbacks and the outgoing browser intent; browser/GitHub app availability
and rendering are controlled by the user's Android installation.

## Changed files

- Production: original `assets/icons/away.svg` and matching `ic_away.xml`, a star
  drawable and icon registry entries; new `AwayStatusButton`, audio-row/menu
  wiring, `ProjectLinks`, and one `MainActivity` callback. English/Chinese strings
  provide the support item, accessible presence states and failed-link feedback.
- Tests: separate `AwayAndSupportTest` with seven regressions and a repository
  callback counter in the existing fixture (292 lines).
- Documentation: this record, architecture and both README languages.
- Configuration: no tracked build/dependency/signing/permission changes.

The optional `MainScreen` callback preserves other callers. Public protocol
ports, service state, connection/audio behavior and persisted formats remain
unchanged.
