# Troubleshooting

[简体中文](TROUBLESHOOTING_ZH.md) · [User guide](USER_GUIDE.md) · [Install and upgrade](INSTALLATION.md)

## Installation and connection

| Symptom | What to check |
| --- | --- |
| Install failure / package conflict | Android version, APK integrity, application ID and certificate. Keep the existing app; do not uninstall before reading the upgrade guide |
| TS3 Mobile and TS3 Mobile UI both installed | UI is a separate test package with separate identity/bookmarks; data does not migrate into the release |
| Cannot connect | Confirm voice port, password, server availability and UDP reachability; ServerQuery is not the voice endpoint |
| Identity connection-limit refusal | End other sessions and follow the suggested minimum 60-second wait before manual retry. Server cleanup may take longer; repeated attempts can trigger a ban |
| Still shown on server after swiping task | A vendor may kill the process before normal exit; disconnect in-app or through its notification and allow an old session to time out |
| Missing search result | Search only covers the loaded server snapshot, subject to visibility permissions |
| Bookmark Connect disabled | Disconnect the current session and wait for bookmark reads/writes to finish |

## Audio and background sessions

| Symptom | What to check |
| --- | --- |
| Others cannot hear you | Microphone mode/permission, channel talk permission and input gain. Hold needs the talk button pressed; try a lower gate threshold |
| Quiet gate still shows microphone capture | Expected: gate detection requires continuous capture. Choose Off to stop |
| No output / wrong device | Listening/per-user mute, participant volume, Android communication volume and selected route. Bluetooth loss/removal applies a safety mute. Check the route and unmute manually after reconnecting |
| Bluetooth differs from speaker quality | Duplex can use a communication audio path; system, headset and device capabilities vary |
| Speaker echo | Prefer headphones, lower speaker volume and inspect device AEC state. Noise suppression and echo cancellation serve different purposes |
| Screen-off disconnect | Inspect Background voice check and vendor battery policy. Optional CPU-awake cannot guarantee survival on every device |
| Microphone settings reset | Service recreation restores defaults; these parameters are not permanent cross-process preferences |

## Messages and notifications

| Symptom | What to check |
| --- | --- |
| No incoming poke popup/sound | In foreground check the in-app notice. In background check notification permission, app notifications, the poke channel and DND. An in-app notice does not promise a notification sound |
| Poke does not open Chat | Expected: submission keeps your page and gives brief feedback; history remains in the corresponding private chat |
| Server accepted but no read receipt | Acceptance is not a delivery/read receipt; check peer availability, permissions and identity |
| Unknown send result | No automatic retry; verify with the peer before resending to avoid duplicates |
| Chat gone after restart | Records are memory-only; exit, a new manual session, terminal failure or process death clears them |
| Form still remembers a removed bookmark password | Form memory and bookmarks are separate. Clear that form field and allow it to save too |

For unresolved problems, share version, device, reproduction steps and sanitized
diagnostics as described in [support](../SUPPORT.md). Never post real endpoints,
nicknames, passwords or identity files. Report vulnerabilities
[privately](../SECURITY.md).
