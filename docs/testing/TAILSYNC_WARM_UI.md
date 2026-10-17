# Warm cream UI verification

Verified on 2026-10-17 with the Android 15 / API 35 `tsa-ui-api35` emulator.
The app defaults to cream paper, ivory cards, warm gray text and terracotta
accents, independently of Android dark mode. See ADR 0007.

## Production changes

- Rebuilt connection, session, channel, participant and microphone surfaces.
- Centralized palette, typography, shapes and shared surfaces in the Compose UI
  layer; all presentation files remain below 300 lines.
- Bundled 26 Lucide 0.468.0 vectors with 1.75-unit rounded strokes and the ISC
  license. Removed the unused extended Material Icons dependency.
- Added scaling-aware volume labels, scrolling navigation and a microphone mode
  menu for large text, while retaining service callbacks and channel gestures.
- Updated English/Chinese strings, launcher and system-window colors.

## Tests and checks

- 83 JVM unit tests passed: app 32, audio-opus 20, ts3-protocol 31.
- 15 offline Compose tests passed. These cover form validation and cancellation,
  channel expansion/double-tap joining and password cancellation/submission,
  participant mute/volume, diagnostics copying, push-to-talk cancellation and
  removal, and the large-text microphone menu and volume glyph bounds.
- Preview fixtures cover English/Chinese, light/warm-dark tokens and 1.8x text.
  All names and addresses in previews are synthetic fixtures.
- Kotlin formatting and repository `quality` passed; Kotlin compilation,
  `:app:assembleDebug`, app/audio Android Lint and the privacy scan passed.
- App Lint reports 0 errors and 36 warnings: 33 dependency-update notices,
  2 pluralization candidates and 1 unused-resource notice.
- Localized resource keys match, vector XML parses, and `git diff --check`
  reports no whitespace errors.

```powershell
.\gradlew.bat quality :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest
.\tools\check-repository-privacy.ps1
```

## Documentation and configuration

README translations, architecture, contributing instructions, ADR 0007 and
third-party notices describe the final warm appearance and verification.
Compose instrumentation-test dependencies and app lock state are updated;
release/debug compile and runtime graphs remain locked. No credentials,
service/protocol APIs, persistence formats or audio implementation changed.

## Limits and local artifacts

UI verification used an emulator, not a physical device or live TeamSpeak
server. Existing device/audio acceptance requirements still apply.

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. Local preview
PNGs are under `app/build/ui-previews`, including `warm-cream-preview.png`.
These generated artifacts are ignored by Git. Preview tests initially write
into the app's external `ui-previews` directory; preserve/pull those files
before a test runner uninstalls the app.
