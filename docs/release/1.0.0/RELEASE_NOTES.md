# TS3 Mobile 1.0.0 · First stable release

Audio-fix reissue on **2026-11-05** · versionCode **14** · Android **8.0+**.

## Audio-continuity reissue

The November 4 code 13 release was withdrawn after an audio regression. At the
maintainer's explicit request, this replacement keeps tag `v1.0.0` and versionName
1.0.0, increases versionCode to 14 and retains the public signing certificate.
**Download the replacement APK, checksums and build information again. Update in
place without uninstalling; old code 13 files are not this fix.**

- Preserve playback deadlines across short scheduling stalls, avoiding permanently
  slower media consumption and repeated buffer invalidation.
- Keep newly arrived voice packets when the playback worker's clock snapshot is
  older than their arrival, rather than misclassifying them as expired and clearing PCM.
- Add 11 independent regressions; the current JVM suite has 562 passing tests.
  Android 16 recordings and the maintainer's listening feedback confirm improvement
  with the debug fix, not glitch-free acoustic coverage of every release/device scenario.

Codec settings, receive budgets, protocol, permissions and data formats are unchanged.
The [reissue record](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/docs/release/1.0.0/REISSUE_2026-11-05.md)
and [investigation](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/docs/testing/AUDIO_PLAYBACK_REGRESSION_2026-11-05_ZH.md)
separate replacement-build evidence from historical first-release results.

TS3 Mobile 1.0.0 is the project's first stable release: connect to TeamSpeak 3
on Android, use voice and text, and return to your usual servers through bookmarks.

## Highlights

- **Protected Bluetooth listening:** automatically select an available Bluetooth
  communication headset; device loss, unexpected fallback or failed restoration
  mutes listening. Reconnect, check the route and unmute manually to resume.
- **Refined interface:** warm menus/dialogs, clearer Channels/Users/Chat navigation,
  a simpler connection page and server cards with distinct connection, management
  and add actions.
- **Direct pokes:** a hand icon in channel member rows, optional reason, no forced
  navigation after sending, in-app receive notices and generic background alerts.
  Notification content omits sender/reason and respects Android settings.
- **Channel and private text:** independent drafts, unread positions, conversation
  switching, long-press copy and explicit send outcomes. Unknown results are never
  automatically resent; server acceptance is not a read receipt.
- **Saved servers:** up to 50 named entries, optional default channel and encrypted
  opt-in passwords; Fill form, Edit and Delete in each card's menu.
- **Voice controls:** Off, push-to-talk, continuous and volume gate; input gain,
  RNNoise, device AEC, per-user mute/volume and available audio routes.
- **Sessions and status:** away shortcut, local channel/user search, reconnect
  handling, explicit disconnect and a project GitHub menu link.

Includes the development-stage Opus 1.6.1, receive buffering/mixing and audio
lifecycle fixes. Default packages exclude the optional APM experiment. See the
[changelog](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/CHANGELOG.md).

## Install and upgrade

Android 8.0 (API 26)+ is required. The universal APK contains arm64-v8a,
armeabi-v7a, x86 and x86_64. Download `ts3-mobile-1.0.0-release.apk` from Assets
and compare its SHA-256 with `SHA256SUMS.txt`. `BUILDINFO.json` identifies the
source commit, version, certificate fingerprint and toolchain. Source archives
are not installers.

The certificate matches the public 0.11.1-m10 APK (code 12). An API 35 / 16 KiB
emulator update retained the application UID, synthetic private files/preferences
and Android Keystore ciphertext. The inherited certificate subject is Android
Debug; it is retained for update continuity. See the upgrade guide for its fingerprint.

Updating needs the same package and a compatible signature. Keep the old app
if Android reports a conflict: **do not immediately uninstall or clear storage**.
That removes identity, bookmarks and encrypted form memory; there is no built-in
identity export/restore. Separate TS3 Mobile UI test packages do not migrate data.
Check the actual artifact metadata and
[upgrade guide](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/docs/INSTALLATION.md).

## Before use

- One active server. Disconnect before switching. Whisper, file transfer,
  administration and identity import/export are absent.
- Chat/poke records and drafts are memory-only. Reconnect retains them; manual
  exit, a new session, terminal failure or process death clears them.
- Volume gate captures even during silence; Off stops it. Away does not stop voice.
- Bluetooth, notifications and background survival depend on the device/system.
  Disconnect in-app or through the notification instead of relying on task swiping.
- Historical first-release validation: 551 JVM, 129 app Android and 32 default audio Android tests passed,
  plus 48 Opus compatibility combinations and signed-APK identity/codec/launch probes.
  The maintainer completed hands-on feature checks on a Xiaomi 14 / Android 16
  using the November 3 QA package. That predates the new Bluetooth protection;
  final-APK physical Bluetooth/data-upgrade checks and a second device remain open.
  Android/HAL may reroute buffered audio before callbacks, so zero transient
  speaker output cannot be guaranteed. These limits are retained in the
  [release decision](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/docs/decisions/0021-stable-release-acceptance.md).

See [compatibility](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/docs/COMPATIBILITY.md)
for device coverage and acceptance evidence,
[the guide](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/docs/USER_GUIDE.md)
for operation, and [support](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/SUPPORT.md)
for reports.

## Privacy and acknowledgements

Direct server connections; no ads, analytics or project relay backend. Identity,
form memory and bookmarks are encrypted separately on-device. See
[privacy](https://github.com/monet4070/ts3-mobile/blob/v1.0.0/PRIVACY.md).
Report vulnerabilities [privately](https://github.com/monet4070/ts3-mobile/security/advisories/new).

An unofficial community client, not affiliated with or sponsored by TeamSpeak
Systems GmbH. Apache-2.0 with separate third-party licenses. Thanks to ts3j,
Xiph Opus, RNNoise, Lucide and contributors, including @polleyr for server-password
authentication and English localization work.
