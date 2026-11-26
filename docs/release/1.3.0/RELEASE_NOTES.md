# TS3 Mobile 1.3.0

**versionCode 17 · Android 8.0+ · Release Candidate Draft (pending final signed build, physical-device sign-off, and final CI)**

## Channel Files, Attachments, and Chat

- **Read-only channel files, chat attachments, and server images:** Explicitly browse and download channel files or same-server chat attachments (up to 20 MiB), staging and verifying lengths before saving through Android's document picker. User rows display avatars on the leading tile and server/channel icons open in a preview dialog. Downloads always connect strictly to the active session peer address and accept empty, wildcard, and dual-stack multi-address offers offline without automatic prefetching or cross-host redirects.
- **Opt-in local chat history and formatting:** Added an off-by-default bounded encrypted archive for eligible new chat messages with offline browsing and deletion. Chat renders basic TeamSpeak BBCode, confirms external link targets before opening, and copies original plain text.

## Floating PTT, Bookmarks, and Settings

- **Connected floating PTT:** Standard builds include notification-recalled floating PTT and its private settings screen, with optional silent display on Home that never opens the microphone automatically. Only Push-to-Talk allows press-to-talk transmission; other modes stay read-only, and active microphone trials block concurrent floating capture.
- **Bookmark credential isolation and UI polish:** Editing a bookmark's host or port clears saved passwords and the old default channel from the draft (editing only the default channel clears only the channel password). Bookmark **Connect now** does not tear down an active session. Standardized controls, large-text reflow, grouped Identity & Backups cards, and on-demand Help under **Settings → Help and about → Feature help**.

## Reliability, Audio, and Privacy Hardening

- **Session lifecycle and connection recovery:** Hardened exit/connect concurrency so disconnections preserve user microphone settings and diagnostics, stale failures cannot close replacement sessions or leave the UI stuck connecting, and known handshake unsigned-width errors get bounded retry mitigation while preserving the pinned `ts3mobile-1` dependency.
- **Bluetooth and playback safeguards:** Added continuous live checks for unnotified Bluetooth auto-route fallbacks, prevented leaked idle tracks after delayed Bluetooth readiness, bounded stale media eviction to 360 ms, moved local trial/diagnostic I/O off the main thread, and excluded playback and update DataStores from system backup rules.
- **Desktop INI boundary:** Standard release builds keep desktop INI preview import disabled by default (available in Debug/QA builds only).

## Upgrade and Remaining Release Gates

- Retains application ID `io.github.ts3mobile.app` and the existing public signing certificate for in-place upgrades from 1.2.0 (versionCode 16 → 17), preserving local identity, bookmarks, form data, and preferences. Disconnect first; do not uninstall or clear storage.
- **Release gate notice:** This is a pre-release draft. Before tagging `v1.3.0` or publishing artifacts, the final commit must pass Linux/x86_64 CI (including the TS3j source fallback build), final signed APK verification, and the outstanding physical-device checks (in-playback Bluetooth disconnect, network switches, 30+ min screen-off soak, notification/overlay interactions, and real backup/restore) recorded in [Candidate status](../CANDIDATE_STATUS_2026-11-25.md) and [Handoff](../HANDOFF_1.3.0_2026-11-26_ZH.md), alongside matching `BUILDINFO.json` and `SHA256SUMS.txt`.
