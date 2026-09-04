# Contributing

Thanks for helping improve TS3 Mobile. This is an experimental client, so
changes should preserve connection stability, bounded audio latency, and user
control over microphone transmission.

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
.\gradlew.bat :ts3-protocol:test :audio-opus:testDebugUnitTest :audio-opus:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The repository quality gate also checks Kotlin formatting across all modules:

```powershell
.\gradlew.bat quality
```

Before publishing a branch, run the privacy-safe repository scan:

```powershell
.\tools\check-repository-privacy.ps1
```

Use `.\gradlew.bat ktlintFormat` only for a focused formatting change, then
review the complete diff before committing it.

Changes to native audio or device interaction should also be tested on a
physical device. Describe the tested device, Android version, audio route, and
result without exposing private server details.

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
