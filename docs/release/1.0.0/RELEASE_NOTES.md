# TS3 Mobile 1.0.0 · First stable release

Released on **2026-11-04** · versionCode **13** · Android **8.0+**.

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
- Validation: 551 JVM, 129 app Android and 32 default audio Android tests passed,
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
