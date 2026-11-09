# TS3 Mobile 1.1.0

**2026-11-09 · versionCode 15 · Android 8.0+**

## Changes

- **In-app updates:** open More options → Check for updates. Stable releases are the default;
  optionally include prereleases and remember that choice. Downloads open in your browser.
  Close stays at the top, Markdown release notes render natively, and the full reader keeps Show less visible.
  There is no background polling or automatic installation.
- **Offline audio diagnostics:** retain numeric receive/playback, coarse network and route records without USB.
  Mark a heard interruption, then export JSON after testing. Persisted records remain available after reopening.
  No audio, server address, identity or participant data is recorded or automatically uploaded.
- **Bluetooth:** distinguish idle SCO suspension while a headset remains available from actual device loss;
  restore Bluetooth when fresh playback obtains focus. Real headset loss/fallback retains safety muting.
- **Interface:** grouped microphone settings, a shared live-level/threshold scale, left/right chat bubbles,
  structured search results, and saved Light/Dark/System appearance.
- **Everyday use:** saved tuning, retained chat reading positions, generic background private-message alerts,
  current-connection bookmarks and named channel selection.
- **Identity backups:** passphrase-encrypted `.ts3mbak` files, reopened-file verification, confirmed import and local rollback.
  Backups exclude bookmarks/forms and do not support desktop-client identity formats. Keep the passphrase separately.
- **Participant audio:** muting one user preserves other users' valid unmixed audio and decoder state.
  Shared output retirement can still cause an interruption.

## Upgrade

Download `ts3-mobile-1.1.0-release.apk`, `SHA256SUMS.txt` and `BUILDINFO.json` from this release.
The official package and public signing certificate are retained; code 15 upgrades code 14 in place.
**Disconnect before installing. Keep the existing app; do not uninstall or clear data.**
Older stable builds need this one manual download before the new in-app checker becomes available.
The app does not automatically connect or enable capture. Identity replacement requires explicit comparison and confirmation.

## Validation and limits

See the [release acceptance](https://github.com/monet4070/ts3-mobile/blob/v1.1.0/docs/release/1.1.0/CHECKLIST.md)
for exact automated, signed-artifact and Xiaomi 14 upgrade scope. `BUILDINFO.json` binds source and APK hashes.

**This release does not claim that real mobile-network interruptions are fully fixed.**
Recent phone listening was reported temporarily smooth; the weak-network issue has not yet been reproduced with the new diagnostics.
PLC/underrun counters are not measured packet-loss rates or proof of audible gaps. Abrupt exits or delayed/failed persistence can lose tail records;
retention is normally around ten minutes. Long screen-off use, Bluetooth hot-plug, power cost, more Android/OEM devices and independent security review remain limited.

[User guide](https://github.com/monet4070/ts3-mobile/blob/v1.1.0/docs/USER_GUIDE.md) ·
[Privacy](https://github.com/monet4070/ts3-mobile/blob/v1.1.0/PRIVACY.md) ·
[Report a problem](https://github.com/monet4070/ts3-mobile/issues)
