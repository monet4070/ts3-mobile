# UI refinement after device feedback — 2026-11-02

## Architecture and plan

The first layout pass left Material 3's elevated surface roles at their library
defaults. Menus and dialog containers therefore render purple/gray even though
the surrounding application uses warm paper colors. Complete the theme's
surface hierarchy for both palettes and verify actual popup pixels, alongside
contrast and palette unit regressions.

Channel members currently render through `ChannelParticipantRow`, while poke
recipient capture and validation belong to `ConnectedContent` and the existing
`PokeSendDialog`. Add a labeled Poke action directly to other channel members.
Both channel and Users entry points must share recipient capture and release
PTT before opening the dialog. Preserve identity/session validation, optional
reason, byte limits and explicit send confirmation; clicking the shortcut must
not send a poke by itself.

Remove the connection introduction's second icon and promotional subtitles,
including the app-bar tagline and decorative footer. Use a plain title and
the existing form hierarchy; retain practical errors and validation messages.

This is a presentation change: protocol, audio processing, storage, service
state and release configuration remain compatible. Main risks are unrelated
palette regressions, lost text width in channel members and targeting a stale
client. Cover these in separate test files and inspect device screenshots.

## Decomposition assessment

`ParticipantPanel.kt` is currently 273 lines and combines the Users roster with
the smaller channel-member row. Extract `ChannelParticipantRow` to its own
file as it gains the direct action, retaining its name and existing caller.
Keep recipient capture in `ConnectedContent`; do not duplicate poke rules in
row components. Existing 314-line `UiFixture.kt` will not grow. New layout and
popup regressions belong in focused Android test files.

## Verification

The new palette regression was run before the theme fix and failed on the
unconfigured elevated surface roles. After completing both palettes, the theme
regression and contrast checks pass. Actual rendered pixels are separately
checked for audio-route menus, participant menus and poke dialogs in light
and dark mode, rather than inferring popup appearance from the base palette.

Ran with the existing JDK 17 / Android SDK configuration:

```sh
./gradlew :app:ktlintFormat
./gradlew -I build/ui-polish-2026-11-02/ui-debug.init.gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew quality :app:lintDebug :app:assembleDebug :app:assembleRelease
adb -s <emulator> shell am instrument -w -r -e package io.github.ts3mobile.app.ui io.github.ts3mobile.app.uidebug.test/androidx.test.runner.AndroidJUnitRunner
./gradlew quality
```

| Check | Result |
| --- | --- |
| All-module Kotlin formatting (`quality`) | Passed |
| App JVM tests, freshly executed | 233 passed, zero skipped |
| App lint | Zero errors, 36 warnings about existing APIs/resources/dependency versions |
| Kotlin compilation, Debug APK and minified Release APK | Passed |
| API 35 emulator, full offline UI suite | 85 passed |
| Physical Android 16 device, individual checks | 20 passed |
| Repository privacy scan and whitespace check | Passed |

Thirteen new Android checks cover six light/dark popup renders, two simplified
connection pages, and five channel-member actions: captured recipient and exact
reason, PTT release before confirmation, another channel without joining,
reused client ID rejection, cancellation, and readable Chinese actions at
320 dp / 1.8× text. The channel-expansion test waits for the existing
double-tap gesture timeout before looking for a newly expanded member.

The physical phone is the same Xiaomi 14 / Android 16 / 1200 × 2670 device.
MIUI blocked test-activity background starts (`MIUIOP(10021)`), so that operation
was temporarily allowed only for the isolated debug package while running the
device tests, then restored to its previous `ignore` mode. This is a test-harness
adjustment, not an app manifest/permission change. All 20 final checks pass;
the app is reopened after the original device setting is restored.

Retain logs, copied unit XML, source manifests, APKs and synthetic fixture images
under ignored `build/ui-refinement-2026-11-02/`. The phone uses the same
**TS3 Mobile UI** installation/application ID and signing key as the first pass;
update in place, without clearing identity, bookmarks or form memory. Request a
normal disconnect before installing the update to avoid abandoning its socket.

## Files and compatibility

- Production: complete `ui/theme/Colors.kt`; simplify `ConnectionPanel` and
  `MainScreenAppBar`; extract `ChannelParticipantRow` from `ParticipantPanel`;
  connect its action through `ChannelPanel` and shared `ConnectedPanel` recipient
  capture; remove promotional strings in both languages and add a localized
  member-specific accessibility label.
- Tests: expand `ColorsTest`; add independent `WarmSurfaceTest` and
  `ChannelMemberActionsTest`; update the existing poke rejection wording check.
- Documentation: this record, English/Chinese README and visual architecture.
- Configuration: no tracked build/dependency/signing or permission changes.

The channel shortcut still opens the existing explicit confirmation dialog. It
does not send on first tap, target the local client or bypass stale/ambiguous
identity checks. Protocol/service APIs, persistence and audio behavior are
unchanged. Physical checks use fakes and do not send messages/pokes to a server.
Live-server permissions and acoustic/background acceptance remain separate.
